package com.transactionprocessor.transactionprocessor.recon;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import com.transactionprocessor.transactionprocessor.normalizer.NormalizerService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maybank net-to-zero: drops perfect negative/positive pairs from the
 * reconciliation input.
 *
 * <p>A pair is two Maybank rows ({@code MAYBANK_SA}, {@code MAYBANK_CC} or legacy
 * {@code MAYBANK}) with the same canonical payee, the same amount currency, and
 * amounts that sum to exactly zero (scale-insensitive, e.g. {@code -49.54} and
 * {@code 49.54}). Typical legs are pre-auth holds vs refunds, card spends vs
 * refunds/payments, or any reversal chain whose legs net to zero: keeping both
 * would overstate spend and income, so both are removed. A {@code D-C-D} chain
 * (debit, credit reversal, differing final settlement) nets the first two legs
 * and keeps the final settlement row.
 *
 * <p>Matching ignores timestamp, type and description: only the Wallet-visible
 * triple (payee, currency, absolute amount) plus opposite signs must agree.
 * When a key has N negatives and M positives, {@code min(N, M)} pairs are
 * removed and the remainder is kept, preserving the input order. Non-Maybank
 * rows, zero amounts and rows without an amount never participate and are
 * always kept.
 *
 * <p>Second rule — card-payment transfers: paying the credit-card bill out of
 * savings produces two legs that never share a payee (SA side
 * {@code PYMT FROM A/C | MASTERCARD …}, CC side {@code PYMT@MAYBANK2U.COM}),
 * so the payee rule cannot see them. A CC payment credit and an SA
 * card-payment debit with the same currency, exactly opposite amounts and
 * timestamps within {@value #CARD_PAYMENT_MAX_DAYS} days are therefore paired
 * as well, earliest-first. A lone leg (no counterpart within the window) is
 * always kept.
 *
 * <p>Pure in-memory filter: the input list is untouched and nothing is deleted.
 * {@code unified_transaction} keeps every imported row; paired legs simply
 * produce no recon output (no Maybank copy, and no Grab top-up mirror for a
 * netted top-up leg). Deterministic per input, so repeated recon runs over the
 * same stored rows regenerate identical candidates.
 */
public final class MaybankPairNetting {

    private MaybankPairNetting() {}

    /**
     * Maximum timestamp gap for the card-payment transfer rule. Same-statement
     * legs normally post within hours; the window tolerates weekend/posting
     * delays without reaching across monthly billing cycles.
     */
    static final long CARD_PAYMENT_MAX_DAYS = 7;

    /**
     * Both netting rules in order: same-payee pairs first, then card-payment
     * transfer pairs on the survivors.
     *
     * @return the surviving rows in their original order (a new list; input untouched)
     */
    public static List<UnifiedTransaction> net(List<UnifiedTransaction> rows) {
        return cancelCardPaymentPairs(cancelPerfectPairs(rows));
    }

    /**
     * @return the surviving rows in their original order (a new list; input untouched)
     */
    public static List<UnifiedTransaction> cancelPerfectPairs(List<UnifiedTransaction> rows) {
        String[] keys = new String[rows.size()];
        Map<String, Integer> negCount = new HashMap<>();
        Map<String, Integer> posCount = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            UnifiedTransaction u = rows.get(i);
            String key = pairKey(u);
            keys[i] = key;
            if (key == null) continue;
            int sign = u.getAmount().signum();
            if (sign < 0) negCount.merge(key, 1, Integer::sum);
            else if (sign > 0) posCount.merge(key, 1, Integer::sum);
        }
        Map<String, Integer> negToRemove = new HashMap<>();
        Map<String, Integer> posToRemove = new HashMap<>();
        for (String key : negCount.keySet()) {
            Integer positives = posCount.get(key);
            if (positives == null || positives == 0) continue;
            int pairs = Math.min(negCount.get(key), positives);
            if (pairs > 0) {
                negToRemove.put(key, pairs);
                posToRemove.put(key, pairs);
            }
        }
        if (negToRemove.isEmpty()) return new ArrayList<>(rows);
        List<UnifiedTransaction> survivors = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            String key = keys[i];
            if (key == null) {
                survivors.add(rows.get(i));
                continue;
            }
            int sign = rows.get(i).getAmount().signum();
            if (sign < 0) {
                Integer left = negToRemove.get(key);
                if (left != null && left > 0) {
                    negToRemove.put(key, left - 1);
                    continue;
                }
            } else if (sign > 0) {
                Integer left = posToRemove.get(key);
                if (left != null && left > 0) {
                    posToRemove.put(key, left - 1);
                    continue;
                }
            }
            survivors.add(rows.get(i));
        }
        return survivors;
    }

    /**
     * Card-payment transfer rule: pairs each CC payment credit
     * ({@code PYMT@MAYBANK2U.COM}-shaped, positive) with an SA card-payment
     * debit of the same currency and exactly opposite amount within
     * {@value #CARD_PAYMENT_MAX_DAYS} days, earliest-first. Unmatched legs are
     * kept in their original positions.
     *
     * @return the surviving rows in their original order (a new list; input untouched)
     */
    public static List<UnifiedTransaction> cancelCardPaymentPairs(List<UnifiedTransaction> rows) {
        Map<String, List<Integer>> creditsByKey = new HashMap<>();
        Map<String, List<Integer>> debitsByKey = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            UnifiedTransaction u = rows.get(i);
            if (u == null || u.getAmount() == null) continue;
            if (isCcPaymentCredit(u)) {
                creditsByKey.computeIfAbsent(transferKey(u), k -> new ArrayList<>()).add(i);
            } else if (isSaCardPaymentDebit(u)) {
                debitsByKey.computeIfAbsent(transferKey(u), k -> new ArrayList<>()).add(i);
            }
        }
        if (creditsByKey.isEmpty() || debitsByKey.isEmpty()) return new ArrayList<>(rows);
        boolean[] doomed = new boolean[rows.size()];
        boolean pairedAny = false;
        for (Map.Entry<String, List<Integer>> e : creditsByKey.entrySet()) {
            List<Integer> debits = debitsByKey.get(e.getKey());
            if (debits == null || debits.isEmpty()) continue;
            List<Integer> credits = e.getValue();
            List<Integer> openDebits = new ArrayList<>(debits);
            // Both index lists are in input order; input order is statement
            // order, so earliest-first pairing is chronological per key.
            for (int ci : credits) {
                LocalDateTime ct = rows.get(ci).getTimestamp();
                for (int k = 0; k < openDebits.size(); k++) {
                    int di = openDebits.get(k);
                    if (withinDays(ct, rows.get(di).getTimestamp(), CARD_PAYMENT_MAX_DAYS)) {
                        doomed[ci] = true;
                        doomed[di] = true;
                        openDebits.remove(k);
                        pairedAny = true;
                        break;
                    }
                }
            }
        }
        if (!pairedAny) return new ArrayList<>(rows);
        List<UnifiedTransaction> survivors = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            if (!doomed[i]) survivors.add(rows.get(i));
        }
        return survivors;
    }

    /**
     * Pairing key for Maybank netting, or {@code null} when the row cannot take
     * part in a pair (non-Maybank source, missing/zero amount).
     */
    private static String pairKey(UnifiedTransaction u) {
        if (u == null || u.getAmount() == null || u.getAmount().signum() == 0) return null;
        SourceType source = u.getSource();
        if (source != SourceType.MAYBANK_SA && source != SourceType.MAYBANK_CC
                && source != SourceType.MAYBANK) return null;
        String payee = NormalizerService.canonicalPayee(u.getPayee());
        String currency = u.getAmountCurrency() == null ? "" : u.getAmountCurrency().strip().toUpperCase(Locale.ROOT);
        String abs = NormalizerService.dedupAmountPlain(u.getAmount().abs());
        return payee + "|" + currency + "|" + abs;
    }

    /** Transfer key: currency + absolute amount only — the two legs never share a payee. */
    private static String transferKey(UnifiedTransaction u) {
        String currency = u.getAmountCurrency() == null ? "" : u.getAmountCurrency().strip().toUpperCase(Locale.ROOT);
        return currency + "|" + NormalizerService.dedupAmountPlain(u.getAmount().abs());
    }

    /**
     * CC-side leg of a card-bill payment: money INTO the card account
     * (positive) whose free text marks it as a payment rather than a merchant
     * refund. Merchant refunds ({@code ALP*…CR} etc.) carry no payment marker
     * and never qualify.
     */
    static boolean isCcPaymentCredit(UnifiedTransaction u) {
        if (u.getSource() != SourceType.MAYBANK_CC) return false;
        if (u.getAmount() == null || u.getAmount().signum() <= 0) return false;
        String hay = haystack(u);
        return hay.contains("PYMT") || hay.contains("PAYMENT") || hay.contains("MAYBANK2U");
    }

    /**
     * SA-side leg of a card-bill payment: money OUT of savings (negative)
     * towards a credit card — a card-network marker ({@code MASTERCARD} /
     * {@code VISA} / {@code CREDITCARD}) plus a payment marker. Ordinary
     * transfers, DuitNow spends and loan payments carry no card marker and
     * never qualify.
     */
    static boolean isSaCardPaymentDebit(UnifiedTransaction u) {
        SourceType source = u.getSource();
        if (source != SourceType.MAYBANK_SA && source != SourceType.MAYBANK) return false;
        if (u.getAmount() == null || u.getAmount().signum() >= 0) return false;
        String hay = haystack(u);
        boolean card = hay.contains("MASTERCARD") || hay.contains("VISA") || hay.contains("CREDITCARD");
        if (!card) return false;
        return hay.contains("PYMT") || hay.contains("PAYMENT") || hay.contains("CARD");
    }

    /** Alphanumerics-only upper-cased free text (payee + description + reference). */
    private static String haystack(UnifiedTransaction u) {
        String s = (nullToEmpty(u.getPayee()) + " " + nullToEmpty(u.getDescription())
                + " " + nullToEmpty(u.getReference())).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** True when both timestamps exist and are at most {@code maxDays} apart. */
    private static boolean withinDays(LocalDateTime a, LocalDateTime b, long maxDays) {
        if (a == null || b == null) return false;
        long days = Math.abs(java.time.Duration.between(a, b).toDays());
        return days <= maxDays;
    }
}
