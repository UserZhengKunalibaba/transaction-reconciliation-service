package com.transactionprocessor.transactionprocessor.parser;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GrabParserExtendedTest {

    private GrabParser parser;

    @BeforeEach
    void setUp() {
        AppConfig c = new AppConfig();
        parser = new GrabParser(new PdfTextExtractor(), c);
    }

    // --- supports ---

    @Test
    void supportsGrabPdf() {
        assertTrue(parser.supports(new StatementFileDescriptor(
                java.nio.file.Paths.get("/s/Grab statement.pdf"),
                "Grab statement.pdf", 1, "PDF")));
    }

    @Test
    void supportsGrabWithDifferentName() {
        assertTrue(parser.supports(new StatementFileDescriptor(
                java.nio.file.Paths.get("/s/Grab_History.pdf"),
                "Grab_History.pdf", 1, "PDF")));
    }

    @Test
    void doesNotSupportXlsx() {
        assertFalse(parser.supports(new StatementFileDescriptor(
                java.nio.file.Paths.get("/s/Grab.xlsx"),
                "Grab.xlsx", 1, "XLSX")));
    }

    @Test
    void doesNotSupportNonGrabPdf() {
        assertFalse(parser.supports(new StatementFileDescriptor(
                java.nio.file.Paths.get("/s/OtherBank.pdf"),
                "OtherBank.pdf", 1, "PDF")));
    }

    // --- version ---

    @Test
    void versionIsGrabV1() {
        assertEquals("grab-v1", parser.version());
    }

    // --- parseTextTable ---

    @Test
    void parsesGrabTextTable() throws Exception {
        String text = "08 Sep 2026, 11:58AM A-9QDD6APWWR9QAV Bach Viet - Bandar Indahpura MYR 20.10\n"
                + "19 Jun 2026, 02:00PM A-9GWXL44GWX5TAV Gravy Noodle - Rat Na Yot THB 74.00";
        var rows = parser.parseTextTable(text);
        assertEquals(2, rows.size());
        assertEquals("MYR", rows.get(0).getRawCurrency());
        assertEquals("THB", rows.get(1).getRawCurrency());
    }

    @Test
    void parsesSingleGrabTrip() throws Exception {
        String text = "08 Sep 2026, 11:58AM A-9QDD6APWWR9QAV Bach Viet - Bandar Indahpura MYR 20.10";
        var rows = parser.parseTextTable(text);
        assertEquals(1, rows.size());
        assertEquals("A-9QDD6APWWR9QAV", rows.get(0).getSourceTransactionId());
        assertEquals("20.10", rows.get(0).getRawAmount());
        assertEquals("MYR", rows.get(0).getRawCurrency());
        assertEquals("08 Sep 2026, 11:58AM", rows.get(0).getRawDate());
    }

    @Test
    void parsesGrabDescription() throws Exception {
        String text = "08 Sep 2026, 11:58AM A-9QDD6APWWR9QAV Bach Viet - Bandar Indahpura MYR 20.10";
        var rows = parser.parseTextTable(text);
        assertEquals(1, rows.size());
        assertEquals("Bach Viet - Bandar Indahpura", rows.get(0).getRawDescription());
    }

    @Test
    void parsesBookingCode() throws Exception {
        String text = "08 Sep 2026, 11:58AM A-9QDD6APWWR9QAV Bach Viet - Bandar Indahpura MYR 20.10";
        var rows = parser.parseTextTable(text);
        assertEquals(1, rows.size());
        assertEquals("A-9QDD6APWWR9QAV", rows.get(0).getPayload().get("bookingCode"));
    }

    @Test
    void parsesDateTime() throws Exception {
        String text = "08 Sep 2026, 11:58AM A-9QDD6APWWR9QAV Bach Viet - Bandar Indahpura MYR 20.10";
        var rows = parser.parseTextTable(text);
        assertEquals(1, rows.size());
        assertEquals("08 Sep 2026, 11:58AM", rows.get(0).getPayload().get("dateTime"));
    }

    @Test
    void rawDataHashInputContainsBookingCodeAndAmount() throws Exception {
        String text = "08 Sep 2026, 11:58AM A-9QDD6APWWR9QAV Bach Viet MYR 20.10";
        var rows = parser.parseTextTable(text);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).getRawDataHashInput().contains("A-9QDD6APWWR9QAV"));
        assertTrue(rows.get(0).getRawDataHashInput().contains("20.10"));
    }

    @Test
    void amountWithCommasParsed() throws Exception {
        String text = "08 Sep 2026, 11:58AM A-9QDD6APWWR9QAV Bach Viet MYR 1,234.56";
        var rows = parser.parseTextTable(text);
        assertEquals(1, rows.size());
        assertEquals("1234.56", rows.get(0).getRawAmount());
    }

    @Test
    void noRowsThrowsNoRowsException() {
        String text = "Some random text without any trip rows";
        assertThrows(StatementParseException.class, () -> parser.parseTextTable(text));
    }

    @Test
    void emptyTextThrowsNoRowsException() {
        assertThrows(StatementParseException.class, () -> parser.parseTextTable(""));
    }

    @Test
    void multipleTripsParsedCorrectly() throws Exception {
        String text = """
                08 Sep 2026, 11:58AM A-9QDD6APWWR9QAV Bach Viet MYR 20.10
                19 Jun 2026, 02:00PM A-9GWXL44GWX5TAV Gravy Noodle THB 74.00
                01 Aug 2026, 09:30AM A-9ABC123DEF456GH Grab Food MYR 15.50
                """;
        var rows = parser.parseTextTable(text);
        assertEquals(3, rows.size());
    }

    @Test
    void payloadContainsAllFields() throws Exception {
        String text = "08 Sep 2026, 11:58AM A-9QDD6APWWR9QAV Bach Viet MYR 20.10";
        var rows = parser.parseTextTable(text);
        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).getPayload().get("dateTime"));
        assertNotNull(rows.get(0).getPayload().get("bookingCode"));
        assertNotNull(rows.get(0).getPayload().get("currency"));
        assertNotNull(rows.get(0).getPayload().get("amount"));
    }
}
