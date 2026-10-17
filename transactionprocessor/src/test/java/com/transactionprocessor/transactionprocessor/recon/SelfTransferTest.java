package com.transactionprocessor.transactionprocessor.recon;

import com.transactionprocessor.transactionprocessor.domain.ReconTransaction;
import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelfTransferTest {

    private static long nextId = 1;

    private static UnifiedTransaction unified(SourceType source, String payee, String amount,
                                              LocalDateTime ts, String desc) {
        UnifiedTransaction u = new UnifiedTransaction();
        u.setId(nextId++);
        u.setTimestamp(ts);
        u.setPayee(payee);
        u.setAmount(new BigDecimal(amount));
        u.setAmountCurrency("MYR");
        u.setSource(source);
        u.setType(new BigDecimal(amount).signum() < 0 ? "EXPENSE" : "INCOME");
        u.setAccount(source == SourceType.TNG ? WalletAccount.Cash : WalletAccount.Maybank);
        u.setDescription(desc);
        u.setCategory("Transfer");
        u.setReference("ref");
        return u;
    }

    private static UnifiedTransaction saDebit(String amount, LocalDateTime ts) {
        return unified(SourceType.MAYBANK_SA, "CHANGYINCHENG *", "-" + amount, ts,
                "IBK FUND TFR FR A/C | CHANGYINCHENG * / Top up");
    }

    private static UnifiedTransaction tngReceive(String amount, LocalDateTime ts) {
        UnifiedTransaction u = unified(SourceType.TNG, "CHANG YIN CHENG", amount, ts,
                "DUITNOW_RECEI 20260331111 CHANG YIN CHENG 2026033110110000010000TNGOW3");
        u.setType("INCOME");
        return u;
    }

    private static List<ReconTransaction> candidatesOf(List<UnifiedTransaction> unified) {
        List<ReconTransaction> out = new ArrayList<>();
        for (UnifiedTransaction u : unified) {
            out.add(ReconciliationService.copyOf(u, u.getAccount()));
        }
        return out;
    }

    @Test
    void linkedPairIsRetypedWithNoMirror() {
        List<UnifiedTransaction> unified = new ArrayList<>(List.of(
                saDebit("76.89", LocalDateTime.of(2026, 3, 31, 4, 33)),
                tngReceive("76.89", LocalDateTime.of(2026, 3, 31, 8, 15))));
        List<ReconTransaction> candidates = candidatesOf(unified);
        ReconciliationService.ReconResult r = new ReconciliationService.ReconResult();
        SelfTransfer.Outcome outcome = SelfTransfer.link(unified, candidates, r);
        List<ReconTransaction> mirrors = outcome.mirrors();
        assertTrue(mirrors.isEmpty());
        assertEquals(1, r.selfTransferPairs);
        assertEquals(0, r.selfTransferMirrors);
        assertEquals(2, candidates.size());
        for (ReconTransaction c : candidates) {
            assertEquals("TRANSFER", c.getType());
        }
        // Accounts untouched: Maybank leg stays negative, Cash leg positive.
        assertTrue(candidates.stream().anyMatch(c ->
                c.getAccount() == WalletAccount.Maybank && c.getAmount().signum() < 0));
        assertTrue(candidates.stream().anyMatch(c ->
                c.getAccount() == WalletAccount.Cash && c.getAmount().signum() > 0));
    }

    @Test
    void orphanSaDebitGainsCashMirror() {
        List<UnifiedTransaction> unified = new ArrayList<>(List.of(
                saDebit("10.00", LocalDateTime.of(2026, 3, 2, 0, 9))));
        List<ReconTransaction> candidates = candidatesOf(unified);
        ReconciliationService.ReconResult r = new ReconciliationService.ReconResult();
        SelfTransfer.Outcome outcome = SelfTransfer.link(unified, candidates, r);
        List<ReconTransaction> mirrors = outcome.mirrors();
        assertEquals(0, r.selfTransferPairs);
        assertEquals(1, r.selfTransferMirrors);
        assertEquals(1, mirrors.size());
        ReconTransaction m = mirrors.get(0);
        assertEquals(WalletAccount.Cash, m.getAccount());
        assertEquals("TRANSFER", m.getType());
        assertEquals(0, new BigDecimal("10.00").compareTo(m.getAmount()));
        assertEquals(LocalDateTime.of(2026, 3, 2, 0, 9), m.getTimestamp());
        assertTrue(m.getDescription().startsWith("Self transfer from Maybank:"));
        assertEquals("TRANSFER", candidates.get(0).getType());
    }

    @Test
    void orphanTngReceiveIsRetypedWithoutMirror() {
        List<UnifiedTransaction> unified = new ArrayList<>(List.of(
                tngReceive("20.00", LocalDateTime.of(2025, 11, 17, 2, 30))));
        List<ReconTransaction> candidates = candidatesOf(unified);
        ReconciliationService.ReconResult r = new ReconciliationService.ReconResult();
        SelfTransfer.Outcome outcome = SelfTransfer.link(unified, candidates, r);
        List<ReconTransaction> mirrors = outcome.mirrors();
        assertTrue(mirrors.isEmpty());
        assertEquals("TRANSFER", candidates.get(0).getType());
    }

    @Test
    void doubleDebitSingleReceiveLinksOnceAndMirrorsRemainder() {
        List<UnifiedTransaction> unified = new ArrayList<>(List.of(
                saDebit("10.00", LocalDateTime.of(2026, 4, 1, 0, 15)),
                saDebit("10.00", LocalDateTime.of(2026, 4, 1, 0, 21)),
                tngReceive("10.00", LocalDateTime.of(2026, 4, 1, 8, 39))));
        List<ReconTransaction> candidates = candidatesOf(unified);
        ReconciliationService.ReconResult r = new ReconciliationService.ReconResult();
        SelfTransfer.Outcome outcome = SelfTransfer.link(unified, candidates, r);
        List<ReconTransaction> mirrors = outcome.mirrors();
        assertEquals(1, r.selfTransferPairs);
        assertEquals(1, r.selfTransferMirrors);
        assertEquals(1, mirrors.size());
    }

    @Test
    void nonSelfRowsUntouched() {
        UnifiedTransaction metro = unified(SourceType.TNG, "METRO", "-20.00",
                LocalDateTime.of(2026, 9, 10, 10, 0), "Metro spend");
        metro.setType("EXPENSE");
        List<UnifiedTransaction> unified = new ArrayList<>(List.of(metro));
        List<ReconTransaction> candidates = candidatesOf(unified);
        ReconciliationService.ReconResult r = new ReconciliationService.ReconResult();
        assertTrue(SelfTransfer.link(unified, candidates, r).mirrors().isEmpty());
        assertEquals("EXPENSE", candidates.get(0).getType());
        assertEquals(0, r.selfTransferPairs);
        assertEquals(0, r.selfTransferMirrors);
    }

    @Test
    void selfNameVariantsRecognised() {
        assertTrue(SelfTransfer.isSelf(saDebit("1.00", LocalDateTime.of(2026, 3, 2, 0, 9))));
        UnifiedTransaction cy = tngReceive("1.00", LocalDateTime.of(2026, 3, 2, 0, 9));
        cy.setPayee("C. Y. CHENG");
        assertTrue(SelfTransfer.isSelf(cy));
        UnifiedTransaction other = tngReceive("1.00", LocalDateTime.of(2026, 3, 2, 0, 9));
        other.setPayee("CHONG YI LING");
        assertFalse(SelfTransfer.isSelf(other));
    }

        @Test
    void inputListsUntouchedApartFromRetype() {

        List<UnifiedTransaction> unified = new ArrayList<>(List.of(
                saDebit("10.00", LocalDateTime.of(2026, 3, 2, 0, 9))));
        List<ReconTransaction> candidates = candidatesOf(unified);
        SelfTransfer.link(unified, candidates,
                new ReconciliationService.ReconResult());
        assertEquals("EXPENSE", unified.get(0).getType(),
                "unified rows must never be mutated by recon linking");
    }

    @Test
    void outcomeExposesLinkedDebitIdsForMirrorRevoke() {
        UnifiedTransaction debit = saDebit("10.00", LocalDateTime.of(2026, 3, 31, 4, 33));
        List<UnifiedTransaction> unified = new ArrayList<>(List.of(
                debit, tngReceive("10.00", LocalDateTime.of(2026, 3, 31, 8, 15))));
        List<ReconTransaction> candidates = candidatesOf(unified);
        SelfTransfer.Outcome outcome = SelfTransfer.link(unified, candidates,
                new ReconciliationService.ReconResult());
        assertTrue(outcome.mirrors().isEmpty());
        assertEquals(List.of(debit.getId()), outcome.linkedDebitIds());
    }
}
