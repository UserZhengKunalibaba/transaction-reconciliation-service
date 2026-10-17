package com.transactionprocessor.transactionprocessor.recon;

import com.transactionprocessor.transactionprocessor.domain.ReconTransaction;
import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import com.transactionprocessor.transactionprocessor.normalizer.NormalizerService;
import com.transactionprocessor.transactionprocessor.recon.ReconciliationService.ReconResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Self transfers (Maybank → Cash): links a Maybank savings outflow to yourself
 * with the matching TNG receive from yourself, so each move reads as one
 * transfer — negative on the Maybank side, positive on the Cash side — instead
 * of an expense plus an income.
 *
 * <p>Self is recognised by counterparty name ({@code CHANG YIN CHENG} and its
 * variants {@code CHANGYINCHENG *}, {@code C.Y. CHENG}, …). Linking requires
 * the same currency on both legs (the Maybank↔Cash corridor observed in the
 * statements is MYR throughout). TNG sends to yourself are retyped as Cash
 * transfer-outs; no Maybank leg is ever invented for them.
 *
 * <p>Linking is 1:1, earliest-first: same currency, exactly opposite amounts
 * (scale-insensitive) and timestamps within {@value #LINK_MAX_DAYS} days. Both
 * legs' recon copies are retyped to {@code TRANSFER} (accounts untouched, so
 * the Maybank leg stays negative and the Cash leg stays positive). An SA debit
 * with no TNG counterpart in the window is an orphan: it still reads as a
 * Maybank→Cash transfer, so a Cash {@code TRANSFER} mirror for its full amount
 * is synthesised (same pattern as Grab top-up mirrors). An orphan TNG receive
 * is only retyped — Maybank legs are never invented.
 *
 * <p>Pure with respect to storage: only the passed-in candidate copies are
 * mutated in place and mirrors returned; {@code unified_transaction} is never
 * touched. Mirror rows reuse their source debit's id with the Cash account, so
 * the existing {@code UNIQUE (source_unified_id, account)} key keeps retries
 * idempotent. When a TNG counterpart arrives on a LATER run than its debit,
 * the stale mirror is revoked by the caller (see the {@code 2d} step in
 * {@link ReconciliationService#run}): a mirror is the only recon row keyed
 * {@code (debitId, Cash)}, so revoking it never touches the real counterpart
 * row. Deterministic per input.
 */
public final class SelfTransfer {

    private SelfTransfer() {}

    /** Maximum timestamp gap when linking an SA outflow to its TNG receive. */
    static final long LINK_MAX_DAYS = 3;

    /**
     * Retypes self-transfer copies to {@code TRANSFER} and synthesises Cash
     * mirrors for orphan SA debits.
     *
     * @param unified    netted unified input the candidates were built from
     * @param candidates recon copies built so far (mutated in place)
     * @param counters   run counters (self-transfer fields incremented)
     * @return mirrors for orphan SA debits plus the linked debit ids (whose
     *         stale mirrors, if any, the caller must revoke)
     */
    public static Outcome link(List<UnifiedTransaction> unified,
                               List<ReconTransaction> candidates,
                               ReconResult counters) {
        Map<String, ReconTransaction> byKey = new HashMap<>();
        for (ReconTransaction c : candidates) {
            byKey.put(key(c.getSourceUnifiedId(), c.getAccount()), c);
        }
        List<UnifiedTransaction> debits = new ArrayList<>();
        List<UnifiedTransaction> receives = new ArrayList<>();
        List<UnifiedTransaction> sends = new ArrayList<>();
        for (UnifiedTransaction u : unified) {
            if (!isSelf(u) || u.getAmount() == null || !isMyr(u)) continue;
            if (isSaDebit(u) && byKey.containsKey(key(u.getId(), WalletAccount.Maybank))) {
                debits.add(u);
            } else if (isTngReceive(u) && byKey.containsKey(key(u.getId(), WalletAccount.Cash))) {
                receives.add(u);
            } else if (isTngSend(u) && byKey.containsKey(key(u.getId(), WalletAccount.Cash))) {
                sends.add(u);
            }
        }
        debits.sort(Comparator.comparing(UnifiedTransaction::getTimestamp,
                Comparator.nullsLast(Comparator.naturalOrder())));
        receives.sort(Comparator.comparing(UnifiedTransaction::getTimestamp,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<UnifiedTransaction> openReceives = new ArrayList<>(receives);
        List<UnifiedTransaction> orphanDebits = new ArrayList<>();
        List<Long> linkedDebitIds = new ArrayList<>();
        for (UnifiedTransaction debit : debits) {
            UnifiedTransaction match = null;
            for (int k = 0; k < openReceives.size(); k++) {
                UnifiedTransaction r = openReceives.get(k);
                if (sameCurrency(debit, r)
                        && NormalizerService.dedupAmountPlain(debit.getAmount().abs())
                                .equals(NormalizerService.dedupAmountPlain(r.getAmount().abs()))
                        && withinDays(debit.getTimestamp(), r.getTimestamp(), LINK_MAX_DAYS)) {
                    match = r;
                    openReceives.remove(k);
                    break;
                }
            }
            ReconTransaction debitCopy = byKey.get(key(debit.getId(), WalletAccount.Maybank));
            if (match != null) {
                debitCopy.setType("TRANSFER");
                byKey.get(key(match.getId(), WalletAccount.Cash)).setType("TRANSFER");
                counters.selfTransferPairs++;
                linkedDebitIds.add(debit.getId());
            } else {
                debitCopy.setType("TRANSFER");
                orphanDebits.add(debit);
            }
        }
        for (UnifiedTransaction r : openReceives) {
            byKey.get(key(r.getId(), WalletAccount.Cash)).setType("TRANSFER");
        }
        for (UnifiedTransaction s : sends) {
            byKey.get(key(s.getId(), WalletAccount.Cash)).setType("TRANSFER");
        }
        List<ReconTransaction> mirrors = new ArrayList<>(orphanDebits.size());
        for (UnifiedTransaction debit : orphanDebits) {
            mirrors.add(selfTransferIncomeOf(debit));
            counters.selfTransferMirrors++;
        }
        return new Outcome(mirrors, linkedDebitIds);
    }

    /** Result of {@link #link}: Cash mirrors to insert + linked debit ids to revoke stale mirrors for. */
    public record Outcome(List<ReconTransaction> mirrors, List<Long> linkedDebitIds) {}

    /** Counterparty is the account holder (CHANG YIN CHENG / CHANGYINCHENG* / C.Y. CHENG …). */
    static boolean isSelf(UnifiedTransaction u) {
        if (u == null || u.getPayee() == null) return false;
        String key = u.getPayee().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        return key.equals("CHANGYINCHENG") || key.equals("CYCHENG");
    }

    private static boolean isMyr(UnifiedTransaction u) {
        return u.getAmountCurrency() != null && u.getAmountCurrency().equalsIgnoreCase("MYR");
    }

    private static boolean isSaDebit(UnifiedTransaction u) {
        SourceType source = u.getSource();
        if (source != SourceType.MAYBANK_SA && source != SourceType.MAYBANK) return false;
        return u.getAmount() != null && u.getAmount().signum() < 0;
    }

    private static boolean isTngReceive(UnifiedTransaction u) {
        if (u.getSource() != SourceType.TNG) return false;
        return u.getAmount() != null && u.getAmount().signum() > 0;
    }

    private static boolean isTngSend(UnifiedTransaction u) {
        if (u.getSource() != SourceType.TNG) return false;
        return u.getAmount() != null && u.getAmount().signum() < 0;
    }

    private static boolean sameCurrency(UnifiedTransaction a, UnifiedTransaction b) {
        if (a.getAmountCurrency() == null || b.getAmountCurrency() == null) return false;
        return a.getAmountCurrency().equalsIgnoreCase(b.getAmountCurrency());
    }

    private static boolean withinDays(LocalDateTime a, LocalDateTime b, long maxDays) {
        if (a == null || b == null) return false;
        long days = Math.abs(java.time.Duration.between(a, b).toDays());
        return days <= maxDays;
    }

    private static String key(Long sourceUnifiedId, WalletAccount account) {
        return sourceUnifiedId + "|" + (account == null ? "?" : account.name());
    }

    /** Mirrored Cash transfer-in for an orphan SA self-debit: same timestamp, positive amount. */
    static ReconTransaction selfTransferIncomeOf(UnifiedTransaction saDebit) {
        ReconTransaction r = new ReconTransaction();
        r.setSourceUnifiedId(saDebit.getId());
        r.setTimestamp(saDebit.getTimestamp());
        r.setAccount(WalletAccount.Cash);
        r.setAmount(saDebit.getAmount() == null ? java.math.BigDecimal.ZERO : saDebit.getAmount().abs());
        r.setAmountCurrency(saDebit.getAmountCurrency());
        r.setSource(saDebit.getSource());
        r.setPayee(saDebit.getPayee() == null ? "" : saDebit.getPayee());
        String orig = saDebit.getDescription() == null ? "" : saDebit.getDescription();
        r.setDescription(orig.isEmpty() ? "Self transfer from Maybank" : "Self transfer from Maybank: " + orig);
        r.setType("TRANSFER");
        r.setCategory("Transfer");
        r.setReference(saDebit.getReference());
        r.setOriginalAmount(saDebit.getOriginalAmount());
        r.setOriginalCurrency(saDebit.getOriginalCurrency());
        return r;
    }
}
