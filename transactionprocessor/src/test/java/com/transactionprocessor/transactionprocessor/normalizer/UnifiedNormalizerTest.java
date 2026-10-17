package com.transactionprocessor.transactionprocessor.normalizer;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import com.transactionprocessor.transactionprocessor.parser.RawTransactionData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain unit tests: parser-output DTO → canonical unified transaction.
 * No Spring context, no database.
 */
class UnifiedNormalizerTest {

    private final NormalizerService normalizer =
            new NormalizerService(new CategoryInferenceService(), new ForeignCurrencyFormatter());

    private static RawTransactionData data(String date, String desc, String amount, String ccy,
                                          String sourceId, Map<String, Object> payload) {
        RawTransactionData d = new RawTransactionData();
        d.setRawDate(date);
        d.setRawDescription(desc);
        d.setRawAmount(amount);
        d.setRawCurrency(ccy);
        d.setSourceTransactionId(sourceId);
        d.setPayload(new LinkedHashMap<>(payload));
        return d;
    }

    @Test
    void alipayExpenseKeepsRealTimestampAndOriginalAmount() {
        Map<String, Object> p = Map.of(
                "交易时间", "2026-09-10 10:00:00",
                "收/支", "支出",
                "金额", "2.00",
                "交易对方", " Metro ",
                "商品说明", "coffee",
                "交易分类", "餐饮",
                "收/付款方式", "Mastercard(0000)",
                "交易状态", "交易成功",
                "交易订单号", "ORDER1");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("2026-09-10 10:00:00", "Metro coffee", "2.00", "CNY", "ORDER1", p),
                SourceType.ALIPAY);
        assertEquals(java.time.LocalDateTime.of(2026, 9, 10, 10, 0, 0), u.getTimestamp());
        assertFalse(u.isDateOnly());
        assertEquals("METRO", u.getPayee());
        // MYR unresolved pre-reconciliation → canonical amount is the original.
        // Spends are negative, like every other source.
        assertEquals(0, new BigDecimal("-2.00").compareTo(u.getAmount()));
        assertEquals("CNY", u.getAmountCurrency());
        assertEquals(SourceType.ALIPAY, u.getSource());
        assertEquals("EXPENSE", u.getType());
        assertEquals(WalletAccount.Cash, u.getAccount());
    }

    @Test
    void tngSpendIsNegativeExpenseAndReceiptIsPositiveIncome() {
        Map<String, Object> spend = Map.of("date", "11/6/2026", "type", "Payment",
                "description", "Metro");
        UnifiedTransaction out = normalizer.normalizeToUnified(
                data("11/6/2026", "Metro", "-6.07", "MYR", "x", spend), SourceType.TNG);
        assertEquals("EXPENSE", out.getType());
        assertEquals(0, new BigDecimal("-6.07").compareTo(out.getAmount()));
        assertEquals("MYR", out.getAmountCurrency());
        assertEquals(WalletAccount.Cash, out.getAccount());

        Map<String, Object> in = Map.of("date", "11/6/2026", "type", "Receive from Wallet",
                "description", "Friend");
        UnifiedTransaction inc = normalizer.normalizeToUnified(
                data("11/6/2026", "Friend", "50.00", "MYR", "y", in), SourceType.TNG);
        assertEquals("INCOME", inc.getType());
        assertEquals(0, new BigDecimal("50.00").compareTo(inc.getAmount()));
    }

    @Test
    void tngDateOnlyRowIsFlaggedForSyntheticTime() {
        Map<String, Object> p = Map.of("date", "11/6/2026", "type", "DuitNow",
                "description", "Metro A → Metro B");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("11/6/2026", "Metro A → Metro B", "-2.00", "MYR", "x", p), SourceType.TNG);
        assertEquals(java.time.LocalDate.of(2026, 6, 11), u.getTimestamp().toLocalDate());
        assertTrue(u.isDateOnly(), "date-only rows must be flagged for central synthesis");
        assertEquals("METRO A TO METRO B", u.getPayee());
        assertEquals(0, new BigDecimal("-2.00").compareTo(u.getAmount()));
        assertEquals("MYR", u.getAmountCurrency());
        assertEquals(WalletAccount.Cash, u.getAccount());
    }

    @Test
    void maybankSavingsIsDateOnly() {
        Map<String, Object> p = Map.of("entryType", "SALE DEBIT");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("2026-09-10", "SALE DEBIT | METRO | 2.00-", "-2.00", "MYR", null, p),
                SourceType.MAYBANK_SA);
        assertTrue(u.isDateOnly());
        assertEquals(java.time.LocalDate.of(2026, 9, 10), u.getTimestamp().toLocalDate());
        assertEquals("METRO", u.getPayee());
        assertEquals(WalletAccount.Maybank, u.getAccount());
    }

    @Test
    void maybankCreditCardIsDateOnly() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("description", "ALP*GANKAIPIN / Shanghai CN");
        p.put("postingDate", "10/09");
        p.put("transactionDate", "09/09");
        p.put("credit", false);
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("2026-09-10", "ALP*GANKAIPIN / Shanghai CN", "100.00", "MYR", null, p),
                SourceType.MAYBANK_CC);
        assertTrue(u.isDateOnly());
        assertEquals("ALP*GANKAIPIN", u.getPayee());
        assertEquals(0, new BigDecimal("-100.00").compareTo(u.getAmount()));
        assertEquals(WalletAccount.Maybank, u.getAccount());
    }

    @Test
    void grabKeepsRealTimestamp() {
        Map<String, Object> p = Map.of(
                "dateTime", "08 Sep 2026, 11:58AM",
                "currency", "MYR",
                "amount", "20.10",
                "route", "Bach Viet - Bandar Indahpura",
                "bookingCode", "A-9QDD");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("08 Sep 2026, 11:58AM", "Grab A-9QDD", "20.10", "MYR", "A-9QDD", p),
                SourceType.GRAB);
        assertEquals(java.time.LocalDateTime.of(2026, 9, 8, 11, 58), u.getTimestamp());
        assertFalse(u.isDateOnly());
        assertEquals("BACH VIET - BANDAR INDAHPURA", u.getPayee());
        assertEquals(WalletAccount.Grab, u.getAccount());
    }

    @Test
    void wechatExpenseKeepsRealTimestampAndOriginalAmount() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("交易时间", "2026-09-10 10:05:00");
        p.put("收/支", "支出");
        p.put("金额(元)", "8.00");
        p.put("交易对方", "Coffee Shop");
        p.put("商品", "/");
        p.put("支付方式", "零钱");
        p.put("当前状态", "已完成");
        p.put("交易类型", "商户消费");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("2026-09-10 10:05:00", "Coffee Shop", "8.00", "CNY", "WX1", p),
                SourceType.WECHATPAY);
        assertEquals(java.time.LocalDateTime.of(2026, 9, 10, 10, 5, 0), u.getTimestamp());
        assertFalse(u.isDateOnly());
        assertEquals("COFFEE SHOP", u.getPayee());
        assertEquals(0, new BigDecimal("-8.00").compareTo(u.getAmount()));
        assertEquals("CNY", u.getAmountCurrency());
        assertEquals(WalletAccount.Cash, u.getAccount());
    }

    @Test
    void alipayIncomeKeepsCnyLabel() {
        Map<String, Object> p = Map.of(
                "交易时间", "2026-09-06 12:11:28",
                "收/支", "收入",
                "金额", "7.00",
                "交易对方", "ANO",
                "商品说明", "refund",
                "交易分类", "",
                "收/付款方式", "Cash",
                "交易状态", "交易成功",
                "交易订单号", "INC1");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("2026-09-06 12:11:28", "ANO refund", "7.00", "CNY", "INC1", p),
                SourceType.ALIPAY);
        assertEquals("INCOME", u.getType());
        assertEquals(0, new BigDecimal("7.00").compareTo(u.getAmount()));
        assertEquals("CNY", u.getAmountCurrency(),
                "foreign income must not be mislabeled as MYR");
        assertEquals(WalletAccount.Cash, u.getAccount());
    }

    @Test
    void wechatIncomeKeepsCnyLabel() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("交易时间", "2026-02-15 18:38:48");
        p.put("收/支", "收入");
        p.put("金额(元)", "0.89");
        p.put("交易对方", "yw.");
        p.put("商品", "/");
        p.put("支付方式", "零钱");
        p.put("当前状态", "已完成");
        p.put("交易类型", "微信红包");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("2026-02-15 18:38:48", "yw.", "0.89", "CNY", "WXINC", p),
                SourceType.WECHATPAY);
        assertEquals("INCOME", u.getType());
        assertEquals(0, new BigDecimal("0.89").compareTo(u.getAmount()));
        assertEquals("CNY", u.getAmountCurrency());
        assertEquals(WalletAccount.Cash, u.getAccount());
    }

    @Test
    void wechatRedPacketKeepsCnyLabel() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("交易时间", "2026-02-17 00:39:14");
        p.put("收/支", "支出");
        p.put("金额(元)", "0.50");
        p.put("交易对方", "发出群红包");
        p.put("商品", "/");
        p.put("支付方式", "零钱");
        p.put("当前状态", "已完成");
        p.put("交易类型", "群红包");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("2026-02-17 00:39:14", "发出群红包", "0.50", "CNY", "WXRP", p),
                SourceType.WECHATPAY);
        assertEquals("EXPENSE", u.getType());
        assertEquals(0, new BigDecimal("-0.50").compareTo(u.getAmount()));
        assertEquals("CNY", u.getAmountCurrency());
        assertEquals(WalletAccount.Cash, u.getAccount());
    }

    @Test
    void alipayTransferZeroKeepsCnyLabel() {
        Map<String, Object> p = Map.of(
                "交易时间", "2026-07-29 16:53:09",
                "收/支", "支出",
                "金额", "77.50",
                "交易对方", "LUODEDAO77",
                "商品说明", "refund",
                "交易分类", "",
                "收/付款方式", "Cash",
                "交易状态", "退款成功",
                "交易订单号", "REF1");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("2026-07-29 16:53:09", "LUODEDAO77 refund", "77.50", "CNY", "REF1", p),
                SourceType.ALIPAY);
        assertEquals("TRANSFER", u.getType());
        assertEquals(0, BigDecimal.ZERO.compareTo(u.getAmount()));
        assertEquals("CNY", u.getAmountCurrency(),
                "zeroed transfer must carry the transaction currency, not MYR");
        assertEquals(0, new BigDecimal("77.50").compareTo(u.getOriginalAmount()));
        assertEquals(WalletAccount.Cash, u.getAccount());
    }

    @Test
    void grabForeignExpenseIsNegativeInOriginalCurrency() {
        Map<String, Object> p = Map.of(
                "dateTime", "19 Jun 2026, 02:00PM",
                "currency", "THB",
                "amount", "74.00",
                "route", "Gravy Noodle - Rat Na Yot",
                "bookingCode", "A-9GWXL44GWXSTAV7");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("19 Jun 2026, 02:00PM", "Grab A-9GWXL44GWXSTAV7", "74.00", "THB",
                        "A-9GWXL44GWXSTAV7", p),
                SourceType.GRAB);
        // Spends are negative like their MYR siblings; the truthful positive
        // original is preserved for reference.
        assertEquals(0, new BigDecimal("-74.00").compareTo(u.getAmount()));
        assertEquals("THB", u.getAmountCurrency());
        assertEquals(0, new BigDecimal("74.00").compareTo(u.getOriginalAmount()));
        assertEquals("THB", u.getOriginalCurrency());
        assertEquals("EXPENSE", u.getType());
        assertEquals(WalletAccount.Grab, u.getAccount());
    }

    @Test
    void grabMyrExpenseIsNegative() {
        Map<String, Object> p = Map.of(
                "dateTime", "08 Sep 2026, 11:58AM",
                "currency", "MYR",
                "amount", "20.10",
                "route", "Bach Viet - Bandar Indahpura",
                "bookingCode", "A-9QDD");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("08 Sep 2026, 11:58AM", "Grab A-9QDD", "20.10", "MYR", "A-9QDD", p),
                SourceType.GRAB);
        assertEquals(0, new BigDecimal("-20.10").compareTo(u.getAmount()));
        assertEquals("MYR", u.getAmountCurrency());
        assertEquals(WalletAccount.Grab, u.getAccount());
    }

    @Test
    void tngMerchantExtraction() {
        // Shapes copied from real statement output (references abbreviated).
        assertEquals("cos", NormalizerService.extractTngMerchant(
                "Payment 20260611101 cos 202606112112128001001714478352 10000010000 35119 TNGOW3MY1 71443103929 983"));
        assertEquals("Bean's Factory", NormalizerService.extractTngMerchant(
                "Transfer to Wallet 20260611111 Bean's Factory 2026061110110000010000TNGOW3 21700010100 MY171443104272565 1565"));
        assertEquals("GAN KAI PIN", NormalizerService.extractTngMerchant(
                "Receive from Wallet20260614111 GAN KAI PIN 2026061410110000010000TNGOW3 21700010300 MY171659107818861 6061"));
        assertEquals("PAYMENT", NormalizerService.canonicalPayee(NormalizerService.extractTngMerchant(
                "Payment 20260613101 202606132112128001001714417355 10000010000 GWP0026061302209006614608062 51372 TNGOW3MY1204 473")));
        assertEquals("CHANG YIN CHENG", NormalizerService.extractTngMerchant(
                "DUITNOW_RECEI 20260625111 CHANG YIN CHENG 2026062510110000010000TNGOW3 VEFROM 21700010100 MY171443109135372 5024"));
        assertEquals("In-store Up to RM5 Cashback Blind", NormalizerService.extractTngMerchant(
                "Cashback 20260718211 In-store Up to RM5 Cashback Blind 2026071810110000010000TNGOW3 22590230017 Box Voucher MY171443118748406 49"));
        assertEquals("TRANSFER TO WALLET", NormalizerService.canonicalPayee(NormalizerService.extractTngMerchant(
                "Transfer to Wallet 20260720111  & 2026072010110000010000TNGOW3 21700010100 MY171443119502796 3873")));
        assertEquals("Alisha Danila Enterprise", NormalizerService.extractTngMerchant(
                "DuitNow QR 20260718101 Alisha Danila Enterprise 202607182112128001001714498411 10000010000 46990 TNGOW3MY1 916"));
        assertEquals("PINDUODUO", NormalizerService.extractTngMerchant(
                "Refund 20260615211 PINDUODUO 2026061510110000040000TNGOW3 21280130017 MY171443128363735 14"));
        assertEquals("C. Y. CHENG", NormalizerService.extractTngMerchant("C. Y. CHENG [CNY 7.00]"));
        assertEquals("7-ELEVEN MALAYSIA SDN BHD", NormalizerService.extractTngMerchant(
                "Payment 20260718101 7-ELEVEN MALAYSIA SDN BHD 202607182112128001001714469412 10000010000 13144 TNGOW3MY1 288"));
        assertEquals("KL 2D1N trip", NormalizerService.extractTngMerchant(
                "Transfer to Wallet 20260722111 KL 2D1N trip 2026072210110000010000TNGOW3 21700010100 MY171443120102047 1501"));
        assertEquals("Metro A to Metro B", NormalizerService.extractTngMerchant(
                "Payment 20260611101 Metro A → Metro B 10000010000"));
        assertEquals("", NormalizerService.extractTngMerchant(null));
    }

    @Test
    void maybankCardPaymentIsPositiveTransfer() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("description", "PYMT@MAYBANK2U.COM");
        p.put("postingDate", "20/03");
        p.put("transactionDate", "19/03");
        p.put("credit", true);
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("2026-03-20", "PYMT@MAYBANK2U.COM | RM 1147.13CR", "-1147.13", "MYR", null, p),
                SourceType.MAYBANK_CC);
        assertEquals("TRANSFER", u.getType());
        // Money INTO the card account nets against the negative spends.
        assertEquals(0, new BigDecimal("1147.13").compareTo(u.getAmount()));
        assertEquals("MYR", u.getAmountCurrency());
        assertEquals(WalletAccount.Maybank, u.getAccount());
    }

    @Test
    void canonicalPayeeIsStable() {
        assertEquals("METRO", NormalizerService.canonicalPayee("Metro"));
        assertEquals("METRO", NormalizerService.canonicalPayee(" Metro "));
        assertEquals("METRO", NormalizerService.canonicalPayee("METRO"));
        assertEquals("METRO A", NormalizerService.canonicalPayee("  Metro\n\tA  "));
        assertEquals("", NormalizerService.canonicalPayee(null));
    }

    @Test
    void dedupAmountIsScaleInsensitive() {
        assertEquals(NormalizerService.dedupAmountPlain(new BigDecimal("2")),
                NormalizerService.dedupAmountPlain(new BigDecimal("2.00")));
        assertEquals(NormalizerService.dedupAmountPlain(new BigDecimal("2.0")),
                NormalizerService.dedupAmountPlain(new BigDecimal("2.00")));
        assertEquals("2.1", NormalizerService.dedupAmountPlain(new BigDecimal("2.10")));
        assertEquals("-2", NormalizerService.dedupAmountPlain(new BigDecimal("-2.00")));
    }

    @Test
    void maybankSaAccountSlashTypeDoesNotBecomeCMerchant() {
        // June-2026 case: "TRANSFER FROM A/C | NICHOLAS YEO WEI MI* / ..." was
        // labeled payee "C" (the "/C" fragment survived the split), hiding a
        // real RM20 transfer from merchant search.
        Map<String, Object> p = Map.of("entryType", "TRANSFER FROM A/C");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("2026-06-06",
                        "TRANSFER FROM A/C | NICHOLAS YEO WEI MI* / 488094840Q / MAE QR | 20.00-",
                        "-20.00", "MYR", null, p),
                SourceType.MAYBANK_SA);
        assertEquals("NICHOLAS YEO WEI MI*", u.getPayee());
    }

    @Test
    void maybankSaIbkFundMerchantSurvives() {
        Map<String, Object> p = Map.of("entryType", "IBK FUND TFR FR A/C");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("2026-06-01",
                        "IBK FUND TFR FR A/C | TEALIVE * / QR82584076 / DUITNOW QR | 9.80-",
                        "-9.80", "MYR", null, p),
                SourceType.MAYBANK_SA);
        assertEquals("TEALIVE *", u.getPayee());
    }

    @Test
    void maybankSaMerchantlessRowFallsBackToEntryType() {
        Map<String, Object> p = Map.of("entryType", "CASH WITHDRAWAL");
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("2026-06-20", "CASH WITHDRAWAL | 107.37-", "-107.37", "MYR", null, p),
                SourceType.MAYBANK_SA);
        assertEquals("CASH WITHDRAWAL", u.getPayee());
    }

    @Test
    void maybankCcMerchantExtractionUnchanged() {
        // The SA "A/C" fix must not leak into the CC shape ("merchant | RM amt").
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("description", "ALP*GANKAIPIN / Shanghai CN");
        p.put("postingDate", "10/09");
        p.put("transactionDate", "09/09");
        p.put("credit", false);
        UnifiedTransaction u = normalizer.normalizeToUnified(
                data("2026-09-10", "ALP*GANKAIPIN / Shanghai CN | RM 100.00", "100.00", "MYR", null, p),
                SourceType.MAYBANK_CC);
        assertEquals("ALP*GANKAIPIN", u.getPayee());
    }
}
