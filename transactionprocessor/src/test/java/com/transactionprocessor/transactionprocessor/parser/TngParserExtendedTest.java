package com.transactionprocessor.transactionprocessor.parser;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TngParserExtendedTest {

    private TngParser parser;

    @BeforeEach
    void setUp() {
        AppConfig c = new AppConfig();
        parser = new TngParser(new PdfTextExtractor(), c);
    }

    // --- supports ---

    @Test
    void supportsTngFilename() {
        assertTrue(parser.supports(new StatementFileDescriptor(
                java.nio.file.Paths.get("/s/tng_ewallet_transactions.pdf"),
                "tng_ewallet_transactions.pdf", 1, "PDF")));
    }

    @Test
    void supportsTouchNGoFilename() {
        assertTrue(parser.supports(new StatementFileDescriptor(
                java.nio.file.Paths.get("/s/touch_n_go_statement.pdf"),
                "touch_n_go_statement.pdf", 1, "PDF")));
    }

    @Test
    void supportsEwalletFilename() {
        assertTrue(parser.supports(new StatementFileDescriptor(
                java.nio.file.Paths.get("/s/ewallet_transactions.pdf"),
                "ewallet_transactions.pdf", 1, "PDF")));
    }

    @Test
    void doesNotSupportXlsx() {
        assertFalse(parser.supports(new StatementFileDescriptor(
                java.nio.file.Paths.get("/s/tng.xlsx"),
                "tng.xlsx", 1, "XLSX")));
    }

    @Test
    void doesNotSupportRandomPdf() {
        assertFalse(parser.supports(new StatementFileDescriptor(
                java.nio.file.Paths.get("/s/bank_statement.pdf"),
                "bank_statement.pdf", 1, "PDF")));
    }

    // --- version ---

    @Test
    void versionIsTngV1() {
        assertEquals("tng-v1", parser.version());
    }

    // --- parseText ---

    @Test
    void parsesPaymentTransaction() {
        String text = """
                11/6/2026
                Success Payment
                Star Mart *38727403471
                RM8.50 RM123.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("11/6/2026", rows.get(0).getRawDate());
        assertEquals("-8.50", rows.get(0).getRawAmount());
        assertEquals("MYR", rows.get(0).getRawCurrency());
        assertEquals("Payment", rows.get(0).getPayload().get("type"));
    }

    @Test
    void parsesDuitNowTransaction() {
        String text = """
                11/6/2026
                DuitNow QR
                Coffee Shop
                RM15.00 RM100.00
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("-15.00", rows.get(0).getRawAmount());
        assertEquals("DuitNow QR", rows.get(0).getPayload().get("type"));
    }

    @Test
    void parsesReceiveFromWallet() {
        String text = """
                11/6/2026
                Receive from Wallet
                Transfer from friend
                RM50.00 RM200.00
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("50.00", rows.get(0).getRawAmount());
        assertEquals("Receive from Wallet", rows.get(0).getPayload().get("type"));
    }

    @Test
    void parsesTransferToWallet() {
        String text = """
                11/6/2026
                Transfer to Wallet
                Transfer to friend
                RM25.00 RM175.00
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("-25.00", rows.get(0).getRawAmount());
        assertEquals("Transfer to Wallet", rows.get(0).getPayload().get("type"));
    }

    @Test
    void parsesCashback() {
        String text = """
                11/6/2026
                Cashback
                Promotion reward
                RM1.00 RM124.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("1.00", rows.get(0).getRawAmount());
        assertEquals("Cashback", rows.get(0).getPayload().get("type"));
    }

    @Test
    void parsesRefund() {
        String text = """
                11/6/2026
                Refund
                Returned item
                RM20.00 RM143.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("20.00", rows.get(0).getRawAmount());
        assertEquals("Refund", rows.get(0).getPayload().get("type"));
    }

    @Test
    void parsesMultipleTransactions() {
        String text = """
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                12/6/2026
                DuitNow QR
                Coffee Shop
                RM15.00 RM108.45
                """;
        var rows = parser.parseText(text);
        assertEquals(2, rows.size());
        assertEquals("11/6/2026", rows.get(0).getRawDate());
        assertEquals("12/6/2026", rows.get(1).getRawDate());
    }

    @Test
    void singleLinePdfBoxRowDetectsTypeAndSign() {
        // PDFBox merges table columns onto one line: the type shares the amount line.
        String text = """
                11/6/2026 Success Payment 20260611101 cos stuff RM6.07 RM117.90
                10000010000 35119
                TNGOW3MY1
                11/6/2026 Success Receive from Wallet 20260611102 friend stuff RM50.00 RM167.90
                """;
        var rows = parser.parseText(text);
        assertEquals(2, rows.size());
        assertEquals("-6.07", rows.get(0).getRawAmount());
        assertEquals("Payment", rows.get(0).getPayload().get("type"));
        assertEquals("50.00", rows.get(1).getRawAmount());
        assertEquals("Receive from Wallet", rows.get(1).getPayload().get("type"));
    }

    @Test
    void emptyTextReturnsEmpty() {
        var rows = parser.parseText("");
        assertTrue(rows.isEmpty());
    }

    @Test
    void nullTextReturnsEmpty() {
        var rows = parser.parseText(null);
        assertTrue(rows.isEmpty());
    }

    @Test
    void blankTextReturnsEmpty() {
        var rows = parser.parseText("   \n  \n  ");
        assertTrue(rows.isEmpty());
    }

    @Test
    void skipsHeaderLines() {
        String text = """
                TNG WALLET
                Date Status Type Reference Details Amount(RM) WalletBalance
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
    }

    @Test
    void skipsGeneratedDateTime() {
        String text = """
                Generated Date & Time
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
    }

    @Test
    void skipsTransactionPeriod() {
        String text = """
                Transaction Period
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
    }

    @Test
    void skipsRegisteredName() {
        String text = """
                Registered Name
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
    }

    @Test
    void skipsWalletId() {
        String text = """
                Wallet ID
                1234567890
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
    }

    @Test
    void skipsAccountStatus() {
        String text = """
                Account Status
                Active
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
    }

    @Test
    void skipsPhoneNumber() {
        String text = """
                +603 5022 1234
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
    }

    @Test
    void skipsSeparatorLines() {
        String text = """
                =====
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
    }

    @Test
    void skipsSystemMessageLines() {
        String text = """
                *This is a system generated statement
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
    }

    @Test
    void parsesForeignCurrencyTransaction() {
        String text = """
                11/6/2026
                DuitNow QR
                (John Doe) CNY 7.00
                RM5.00 RM100.00
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).getRawDescription().contains("CNY"));
    }

    @Test
    void sourceTransactionIdContainsDateAndAmount() {
        String text = """
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        String sourceId = rows.get(0).getSourceTransactionId();
        assertTrue(sourceId.contains("11/6/2026"));
        assertTrue(sourceId.contains("8.50"));
    }

    @Test
    void payloadContainsAllFields() {
        String text = """
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).getPayload().get("date"));
        assertNotNull(rows.get(0).getPayload().get("type"));
        assertNotNull(rows.get(0).getPayload().get("description"));
        assertNotNull(rows.get(0).getPayload().get("walletBalance"));
    }

    @Test
    void walletBalanceExtracted() {
        String text = """
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("123.45", rows.get(0).getPayload().get("walletBalance"));
    }

    @Test
    void duittNowReceivefromType() {
        String text = """
                11/6/2026
                DUITNOW_RECEIVEFROM
                Transfer from bank
                RM100.00 RM500.00
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("DUITNOW_RECEIVEFROM", rows.get(0).getPayload().get("type"));
    }

    @Test
    void amountWithoutDecimalFailsGracefully() {
        // Amount without .XX should not match AMOUNT_LINE pattern
        String text = """
                11/6/2026
                Success Payment
                Star Mart
                RM8 RM123
                """;
        var rows = parser.parseText(text);
        // Amount pattern requires .XX, so this should not parse
        assertEquals(0, rows.size());
    }

    @Test
    void multipleTransactionsDifferentDates() {
        String text = """
                11/6/2026
                Success Payment
                Star Mart
                RM8.50 RM123.45
                12/6/2026
                DuitNow QR
                Coffee Shop
                RM15.00 RM108.45
                13/6/2026
                Cashback
                Promotion
                RM1.00 RM109.45
                """;
        var rows = parser.parseText(text);
        assertEquals(3, rows.size());
        assertEquals("11/6/2026", rows.get(0).getRawDate());
        assertEquals("12/6/2026", rows.get(1).getRawDate());
        assertEquals("13/6/2026", rows.get(2).getRawDate());
    }

    @Test
    void moneyPacketReceivedStaysPositive() {
        String text = """
                18/2/2026
                Money Packet 20260218111 DING CHEE TENG 2026021810110000010000TNGOW3 Received 21700070300 MY171631562196466 2702
                RM0.95 RM600.00
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("0.95", rows.get(0).getRawAmount());
        assertEquals("Money Packet", rows.get(0).getPayload().get("type"));
    }

    @Test
    void moneyPacketSentIsNegative() {
        // "Random for N Receivers" with no Received marker = packet you sent.
        String text = """
                17/2/2026
                Money Packet 20260217111 Random for 3 Receivers 2026021710110000010000TNGOW3 21700010100 MY171443163674944 9765
                RM100.00 RM400.00
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertEquals("-100.00", rows.get(0).getRawAmount());
        assertEquals("Money Packet", rows.get(0).getPayload().get("type"));
    }

    @Test
    void moneyPacketSentKeepsDescriptionForStablePayee() {
        // The Money Packet line must stay in the description: merchant/payee
        // extraction keys off its leading words, so dropping it would change
        // payees (and dedup keys) for reimports.
        String text = """
                17/2/2026
                Money Packet 20260217111 Random for 3 Receivers 2026021710110000010000TNGOW3 21700010100 MY171443163674944 9765
                RM100.00 RM400.00
                """;
        var rows = parser.parseText(text);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).getRawDescription().startsWith("Money Packet"));
    }

    @Test
    void sentMoneyPacketRule() {
        assertTrue(TngParser.isSentMoneyPacket("Money Packet",
                "Money Packet 20260217111 Random for 3 Receivers 2026021710110000010000TNGOW3 21700010100 MY171443163674944 9765"));
        assertFalse(TngParser.isSentMoneyPacket("Money Packet",
                "Money Packet 20260218111 DING CHEE TENG 2026021810110000010000TNGOW3 Received 21700070300 MY171631562196466 2702"));
        assertFalse(TngParser.isSentMoneyPacket("Payment", "Payment Star Mart"));
        assertFalse(TngParser.isSentMoneyPacket("Money Packet", null));
    }
}
