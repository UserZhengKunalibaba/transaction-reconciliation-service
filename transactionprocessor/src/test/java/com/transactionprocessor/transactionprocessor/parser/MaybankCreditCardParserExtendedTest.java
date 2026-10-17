package com.transactionprocessor.transactionprocessor.parser;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaybankCreditCardParserExtendedTest {

    private MaybankCreditCardParser parser;

    @BeforeEach
    void setUp() {
        AppConfig c = new AppConfig();
        parser = new MaybankCreditCardParser(new PdfTextExtractor(), c);
    }

    // --- supports ---

    @Test
    void supportsBillFilenames() {
        var d = new StatementFileDescriptor(java.nio.file.Paths.get("/s/M2U Bill 0000 Aug 2026.pdf"),
                "M2U Bill 0000 Aug 2026.pdf", 1, "PDF");
        assertTrue(parser.supports(d));
    }

    @Test
    void supportsAlternativeBillFilenames() {
        var d = new StatementFileDescriptor(java.nio.file.Paths.get("/s/Maybank Credit Card Statement.pdf"),
                "Maybank Credit Card Statement.pdf", 1, "PDF");
        assertTrue(parser.supports(d));
    }

    @Test
    void doesNotSupportSavingsPdf() {
        var d = new StatementFileDescriptor(java.nio.file.Paths.get("/s/Savings Account.pdf"),
                "Savings Account.pdf", 1, "PDF");
        assertFalse(parser.supports(d));
    }

    @Test
    void doesNotSupportXlsx() {
        var d = new StatementFileDescriptor(java.nio.file.Paths.get("/s/bill.xlsx"),
                "bill.xlsx", 1, "XLSX");
        assertFalse(parser.supports(d));
    }

    // --- version ---

    @Test
    void versionIsMaybankCcV1() {
        assertEquals("maybank-cc-v1", parser.version());
    }

    // --- parseText ---

    @Test
    void parsesBillRowsWithForeignAmount() {
        String text = """
                Statement Date/ 24 AUG 26
                Posting Date / Transaction Date / Transaction Description / Amount(RM)
                25/07
                25/07
                RTL PROFIT CHRG RATE 17.00%
                Alipay*Taobao       M4
                TRANSACTED AMOUNT              CNY             8.90
                Shanghai      CN
                5.49
                30/07
                29/07
                Alipay*Taobao       M4
                TRANSACTED AMOUNT              CNY           300.76
                Shanghai      CN
                186.14
                """;
        var rows = parser.parseText(text);
        assertTrue(rows.size() >= 2, "expected >=2 rows, got " + rows.size());
        assertEquals("CNY", String.valueOf(rows.get(0).getPayload().get("foreignCurrency")));
    }

    @Test
    void parsesMyrOnlyTransaction() {
        String text = """
                Statement Date/ 24 AUG 26
                Posting Date / Transaction Date / Transaction Description / Amount(RM)
                01/08 01/08 GRAB FOOD 15.90
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("15.90", rows.get(0).getRawAmount());
        assertEquals("MYR", rows.get(0).getRawCurrency());
    }

    @Test
    void parsesMultipleTransactions() {
        String text = """
                Statement Date/ 24 AUG 26
                Posting Date / Transaction Date / Transaction Description / Amount(RM)
                25/07 25/07 Alipay*Taobao M4 Shanghai CN 5.49
                TRANSACTED AMOUNT              CNY             8.90
                30/07 29/07 Alipay*Taobao M4 Shanghai CN 186.14
                TRANSACTED AMOUNT              CNY           300.76
                """;
        var rows = parser.parseText(text);
        assertEquals(2, rows.size());
    }

    @Test
    void foreignAmountExtractedCorrectly() {
        String text = """
                Statement Date/ 24 AUG 26
                Posting Date / Transaction Date / Transaction Description / Amount(RM)
                30/07
                29/07
                Alipay*Taobao       M4
                TRANSACTED AMOUNT              CNY           300.76
                Shanghai      CN
                186.14
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("300.76", rows.get(0).getPayload().get("foreignAmount"));
        assertEquals("CNY", rows.get(0).getPayload().get("foreignCurrency"));
        assertEquals("186.14", rows.get(0).getRawAmount());
    }

    @Test
    void transactionDateExtracted() {
        String text = """
                Statement Date/ 24 AUG 26
                Posting Date / Transaction Date / Transaction Description / Amount(RM)
                25/07
                25/07
                Alipay*Taobao       M4
                TRANSACTED AMOUNT              CNY             8.90
                Shanghai      CN
                5.49
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).getRawDate());
    }

    @Test
    void merchantExtractedFromDescription() {
        String text = """
                Statement Date/ 24 AUG 26
                Posting Date / Transaction Date / Transaction Description / Amount(RM)
                25/07
                25/07
                Alipay*Taobao       M4
                TRANSACTED AMOUNT              CNY             8.90
                Shanghai      CN
                5.49
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        String desc = rows.get(0).getRawDescription();
        assertTrue(desc.contains("Alipay*Taobao"), "Description should contain merchant");
    }

    @Test
    void emptyTextReturnsEmpty() {
        var rows = parser.parseText("");
        assertTrue(rows.isEmpty());
    }

    @Test
    void textWithoutHeaderReturnsEmpty() {
        var rows = parser.parseText("Some random text without header");
        assertTrue(rows.isEmpty());
    }
}
