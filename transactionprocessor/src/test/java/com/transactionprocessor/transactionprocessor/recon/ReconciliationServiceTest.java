package com.transactionprocessor.transactionprocessor.recon;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import com.transactionprocessor.transactionprocessor.repo.ReconTransactionRepository;
import com.transactionprocessor.transactionprocessor.repo.UnifiedTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReconciliationServiceTest {

    @Autowired ReconciliationService recon;
    @Autowired UnifiedTransactionRepository unifiedRepo;
    @Autowired ReconTransactionRepository reconRepo;

    @BeforeEach
    void clean() {
        reconRepo.deleteAll();
        unifiedRepo.deleteAll();
    }

    private static UnifiedTransaction row(LocalDateTime ts, String payee, String amount,
                                          SourceType source, String desc) {
        UnifiedTransaction u = new UnifiedTransaction();
        u.setTimestamp(ts);
        u.setPayee(payee);
        u.setAmount(new BigDecimal(amount));
        u.setAmountCurrency("MYR");
        u.setSource(source);
        u.setType(new BigDecimal(amount).signum() < 0 ? "EXPENSE" : "INCOME");
        u.setDescription(desc);
        u.setCategory("Test");
        return u;
    }

    /** Mirrors real normalizer output for an Alipay/WeChat expense: spend in CNY. */
    private static UnifiedTransaction cnyExpenseRow(LocalDateTime ts, String payee, String cny,
                                                    SourceType source, String desc) {
        UnifiedTransaction u = new UnifiedTransaction();
        u.setTimestamp(ts);
        u.setPayee(payee);
        u.setAmount(new BigDecimal(cny).negate());
        u.setAmountCurrency("CNY");
        u.setOriginalAmount(new BigDecimal(cny));
        u.setOriginalCurrency("CNY");
        u.setSource(source);
        u.setType("EXPENSE");
        u.setDescription(desc);
        u.setCategory("Test");
        return u;
    }

    /** Mirrors real normalizer output for a Maybank CC bill row with a foreign amount. */
    private static UnifiedTransaction maybankCcRow(LocalDateTime ts, String payee, String myr,
                                                   String foreignCny, String desc) {
        UnifiedTransaction u = new UnifiedTransaction();
        u.setTimestamp(ts);
        u.setPayee(payee);
        u.setAmount(new BigDecimal(myr).negate());
        u.setAmountCurrency("MYR");
        u.setOriginalAmount(new BigDecimal(foreignCny));
        u.setOriginalCurrency("CNY");
        u.setSource(SourceType.MAYBANK_CC);
        u.setType("EXPENSE");
        u.setDescription(desc);
        u.setCategory("Test");
        return u;
    }

    @Test
    void maybankUnionCopiesBothSourcesWithoutSpecialHandling() {
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 1, 10, 0), "ALP*GANKAIPIN", "-14.12",
                SourceType.MAYBANK_SA, "SALE DEBIT ALP*GANKAIPIN"));
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 2, 11, 0), "ALIPAY*TAOBAO", "-186.14",
                SourceType.MAYBANK_CC, "Alipay*Taobao Shanghai CN"));
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(2, r.maybankCopied);
        assertEquals(2, r.inserted);
        assertEquals(2, reconRepo.countByAccount(WalletAccount.Maybank));
        // union = faithful copy: amounts and timestamps preserved exactly
        assertTrue(reconRepo.findAll().stream().filter(x -> x.getAccount() == WalletAccount.Maybank).toList().stream()
                .anyMatch(t -> t.getAmount().compareTo(new BigDecimal("-14.12")) == 0
                        && t.getTimestamp().equals(LocalDateTime.of(2026, 9, 1, 10, 0))));
        assertTrue(reconRepo.findAll().stream().filter(x -> x.getAccount() == WalletAccount.Maybank).toList().stream()
                .anyMatch(t -> t.getAmount().compareTo(new BigDecimal("-186.14")) == 0));
    }

    @Test
    void grabExpensesUntouchedPlusTopupIncomeWithExactTimestamp() {
        LocalDateTime topupTs = LocalDateTime.of(2026, 9, 3, 9, 15);
        unifiedRepo.save(row(topupTs, "GRABPAY-EC", "-20.00",
                SourceType.MAYBANK_SA, "TRANSFER GRABPAY-EC * PETALING JAYA"));
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 4, 11, 58), "GRAB TRIP", "-20.10",
                SourceType.GRAB, "Grab ride A-123"));
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(1, r.maybankCopied);
        assertEquals(1, r.grabCopied);
        assertEquals(1, r.grabTopupIncome);
        var grab = reconRepo.findAll().stream().filter(x -> x.getAccount() == WalletAccount.Grab).toList();
        assertEquals(2, grab.size());
        // expense untouched
        assertTrue(grab.stream().anyMatch(t -> t.getAmount().compareTo(new BigDecimal("-20.10")) == 0
                && "EXPENSE".equals(t.getType())));
        // synthesized income mirrors the Maybank row with the EXACT same timestamp
        var income = grab.stream().filter(t -> "INCOME".equals(t.getType())).toList();
        assertEquals(1, income.size());
        assertEquals(topupTs, income.get(0).getTimestamp());
        assertEquals(0, income.get(0).getAmount().compareTo(new BigDecimal("20.00")));
    }

    @Test
    void grabFoodSpendDoesNotCreateTopup() {
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 5, 12, 0), "GRAB FOOD", "-25.50",
                SourceType.MAYBANK_SA, "SALE DEBIT GRAB FOOD KL"));
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(1, r.maybankCopied);
        assertEquals(0, r.grabTopupIncome);
        assertEquals(0, reconRepo.countByAccount(WalletAccount.Grab));
    }

    @Test
    void cashCopiesRestFaithfully() {
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 6, 10, 0), "TNG MERCHANT", "-5.00",
                SourceType.TNG, "TNG spend"));
        // No Maybank rows: both Alipay/WeChat expenses are unmatched → -0.01 MYR placeholders.
        unifiedRepo.save(cnyExpenseRow(LocalDateTime.of(2026, 9, 7, 10, 0), "ALIPAY MERCHANT", "10.00",
                SourceType.ALIPAY, "Alipay spend"));
        unifiedRepo.save(cnyExpenseRow(LocalDateTime.of(2026, 9, 8, 10, 0), "WECHAT MERCHANT", "3.00",
                SourceType.WECHATPAY, "WeChat spend"));
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(3, r.cashCopied);
        assertEquals(0, r.cashMatched);
        assertEquals(2, r.cashPlaceholder);
        assertEquals(3, reconRepo.countByAccount(WalletAccount.Cash));
        // TNG stays faithful; Alipay/WeChat fall back to the placeholder with CNY preserved.
        assertTrue(reconRepo.findAll().stream().filter(x -> x.getAccount() == WalletAccount.Cash).toList().stream()
                .anyMatch(t -> t.getSource() == SourceType.TNG
                        && t.getAmount().compareTo(new BigDecimal("-5.00")) == 0
                        && "MYR".equals(t.getAmountCurrency())));
        assertTrue(reconRepo.findAll().stream().filter(x -> x.getAccount() == WalletAccount.Cash).toList().stream()
                .anyMatch(t -> t.getSource() == SourceType.ALIPAY
                        && t.getAmount().compareTo(new BigDecimal("-0.01")) == 0
                        && "MYR".equals(t.getAmountCurrency())
                        && t.getOriginalAmount().compareTo(new BigDecimal("10.00")) == 0
                        && "CNY".equals(t.getOriginalCurrency())));
    }

    @Test
    void alipayMatchedByExactCnyAmountIsRemovedFromCash() {
        // Scale-insensitive: Maybank states 300.760, Alipay states 300.76.
        unifiedRepo.save(maybankCcRow(LocalDateTime.of(2026, 9, 2, 11, 0), "ALIPAY*TAOBAO", "186.14",
                "300.760", "Alipay*Taobao Shanghai CN | TRANSACTED CNY 300.760"));
        unifiedRepo.save(cnyExpenseRow(LocalDateTime.of(2026, 9, 4, 15, 51), "ALIPAY MERCHANT", "300.76",
                SourceType.ALIPAY, "Alipay - merchant"));
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(1, r.maybankCopied);
        assertEquals(1, r.cashMatched);
        assertEquals(0, r.cashPlaceholder);
        assertEquals(1, reconRepo.countByAccount(WalletAccount.Maybank));
        assertEquals(0, reconRepo.countByAccount(WalletAccount.Cash));
        assertEquals(1, r.inserted);
    }

    @Test
    void wechatMatchedByExactCnyAmountIsRemovedFromCash() {
        unifiedRepo.save(maybankCcRow(LocalDateTime.of(2026, 9, 2, 11, 0), "WEIXIN*GUANGZHOU", "40.12",
                "65.00", "WEIXIN*Guangzhou | TRANSACTED CNY 65.00"));
        unifiedRepo.save(cnyExpenseRow(LocalDateTime.of(2026, 9, 3, 12, 0), "WECHAT MERCHANT", "65.00",
                SourceType.WECHATPAY, "WeChat Pay - merchant"));
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(1, r.cashMatched);
        assertEquals(0, reconRepo.countByAccount(WalletAccount.Cash));
    }

    @Test
    void alipayUnmatchedKeptAs001MyrPlaceholder() {
        unifiedRepo.save(maybankCcRow(LocalDateTime.of(2026, 9, 2, 11, 0), "ALIPAY*TAOBAO", "186.14",
                "300.76", "Alipay*Taobao Shanghai CN"));
        unifiedRepo.save(cnyExpenseRow(LocalDateTime.of(2026, 9, 5, 10, 0), "ALIPAY MERCHANT", "138.00",
                SourceType.ALIPAY, "Alipay - other merchant"));
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(0, r.cashMatched);
        assertEquals(1, r.cashPlaceholder);
        var cash = reconRepo.findAll().stream().filter(x -> x.getAccount() == WalletAccount.Cash).toList();
        assertEquals(1, cash.size());
        assertEquals(0, cash.get(0).getAmount().compareTo(new BigDecimal("-0.01")));
        assertEquals("MYR", cash.get(0).getAmountCurrency());
        assertEquals("EXPENSE", cash.get(0).getType());
        assertEquals(0, cash.get(0).getOriginalAmount().compareTo(new BigDecimal("138.00")));
        assertEquals("CNY", cash.get(0).getOriginalCurrency());
    }

    @Test
    void cnyMatchIsExactToTheCent() {
        unifiedRepo.save(maybankCcRow(LocalDateTime.of(2026, 9, 2, 11, 0), "ALIPAY*TAOBAO", "186.14",
                "300.76", "Alipay*Taobao Shanghai CN"));
        unifiedRepo.save(cnyExpenseRow(LocalDateTime.of(2026, 9, 4, 15, 51), "ALIPAY MERCHANT", "300.77",
                SourceType.ALIPAY, "Alipay - off by a cent"));
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(0, r.cashMatched);
        assertEquals(1, r.cashPlaceholder);
        assertEquals(1, reconRepo.countByAccount(WalletAccount.Cash));
    }

    @Test
    void tngCopiedFaithfullyRegardlessOfMaybankCnyPool() {
        // Rule is scoped to Alipay/WeChat only: TNG is never matched or placeholdered.
        unifiedRepo.save(maybankCcRow(LocalDateTime.of(2026, 9, 2, 11, 0), "ALIPAY*TAOBAO", "186.14",
                "300.76", "Alipay*Taobao Shanghai CN"));
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 6, 10, 0), "TNG MERCHANT", "-5.00",
                SourceType.TNG, "TNG spend"));
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(0, r.cashMatched);
        assertEquals(0, r.cashPlaceholder);
        var cash = reconRepo.findAll().stream().filter(x -> x.getAccount() == WalletAccount.Cash).toList();
        assertEquals(1, cash.size());
        assertEquals(0, cash.get(0).getAmount().compareTo(new BigDecimal("-5.00")));
        assertEquals("MYR", cash.get(0).getAmountCurrency());
    }

    @Test
    void alipayIncomeKeptFaithfullyNotMatchedOrPlaceholdered() {
        // Incomes are real money in, not Maybank-settled spends: never dropped or zeroed.
        UnifiedTransaction income = cnyExpenseRow(LocalDateTime.of(2026, 9, 7, 10, 0), "ALIPAY MERCHANT",
                "300.76", SourceType.ALIPAY, "Alipay income");
        income.setAmount(new BigDecimal("300.76"));
        income.setType("INCOME");
        unifiedRepo.save(maybankCcRow(LocalDateTime.of(2026, 9, 2, 11, 0), "ALIPAY*TAOBAO", "186.14",
                "300.76", "Alipay*Taobao Shanghai CN"));
        unifiedRepo.save(income);
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(0, r.cashMatched);
        assertEquals(0, r.cashPlaceholder);
        var cash = reconRepo.findAll().stream().filter(x -> x.getAccount() == WalletAccount.Cash).toList();
        assertEquals(1, cash.size());
        assertEquals(0, cash.get(0).getAmount().compareTo(new BigDecimal("300.76")));
        assertEquals("CNY", cash.get(0).getAmountCurrency());
    }

    @Test
    void retryIsIdempotentSecondRunInsertsNothing() {
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 1, 10, 0), "ALP*GANKAIPIN", "-14.12",
                SourceType.MAYBANK_SA, "SALE DEBIT"));
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 3, 9, 15), "GRABPAY-EC", "-20.00",
                SourceType.MAYBANK_SA, "TRANSFER GRABPAY-EC"));
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 4, 11, 58), "GRAB TRIP", "-20.10",
                SourceType.GRAB, "Grab ride"));
        ReconciliationService.ReconResult first = recon.run();
        assertTrue(first.inserted > 0);
        long total = reconRepo.count();
        ReconciliationService.ReconResult second = recon.run();
        assertEquals(0, second.inserted);
        assertEquals(total, reconRepo.count());
    }

    @Test
    void runHasNoDbTransaction() throws Exception {
        var m = ReconciliationService.class.getMethod("run");
        assertNull(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class),
                "recon run() must not run inside a DB transaction (idempotency via unique key instead)");
    }

    @Test
    void perfectMaybankPairProducesNoOutput() {
        unifiedRepo.save(row(LocalDateTime.of(2026, 2, 7, 0, 24), "PSS BANDAR PUTRA 3 *", "-200",
                SourceType.MAYBANK_SA, "PRE-AUTH DEBIT PSS BANDAR PUTRA 3 *"));
        unifiedRepo.save(row(LocalDateTime.of(2026, 2, 7, 0, 27), "PSS BANDAR PUTRA 3 *", "200",
                SourceType.MAYBANK_SA, "PRE-AUTH REFUND PSS BANDAR PUTRA 3 *"));
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(2, r.maybankNetted);
        assertEquals(0, r.maybankCopied);
        assertEquals(0, r.inserted);
        assertEquals(0, reconRepo.count());
        // Unified rows are untouched: netting is output-side only.
        assertEquals(2, unifiedRepo.count());
    }

    @Test
    void nettedTopupLegProducesNoGrabMirror() {
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 3, 9, 15), "GRABPAY-EC", "-20.00",
                SourceType.MAYBANK_SA, "TRANSFER GRABPAY-EC * PETALING JAYA"));
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 3, 9, 18), "GRABPAY-EC", "20.00",
                SourceType.MAYBANK_SA, "REFUND GRABPAY-EC * PETALING JAYA"));
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(2, r.maybankNetted);
        assertEquals(0, r.grabTopupIncome);
        assertEquals(0, reconRepo.countByAccount(WalletAccount.Grab));
    }

        @Test
    void dcdChainKeepsFinalSettlementInOutput() {

        unifiedRepo.save(row(LocalDateTime.of(2026, 3, 11, 0, 0), "WEIXIN*BEIJING CITY", "-49.54",
                SourceType.MAYBANK_SA, "PRE-AUTH DEBIT WEIXIN*Beijing City"));
        unifiedRepo.save(row(LocalDateTime.of(2026, 3, 14, 0, 3), "WEIXIN*BEIJING CITY", "49.54",
                SourceType.MAYBANK_SA, "PRE-AUTH REFUND WEIXIN*Beijing City"));
        unifiedRepo.save(row(LocalDateTime.of(2026, 3, 14, 0, 6), "WEIXIN*BEIJING CITY", "-49.06",
                SourceType.MAYBANK_SA, "SALE DEBIT WEIXIN*Beijing City"));
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(2, r.maybankNetted);
        assertEquals(1, r.maybankCopied);
        var maybank = reconRepo.findAll().stream().filter(x -> x.getAccount() == WalletAccount.Maybank).toList();
        assertEquals(1, maybank.size());
        assertEquals(0, maybank.get(0).getAmount().compareTo(new BigDecimal("-49.06")));
    }

    @Test
    void cardPaymentTransferLegsProduceNoOutput() {
        // June case: SA card-payment debit + CC payment credit, different payees.
        UnifiedTransaction sa = row(LocalDateTime.of(2026, 6, 12, 1, 18), "MASTERCARD ISLAMIC *", "-719.93",
                SourceType.MAYBANK_SA, "PYMT FROM A/C | MASTERCARD ISLAMIC * / XXXX-XXXX-XXXX-0000 / 00 | 719.93-");
        unifiedRepo.save(sa);
        UnifiedTransaction cc = row(LocalDateTime.of(2026, 6, 12, 0, 6), "PYMT@MAYBANK2U.COM", "719.93",
                SourceType.MAYBANK_CC, "PYMT@MAYBANK2U.COM");
        cc.setReference("CC0000|12/06|12/06");
        cc.setType("TRANSFER");
        unifiedRepo.save(cc);
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(2, r.maybankNetted);
        assertEquals(0, r.maybankCopied);
        assertEquals(0, r.inserted);
        assertEquals(0, reconRepo.count());
        // Unified rows are untouched: netting is output-side only.
        assertEquals(2, unifiedRepo.count());
    }

        @Test
    void loneCardPaymentLegIsStillCopied() {

        // August case: CC credit with no SA counterpart — kept, not netted.
        UnifiedTransaction cc = row(LocalDateTime.of(2026, 8, 13, 0, 6), "PYMT@MAYBANK2U.COM", "288.58",
                SourceType.MAYBANK_CC, "PYMT@MAYBANK2U.COM");
        cc.setType("TRANSFER");
        unifiedRepo.save(cc);
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(0, r.maybankNetted);
        assertEquals(1, r.maybankCopied);
        assertEquals(1, reconRepo.countByAccount(WalletAccount.Maybank));
    }

    @Test
    void selfTransferLegsAreRetypedToTransfer() {
        unifiedRepo.save(row(LocalDateTime.of(2026, 3, 31, 4, 33), "CHANGYINCHENG *", "-76.89",
                SourceType.MAYBANK_SA, "IBK FUND TFR FR A/C | CHANGYINCHENG * / Top up"));
        UnifiedTransaction receive = row(LocalDateTime.of(2026, 3, 31, 8, 15), "CHANG YIN CHENG", "76.89",
                SourceType.TNG, "DUITNOW_RECEI 20260331111 CHANG YIN CHENG 2026033110110000010000TNGOW3");
        receive.setType("INCOME");
        unifiedRepo.save(receive);
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(1, r.selfTransferPairs);
        assertEquals(0, r.selfTransferMirrors);
        var maybank = reconRepo.findAll().stream().filter(x -> x.getAccount() == WalletAccount.Maybank).toList();
        assertEquals(1, maybank.size());
        assertEquals("TRANSFER", maybank.get(0).getType());
        assertTrue(maybank.get(0).getAmount().signum() < 0);
        var cash = reconRepo.findAll().stream().filter(x -> x.getAccount() == WalletAccount.Cash).toList();
        assertEquals(1, cash.size());
        assertEquals("TRANSFER", cash.get(0).getType());
        assertTrue(cash.get(0).getAmount().signum() > 0);
    }

    @Test
    void orphanSelfDebitGainsCashMirror() {
        unifiedRepo.save(row(LocalDateTime.of(2026, 3, 2, 0, 9), "CHANG YIN CHENG *", "-10.00",
                SourceType.MAYBANK_SA, "IBK FUND TFR FR A/C | CHANG YIN CHENG * / Top up"));
        ReconciliationService.ReconResult r = recon.run();
        assertEquals(0, r.selfTransferPairs);
        assertEquals(1, r.selfTransferMirrors);
        assertEquals(1, r.maybankCopied);
        var cash = reconRepo.findAll().stream().filter(x -> x.getAccount() == WalletAccount.Cash).toList();
        assertEquals(1, cash.size());
        assertEquals("TRANSFER", cash.get(0).getType());
        assertEquals(0, cash.get(0).getAmount().compareTo(new BigDecimal("10.00")));
        assertEquals(LocalDateTime.of(2026, 3, 2, 0, 9), cash.get(0).getTimestamp());
    }

    @Test
    void lateCounterpartRevokesStaleMirror() {
        // Run 1: orphan SA debit → Cash mirror inserted.
        unifiedRepo.save(row(LocalDateTime.of(2026, 3, 2, 0, 9), "CHANG YIN CHENG *", "-10.00",
                SourceType.MAYBANK_SA, "IBK FUND TFR FR A/C | CHANG YIN CHENG * / Top up"));
        ReconciliationService.ReconResult first = recon.run();
        assertEquals(1, first.selfTransferMirrors);
        assertEquals(1, reconRepo.countByAccount(WalletAccount.Cash));

        // Run 2: TNG counterpart arrives → linked, mirror revoked, real row kept.
        UnifiedTransaction receive = row(LocalDateTime.of(2026, 3, 2, 8, 0), "CHANG YIN CHENG", "10.00",
                SourceType.TNG, "DUITNOW_RECEI 20260302111 CHANG YIN CHENG 2026030210110000010000TNGOW3");
        receive.setType("INCOME");
        unifiedRepo.save(receive);
        ReconciliationService.ReconResult second = recon.run();
        assertEquals(1, second.selfTransferPairs);
        assertEquals(1, second.selfTransferRevoked);
        var cash = reconRepo.findAll().stream().filter(x -> x.getAccount() == WalletAccount.Cash).toList();
        assertEquals(1, cash.size(), "mirror must be replaced by the real counterpart, not doubled");
        assertEquals("TRANSFER", cash.get(0).getType());
        assertEquals(0, cash.get(0).getAmount().compareTo(new BigDecimal("10.00")));
    }
}
