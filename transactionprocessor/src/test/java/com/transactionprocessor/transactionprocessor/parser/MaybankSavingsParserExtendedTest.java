package com.transactionprocessor.transactionprocessor.parser;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaybankSavingsParserExtendedTest {

    private MaybankSavingsParser parser;

    @BeforeEach
    void setUp() {
        AppConfig c = new AppConfig();
        parser = new MaybankSavingsParser(new PdfTextExtractor(), c);
    }

    // --- supports ---

    @Test
    void supportsSavingsFilenames() {
        var d = new StatementFileDescriptor(java.nio.file.Paths.get("/s/M2U SA 000000 Apr 2026.pdf"),
                "M2U SA 000000 Apr 2026.pdf", 1, "PDF");
        assertTrue(parser.supports(d));
    }

    @Test
    void supportsAlternativeSavingsFilenames() {
        var d = new StatementFileDescriptor(java.nio.file.Paths.get("/s/Maybank Savings Account.pdf"),
                "Maybank Savings Account.pdf", 1, "PDF");
        assertTrue(parser.supports(d));
    }

    @Test
    void doesNotSupportCreditCardPdf() {
        var d = new StatementFileDescriptor(java.nio.file.Paths.get("/s/M2U Bill 0000 Aug 2026.pdf"),
                "M2U Bill 0000 Aug 2026.pdf", 1, "PDF");
        assertFalse(parser.supports(d));
    }

    @Test
    void doesNotSupportXlsx() {
        var d = new StatementFileDescriptor(java.nio.file.Paths.get("/s/savings.xlsx"),
                "savings.xlsx", 1, "XLSX");
        assertFalse(parser.supports(d));
    }

    // --- version ---

    @Test
    void versionIsMaybankSaV1() {
        assertEquals("maybank-sa-v1", parser.version());
    }

    // --- parseText ---

    @Test
    void parsesPreAuthChainRows() {
        String text = """
                URUSNIAGA AKAUN/ACCOUNT TRANSACTIONS
                ENTRY DATE TRANSACTION DESCRIPTION TRANSACTION AMOUNT STATEMENT BALANCE
                BEGINNING BALANCE
                31,565.89
                01/04/26
                PRE-AUTH REFUND
                14.26+
                31,548.54
                   ALP*GANKAIPIN
                   Shanghai     CN
                   PRE-AUTH REFUND
                01/04/26
                SALE DEBIT
                14.12-
                31,534.42
                   ALP*GANKAIPIN
                   Shanghai     CN
                   SALE DEBIT
                """;
        var rows = parser.parseText(text);
        assertEquals(2, rows.size());
        assertEquals("14.26", rows.get(0).getRawAmount().replace("-", ""));
        assertTrue(rows.get(0).getRawDescription().contains("PRE-AUTH REFUND"));
        assertTrue(rows.get(1).getRawDescription().contains("ALP*GANKAIPIN"));
    }

    @Test
    void parsesMultipleTransactions() {
        String text = """
                URUSNIAGA AKAUN/ACCOUNT TRANSACTIONS
                ENTRY DATE TRANSACTION DESCRIPTION TRANSACTION AMOUNT STATEMENT BALANCE
                BEGINNING BALANCE
                31,565.89
                01/04/26
                SALE DEBIT
                14.12-
                31,551.77
                   ALP*GANKAIPIN
                   Shanghai     CN
                   SALE DEBIT
                02/04/26
                SALE DEBIT
                25.50-
                31,526.27
                   GRAB FOOD
                   KL
                   SALE DEBIT
                03/04/26
                CREDIT
                100.00+
                31,626.27
                   TRANSFER FROM SAVINGS
                   """;
        var rows = parser.parseText(text);
        assertEquals(3, rows.size());
    }

    @Test
    void creditTransactionParsed() {
        String text = """
                URUSNIAGA AKAUN/ACCOUNT TRANSACTIONS
                ENTRY DATE TRANSACTION DESCRIPTION TRANSACTION AMOUNT STATEMENT BALANCE
                BEGINNING BALANCE
                31,565.89
                01/04/26
                CREDIT
                100.00+
                31,665.89
                   TRANSFER FROM SAVINGS
                   """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("100.00", rows.get(0).getRawAmount().replace("+", ""));
        assertTrue(rows.get(0).getRawDescription().contains("CREDIT"));
    }

    @Test
    void debitTransactionParsed() {
        String text = """
                URUSNIAGA AKAUN/ACCOUNT TRANSACTIONS
                ENTRY DATE TRANSACTION DESCRIPTION TRANSACTION AMOUNT STATEMENT BALANCE
                BEGINNING BALANCE
                31,565.89
                01/04/26
                SALE DEBIT
                14.12-
                31,551.77
                   ALP*GANKAIPIN
                   Shanghai     CN
                   SALE DEBIT
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("-14.12", rows.get(0).getRawAmount());
    }

    @Test
    void emptyTextReturnsEmpty() {
        var rows = parser.parseText("");
        assertTrue(rows.isEmpty());
    }    @Test
    void textWithoutHeaderReturnsEmpty() {
        var rows = parser.parseText("Some random text without header");
        assertTrue(rows.isEmpty());
    }

    @Test
    void beginBalanceSkipped() {
        String text = """
                URUSNIAGA AKAUN/ACCOUNT TRANSACTIONS
                ENTRY DATE TRANSACTION DESCRIPTION TRANSACTION AMOUNT STATEMENT BALANCE
                BEGINNING BALANCE
                31,565.89
                """;
        var rows = parser.parseText(text);
        assertTrue(rows.isEmpty());
    }

    @Test
    void sourceTransactionIdIsNullForParseText() {
        String text = """
                URUSNIAGA AKAUN/ACCOUNT TRANSACTIONS
                ENTRY DATE TRANSACTION DESCRIPTION TRANSACTION AMOUNT STATEMENT BALANCE
                BEGINNING BALANCE
                31,565.89
                01/04/26
                SALE DEBIT
                14.12-
                31,551.77
                   ALP*GANKAIPIN
                   Shanghai     CN
                   SALE DEBIT
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertNull(rows.get(0).getSourceTransactionId());
    }

    @Test
    void recordSpanningPageBreakKeepsOnlyRealMerchantLines() {
        // Real June-2026 layout: the 2.00 MYDEBIT record opens at the bottom of
        // page 2, its merchant lines sit on page 3 after the full footer/header.
        // Footer fragments must not glue into the description (they once produced
        // a phantom "TEMPOH 21 HARI." payee downstream).
        String text = """
                06/06/26 PAYMENT VIA MYDEBIT 2.00- 40,600.30
                Perhation / Note
                (1) Semua maklumat dan baki yang dinyatakan di sini akan dianggap betul melainkan Bank telah dimaklumkan secara bertulis tentang sebarang ketidaktepatan dalam
                tempoh 21 hari.
                若银行在21天内未获得书面通知于任何差异，所有显示的账项及余额将被视为正确。
                All items and balances shown will be considered correct unless the Bank is notified in writing of any discrepancies within 21 days.
                (2) Sila beritahu kami sebarang pertukaran alamat secara bertulis.
                請通知本行在何地址更换。
                Please notify us of any change of address in writing.
                (3) Faedah merujuk kepada wang yang diperoleh daripada akaun Konvensional anda.
                利息”是指您从常规账户中因存款而获得的收益。
                Interest refers to the money earned from your Conventional account (s).
                Maybank Islamic Berhad (787435-M)
                15th Floor, Tower A, Dataran Maybank, 1, Jalan Maarof, 59000 Kuala Lumpur
                000003 IBS JOHOR PREMIUM OUTLETS SC MUKA/ 頁 /PAGE : 3
                TARIKH PENYATA
                CHANG YIN CHENG 結單日期 : 30/06/26
                NO 9803 ,JALAN KASAWARI 3 ,BANDAR PUTRA STATEMENT DATE
                KULAI ,81000 ,JOHOR ,MYS NOMBOR AKAUN
                戶號 : 000000-000000
                ACCOUNT
                NUMBER
                PROTECTED BY PIDM UP TO RM250,000 FOR EACH DEPOSITOR SAVINGS ACCOUNT-I
                URUSNIAGA AKAUN/ 戶口進支項 /ACCOUNT TRANSACTIONS
                TARIKH MASUK BUTIR URUSNIAGA JUMLAH URUSNIAGA BAKI PENYATA
                進支日期 進支項說明 银碼 結單存餘
                ENTRY DATE TRANSACTION DESCRIPTION TRANSACTION AMOUNT STATEMENT BALANCE
                   APSB.AM.AEON_MALL_T*
                   Johor Bahru
                   PAYMENT VIA MYDEBIT
                06/06/26 IBK FUND TFR FR A/C 926.00- 39,674.30
                   CHANG CHOON HONG   *
                   Repayments
                   DUITNOW
                """;
        var rows = parser.parseText(text);
        assertEquals(2, rows.size());
        String first = rows.get(0).getRawDescription();
        assertTrue(first.contains("APSB.AM.AEON_MALL_T*"), "orphan merchant lines rejoin: " + first);
        assertTrue(first.contains("Johor Bahru"), first);
        for (String junk : new String[]{"tempoh 21 hari", "Faedah merujuk", "Interest refers",
                "戶號", "進支日期", "NUMBER", "Perhation", "Maybank Islamic"}) {
            assertFalse(first.contains(junk), "footer leaked into description: " + junk + " in " + first);
        }
        assertTrue(rows.get(1).getRawDescription().contains("CHANG CHOON HONG"));
    }

    @Test
    void starredAccountHolderNameIsKeptAsMerchantLine() {
        // "CHANG YIN CHENG *" (own transfer merchant) must survive; only the
        // unstarred footer holder line is meta.
        String text = """
                25/06/26 IBK FUND TFR FR A 5.30- 37,490.33
                   CHANG YIN CHENG    *
                   0056728861
                   DUITNOW QR
                25/06/26 FPX PAYMENT FR A/ 30.24- 37,460.09
                   AKAR7P94L2506260844*
                   AIRASIA BERHAD
                """;
        var rows = parser.parseText(text);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).getRawDescription().contains("CHANG YIN CHENG"),
                rows.get(0).getRawDescription());
    }

    @Test
    void statementSummaryLinesAreSkipped() {
        String text = """
                30/06/26 PROFIT PAID 20.85+ 42,556.49
                ENDING BALANCE :
                42,556.49
                TOTAL CREDIT :
                7,708.09
                TOTAL DEBIT :
                6,978.90
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        String desc = rows.get(0).getRawDescription();
        assertEquals("PROFIT PAID | 20.85+", desc);
    }
}
