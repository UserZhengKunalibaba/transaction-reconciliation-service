package com.transactionprocessor.transactionprocessor.recon;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaybankPairNettingTest {

    private static UnifiedTransaction row(String payee, String amount, SourceType source) {
        UnifiedTransaction u = new UnifiedTransaction();
        u.setTimestamp(LocalDateTime.of(2026, 9, 10, 10, 0));
        u.setPayee(payee);
        u.setAmount(new BigDecimal(amount));
        u.setAmountCurrency("MYR");
        u.setSource(source);
        u.setType(new BigDecimal(amount).signum() < 0 ? "EXPENSE" : "INCOME");
        return u;
    }

    @Test
    void perfectNegativePositivePairIsRemoved() {
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                row("WEIXIN*BEIJING CITY", "-49.54", SourceType.MAYBANK_SA),
                row("WEIXIN*BEIJING CITY", "49.54", SourceType.MAYBANK_SA)));
        assertTrue(MaybankPairNetting.cancelPerfectPairs(rows).isEmpty());
    }

    @Test
    void dcdChainKeepsFinalSettlement() {
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                row("WEIXIN*BEIJING CITY", "-49.54", SourceType.MAYBANK_SA),
                row("WEIXIN*BEIJING CITY", "49.54", SourceType.MAYBANK_SA),
                row("WEIXIN*BEIJING CITY", "-49.06", SourceType.MAYBANK_SA)));
        List<UnifiedTransaction> survivors = MaybankPairNetting.cancelPerfectPairs(rows);
        assertEquals(1, survivors.size());
        assertEquals(0, new BigDecimal("-49.06").compareTo(survivors.get(0).getAmount()));
    }

    @Test
    void differentPayeeOrAmountIsKept() {
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                row("METRO", "-20.00", SourceType.MAYBANK_SA),
                row("OTHER", "20.00", SourceType.MAYBANK_SA),
                row("METRO", "-21.00", SourceType.MAYBANK_SA),
                row("METRO", "20.00", SourceType.MAYBANK_SA)));
        // Only the METRO -20.00 / +20.00 legs pair; the rest stays.
        assertEquals(2, MaybankPairNetting.cancelPerfectPairs(rows).size());
    }

    @Test
    void nonMaybankRowsNeverParticipate() {
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                row("METRO", "-20.00", SourceType.TNG),
                row("METRO", "20.00", SourceType.TNG)));
        assertEquals(2, MaybankPairNetting.cancelPerfectPairs(rows).size());
    }

    @Test
    void scaleVariantsPairAndMajorityKeepsRemainder() {
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                row("METRO", "-20.0", SourceType.MAYBANK_CC),
                row("METRO", "20.00", SourceType.MAYBANK_CC),
                row("METRO", "-20.00", SourceType.MAYBANK_CC)));
        List<UnifiedTransaction> survivors = MaybankPairNetting.cancelPerfectPairs(rows);
        assertEquals(1, survivors.size());
        assertEquals(0, new BigDecimal("-20.00").compareTo(survivors.get(0).getAmount()));
    }

    @Test
    void preauthDebitAndRefundPair() {
        // The reported PSS BANDAR PUTRA case: adjacent synthetic timestamps,
        // whole-ringgit amounts, opposite signs.
        UnifiedTransaction debit = row("PSS BANDAR PUTRA 3 *", "-200", SourceType.MAYBANK_SA);
        debit.setTimestamp(LocalDateTime.of(2026, 2, 7, 0, 24));
        UnifiedTransaction refund = row("PSS BANDAR PUTRA 3 *", "200", SourceType.MAYBANK_SA);
        refund.setTimestamp(LocalDateTime.of(2026, 2, 7, 0, 27));
        assertTrue(MaybankPairNetting.cancelPerfectPairs(
                new ArrayList<>(List.of(debit, refund))).isEmpty());
    }

    @Test
    void legacyNonCanonicalPayeesStillPair() {
        // Rows stored before payee canonicalization (different case/spacing)
        // must still net with each other.
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                row("pss bandar putra 3 *", "-200", SourceType.MAYBANK_SA),
                row("  PSS BANDAR PUTRA 3  * ", "200.00", SourceType.MAYBANK_SA)));
        assertTrue(MaybankPairNetting.cancelPerfectPairs(rows).isEmpty());
    }

    @Test
    void inputListIsUntouched() {
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                row("METRO", "-20.00", SourceType.MAYBANK_SA),
                row("METRO", "20.00", SourceType.MAYBANK_SA)));
        MaybankPairNetting.cancelPerfectPairs(rows);
        assertEquals(2, rows.size(), "netting must not mutate its input");
    }

    private static UnifiedTransaction ccPayment(String amount, LocalDateTime ts) {
        UnifiedTransaction u = new UnifiedTransaction();
        u.setTimestamp(ts);
        u.setPayee("PYMT@MAYBANK2U.COM");
        u.setAmount(new BigDecimal(amount));
        u.setAmountCurrency("MYR");
        u.setSource(SourceType.MAYBANK_CC);
        u.setType("TRANSFER");
        u.setDescription("PYMT@MAYBANK2U.COM");
        u.setReference("CC0000|12/06|12/06");
        return u;
    }

    private static UnifiedTransaction saCardDebit(String amount, LocalDateTime ts) {
        UnifiedTransaction u = new UnifiedTransaction();
        u.setTimestamp(ts);
        u.setPayee("MASTERCARD ISLAMIC *");
        u.setAmount(new BigDecimal(amount).negate());
        u.setAmountCurrency("MYR");
        u.setSource(SourceType.MAYBANK_SA);
        u.setType("EXPENSE");
        u.setDescription("PYMT FROM A/C | MASTERCARD ISLAMIC * / XXXX-XXXX-XXXX-0000 / 00 | "
                + amount + "-");
        u.setReference("SA|PYMT FROM A/C");
        return u;
    }

    @Test
    void cardPaymentTransferPairIsRemoved() {
        // The reported June case: SA debit and CC credit, different payees.
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                saCardDebit("719.93", LocalDateTime.of(2026, 6, 12, 1, 18)),
                ccPayment("719.93", LocalDateTime.of(2026, 6, 12, 0, 6))));
        assertTrue(MaybankPairNetting.cancelCardPaymentPairs(rows).isEmpty());
        assertTrue(MaybankPairNetting.net(rows).isEmpty());
    }

    @Test
    void loneCardPaymentLegIsKept() {
        // August case in the corpus: CC credit with no SA counterpart yet.
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                ccPayment("288.58", LocalDateTime.of(2026, 8, 13, 0, 6))));
        assertEquals(1, MaybankPairNetting.cancelCardPaymentPairs(rows).size());
        assertEquals(1, MaybankPairNetting.net(rows).size());
    }

    @Test
    void merchantRefundCreditDoesNotPairWithCardDebit() {
        // A merchant refund on the card must not net against a card payment.
        UnifiedTransaction refund = ccPayment("719.93", LocalDateTime.of(2026, 6, 12, 0, 6));
        refund.setPayee("ALP*GANKAIPIN");
        refund.setDescription("ALP*GANKAIPIN / Shanghai CN | RM 719.93CR");
        refund.setReference("CC0000|12/06|12/06");
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                saCardDebit("719.93", LocalDateTime.of(2026, 6, 12, 1, 18)), refund));
        assertEquals(2, MaybankPairNetting.cancelCardPaymentPairs(rows).size());
    }

    @Test
    void ordinarySaTransferDoesNotPairWithCardCredit() {
        // No card-network marker on the SA side: kept.
        UnifiedTransaction transfer = saCardDebit("719.93", LocalDateTime.of(2026, 6, 12, 1, 18));
        transfer.setPayee("NICHOLAS YEO WEI MI*");
        transfer.setDescription("TRANSFER FROM A/C | NICHOLAS YEO WEI MI* / 488094840Q / MAE QR | 719.93-");
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                transfer, ccPayment("719.93", LocalDateTime.of(2026, 6, 12, 0, 6))));
        assertEquals(2, MaybankPairNetting.cancelCardPaymentPairs(rows).size());
    }

    @Test
    void cardPaymentOutsideWindowIsKept() {
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                saCardDebit("719.93", LocalDateTime.of(2026, 6, 12, 1, 18)),
                ccPayment("719.93", LocalDateTime.of(2026, 6, 25, 0, 6))));
        assertEquals(2, MaybankPairNetting.cancelCardPaymentPairs(rows).size());
    }

    @Test
    void cardPaymentUnbalancedKeepsRemainder() {
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                saCardDebit("719.93", LocalDateTime.of(2026, 6, 12, 1, 18)),
                saCardDebit("719.93", LocalDateTime.of(2026, 7, 12, 1, 18)),
                ccPayment("719.93", LocalDateTime.of(2026, 6, 12, 0, 6))));
        List<UnifiedTransaction> survivors = MaybankPairNetting.cancelCardPaymentPairs(rows);
        assertEquals(1, survivors.size());
        assertEquals(LocalDateTime.of(2026, 7, 12, 1, 18), survivors.get(0).getTimestamp());
    }
}
