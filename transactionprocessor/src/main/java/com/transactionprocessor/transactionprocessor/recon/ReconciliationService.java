package com.transactionprocessor.transactionprocessor.recon;

import com.transactionprocessor.transactionprocessor.domain.ReconTransaction;
import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import com.transactionprocessor.transactionprocessor.repo.ReconTransactionRepository;
import com.transactionprocessor.transactionprocessor.repo.UnifiedTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rebuilt reconciliation: unified records → three WalletApp accounts → one output table.
 *
 * <ul>
 *   <li>Maybank account = union of MAYBANK_SA + MAYBANK_CC (+ legacy MAYBANK),
 *       minus perfect negative/positive pairs (see {@link MaybankPairNetting}):
 *       paired legs net to zero and produce no output at all.</li>
 *   <li>Grab account = every GRAB row copied untouched (expenses stay expenses) plus one
 *       synthesized INCOME per Maybank→GrabPay top-up, sharing the exact source timestamp
 *       with a mirrored (positive) amount.</li>
 *   <li>Cash account = TNG copied faithfully; ALIPAY / WECHATPAY expenses are reconciled
 *       against the Maybank CNY pool (see below), everything else copied faithfully.
 *       Self transfers (see {@link SelfTransfer}) read as Maybank→Cash transfers:
 *       linked legs are retyped to {@code TRANSFER} and orphan SA debits gain a
 *       Cash transfer-in mirror.</li>
 * </ul>
 *
 * <p>Alipay / WeChat Pay rule: each EXPENSE row carries its spend in CNY. When that exact
 * CNY amount (scale-insensitive, e.g. {@code 300.76} = {@code 300.760}) is stated on any
 * Maybank record ({@code originalAmount} in {@code CNY}, i.e. the CC-bill
 * {@code TRANSACTED AMOUNT}), the spend already settled via Maybank, so no Cash row is
 * emitted. Unmatched expenses cannot be valued in MYR, so they are kept as a
 * {@code -0.01 MYR} placeholder with the original CNY preserved in
 * {@code originalAmount}/{@code originalCurrency} for traceability. Matching is pure
 * set-membership over a volatile in-memory pool — no per-row ordering, no consumption,
 * no state — so retries deterministically regenerate identical candidates.
 *
 * <p>Volatile: candidates are plain in-memory objects; the only persistence is the final
 * per-row insert into {@code recon_transaction}. No intermediate tables, no status columns,
 * no state machine — rerunning over the same input regenerates identical rows.
 *
 * <p>Idempotency without a DB transaction: the method is deliberately NOT
 * {@code @Transactional}. Each row is inserted with its own auto-committed statement
 * (see {@link ReconTransactionWriter}); the {@code UNIQUE (source_unified_id, account)}
 * key turns retries into counted skips instead of duplicates. A cheap pre-query filters
 * the obvious repeats so the happy path rarely hits a constraint violation.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final UnifiedTransactionRepository unifiedRepo;
    private final ReconTransactionRepository reconRepo;
    private final ReconTransactionWriter writer;

    public ReconciliationService(UnifiedTransactionRepository unifiedRepo,
                                 ReconTransactionRepository reconRepo,
                                 ReconTransactionWriter writer) {
        this.unifiedRepo = unifiedRepo;
        this.reconRepo = reconRepo;
        this.writer = writer;
    }

    public static class ReconResult {
        public int maybankCopied;
        public int maybankNetted;
        public int grabCopied;
        public int grabTopupIncome;
        public int selfTransferPairs;
        public int selfTransferMirrors;
        public int selfTransferRevoked;
        public int cashCopied;
        public int cashMatched;
        public int cashPlaceholder;
        public int inserted;
        public int skipped;
    }

    /** Placeholder for an Alipay/WeChat expense with no Maybank settlement: value unknown in MYR. */
    static final BigDecimal UNMATCHED_PLACEHOLDER = new BigDecimal("-0.01");

    // NOTE: no @Transactional here by design (see class javadoc).
    public ReconResult run() {
        ReconResult r = new ReconResult();
        List<UnifiedTransaction> loaded = unifiedRepo.findAll();

        // 0. Maybank net-to-zero (in-memory only; unified rows are untouched):
        // perfect negative/positive pairs — same-payee reversals and SA/CC
        // card-payment transfers — are dropped before anything else, so they
        // contribute neither a Maybank copy, nor a Grab top-up mirror, nor a
        // CNY pool entry.
        List<UnifiedTransaction> all = MaybankPairNetting.net(loaded);
        r.maybankNetted = loaded.size() - all.size();

        // 1. Volatile pool of every CNY amount stated on Maybank records (CC-bill foreign
        // amounts). Built from the entire Maybank set before any Cash decision is made.
        Set<String> maybankCny = new HashSet<>();
        for (UnifiedTransaction u : all) {
            if (!isMaybank(u.getSource())) continue;
            BigDecimal cny = cnyAmountOf(u);
            if (cny != null && cny.signum() != 0) maybankCny.add(cnyKey(cny));
        }

        // 2. Build candidates volatile in memory — nothing is persisted in this step.
        List<ReconTransaction> candidates = new ArrayList<>();
        for (UnifiedTransaction u : all) {
            if (u.getTimestamp() == null || u.getAmount() == null) continue;
            WalletAccount account = accountFor(u.getSource());
            if (account == null) continue;
            // 2a. Alipay / WeChat Pay expenses: drop when the exact CNY amount settled
            // via Maybank, otherwise keep as a -0.01 MYR placeholder.
            if (isAlipayOrWechat(u.getSource()) && "EXPENSE".equals(u.getType())) {
                BigDecimal cny = cnyAmountOf(u);
                if (cny != null && cny.signum() != 0) {
                    if (maybankCny.contains(cnyKey(cny))) {
                        r.cashMatched++;
                        continue;
                    }
                    candidates.add(cashPlaceholderOf(u));
                    r.cashCopied++;
                    r.cashPlaceholder++;
                    continue;
                }
                // No CNY stated (should not happen — the normalizer always sets it):
                // fall through to a faithful copy rather than destroying information.
            }
            candidates.add(copyOf(u, account));
            switch (account) {
                case Maybank -> r.maybankCopied++;
                case Grab -> r.grabCopied++;
                case Cash -> r.cashCopied++;
            }
            // 2b. Grab top-up mirror: one INCOME in the Grab account per Maybank→GrabPay
            // transfer, with the exact same timestamp.
            if (isMaybank(u.getSource()) && isGrabPayTopup(u)) {
                candidates.add(grabTopupIncomeOf(u));
                r.grabTopupIncome++;
            }
        }

        // 2c. Self transfers Maybank→Cash: linked legs are retyped to TRANSFER
        // in place; orphan SA debits gain one Cash transfer-in mirror each.
        SelfTransfer.Outcome selfOutcome = SelfTransfer.link(all, candidates, r);
        candidates.addAll(selfOutcome.mirrors());
        // 2d. Revoke stale mirrors: an SA debit linked now may have been
        // mirrored on an earlier run, before its TNG counterpart arrived. The
        // mirror is the only row keyed (debitId, Cash), so this delete cannot
        // touch the real counterpart row.
        for (Long debitId : selfOutcome.linkedDebitIds()) {
            r.selfTransferRevoked += writer.deleteOne(debitId, WalletAccount.Cash);
        }

        // 3. Retry pre-filter: drop candidates whose (sourceUnifiedId, account) already stored.
        Set<String> existing = new HashSet<>();
        for (ReconTransaction e : reconRepo.findAll()) {
            existing.add(key(e.getSourceUnifiedId(), e.getAccount()));
        }
        List<ReconTransaction> fresh = new ArrayList<>();
        for (ReconTransaction c : candidates) {
            if (!existing.contains(key(c.getSourceUnifiedId(), c.getAccount()))) {
                fresh.add(c);
            }
        }

        // 4. Insert each row independently (no transaction); duplicate keys = skips.
        for (ReconTransaction c : fresh) {
            if (writer.insertOne(c)) {
                r.inserted++;
                existing.add(key(c.getSourceUnifiedId(), c.getAccount()));
            } else {
                r.skipped++;
            }
        }
        r.skipped += candidates.size() - fresh.size();
        log.info("Recon run: {} candidates (Maybank {} + Grab {} + top-up {} + Cash {} = matched {} + placeholder {}), netted {} Maybank offsetting row(s), self-transfers {} pair(s) + {} mirror(s) - {} revoked, inserted {}, skipped {}",
                candidates.size(), r.maybankCopied, r.grabCopied, r.grabTopupIncome, r.cashCopied,
                r.cashMatched, r.cashPlaceholder, r.maybankNetted,
                r.selfTransferPairs, r.selfTransferMirrors, r.selfTransferRevoked, r.inserted, r.skipped);
        return r;
    }

    static WalletAccount accountFor(SourceType source) {
        if (source == null) return null;
        return switch (source) {
            case MAYBANK_SA, MAYBANK_CC, MAYBANK -> WalletAccount.Maybank;
            case GRAB -> WalletAccount.Grab;
            case TNG, ALIPAY, WECHATPAY -> WalletAccount.Cash;
        };
    }

    private static boolean isMaybank(SourceType source) {
        return source == SourceType.MAYBANK_SA || source == SourceType.MAYBANK_CC || source == SourceType.MAYBANK;
    }

    /**
     * A Maybank record is a transfer to GrabPay when money leaves Maybank and any of the
     * free-text fields mention GRABPAY. The match is on the alphanumerics-only upper-cased
     * text so "GrabPay", "GRABPAY-EC", "Grab Pay" all hit, while plain "GRAB FOOD" spends
     * (direct purchases, not wallet top-ups) do not.
     */
    static boolean isGrabPayTopup(UnifiedTransaction u) {
        if (u.getAmount() == null || u.getAmount().signum() >= 0) return false;
        String hay = (nullToEmpty(u.getPayee()) + " " + nullToEmpty(u.getDescription())
                + " " + nullToEmpty(u.getReference())).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return hay.contains("GRABPAY");
    }

    private static boolean isAlipayOrWechat(SourceType source) {
        return source == SourceType.ALIPAY || source == SourceType.WECHATPAY;
    }

    /**
     * Absolute CNY amount stated on a unified row: the structured original first
     * (Alipay/WeChat originals, CC-bill {@code TRANSACTED AMOUNT}), falling back to the
     * canonical amount when it is itself denominated in CNY. Null when the row states
     * no CNY.
     */
    static BigDecimal cnyAmountOf(UnifiedTransaction u) {
        if ("CNY".equalsIgnoreCase(u.getOriginalCurrency()) && u.getOriginalAmount() != null) {
            return u.getOriginalAmount().abs();
        }
        if ("CNY".equalsIgnoreCase(u.getAmountCurrency()) && u.getAmount() != null) {
            return u.getAmount().abs();
        }
        return null;
    }

    /** Scale-insensitive key so {@code 300.76} and {@code 300.760} match exactly. */
    static String cnyKey(BigDecimal cny) {
        return cny.stripTrailingZeros().toPlainString();
    }

    /**
     * Unmatched Alipay/WeChat expense kept as a {@code -0.01 MYR} placeholder: the MYR
     * value is unknowable without a Maybank settlement, but payee / description /
     * reference / category / original CNY are all preserved for review.
     */
    static ReconTransaction cashPlaceholderOf(UnifiedTransaction u) {
        ReconTransaction r = copyOf(u, WalletAccount.Cash);
        r.setAmount(UNMATCHED_PLACEHOLDER);
        r.setAmountCurrency("MYR");
        return r;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String key(Long sourceUnifiedId, WalletAccount account) {
        return sourceUnifiedId + "|" + (account == null ? "?" : account.name());
    }

    /** Faithful 1:1 copy of a unified row into the given WalletApp account. */
    static ReconTransaction copyOf(UnifiedTransaction u, WalletAccount account) {
        ReconTransaction r = new ReconTransaction();
        r.setSourceUnifiedId(u.getId());
        r.setTimestamp(u.getTimestamp());
        r.setAccount(account);
        r.setAmount(u.getAmount());
        r.setAmountCurrency(u.getAmountCurrency());
        r.setSource(u.getSource());
        r.setPayee(u.getPayee() == null ? "" : u.getPayee());
        r.setDescription(u.getDescription());
        r.setType(u.getType());
        r.setCategory(u.getCategory());
        r.setReference(u.getReference());
        r.setOriginalAmount(u.getOriginalAmount());
        r.setOriginalCurrency(u.getOriginalCurrency());
        return r;
    }

    /** Mirrored income for a Maybank→GrabPay top-up: same timestamp, positive amount. */
    static ReconTransaction grabTopupIncomeOf(UnifiedTransaction maybank) {
        ReconTransaction r = new ReconTransaction();
        r.setSourceUnifiedId(maybank.getId());
        r.setTimestamp(maybank.getTimestamp());
        r.setAccount(WalletAccount.Grab);
        BigDecimal amt = maybank.getAmount() == null ? BigDecimal.ZERO : maybank.getAmount().abs();
        r.setAmount(amt);
        r.setAmountCurrency(maybank.getAmountCurrency());
        r.setSource(maybank.getSource());
        r.setPayee(maybank.getPayee() == null ? "" : maybank.getPayee());
        String orig = maybank.getDescription() == null ? "" : maybank.getDescription();
        r.setDescription(orig.isEmpty() ? "Grab top-up from Maybank" : "Grab top-up from Maybank: " + orig);
        r.setType("INCOME");
        r.setCategory(maybank.getCategory());
        r.setReference(maybank.getReference());
        r.setOriginalAmount(maybank.getOriginalAmount());
        r.setOriginalCurrency(maybank.getOriginalCurrency());
        return r;
    }
}
