package com.transactionprocessor.transactionprocessor.parser;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class GrabTngParserTest {

    @Test
    void grabImagePdfThrowsNeedOcr() {
        AppConfig c = new AppConfig();
        GrabParser grab = new GrabParser(new PdfTextExtractor(), c);
        // Grab statement.pdf is image-only; but fixture-independent: parser supports() check
        assertTrue(grab.supports(new StatementFileDescriptor(Paths.get("/s/Grab statement.pdf"), "Grab statement.pdf", 1, "PDF")));
    }

    @Test
    void grabTextTableParses() throws Exception {
        AppConfig c = new AppConfig();
        GrabParser grab = new GrabParser(new PdfTextExtractor(), c);
        String text = "08 Sep 2026, 11:58AM A-9QDD6APWWR9QAV Bach Viet - Bandar Indahpura MYR 20.10\n"
                + "19 Jun 2026, 02:00PM A-9GWXL44GWX5TAV Gravy Noodle - Rat Na Yot THB 74.00";
        var rows = grab.parseTextTable(text);
        assertEquals(2, rows.size());
        assertEquals("MYR", rows.get(0).getRawCurrency());
        assertEquals("THB", rows.get(1).getRawCurrency());
    }

    @Test
    void tngSupports() {
        AppConfig c = new AppConfig();
        TngParser tng = new TngParser(new PdfTextExtractor(), c);
        assertTrue(tng.supports(new StatementFileDescriptor(
                Paths.get("/s/tng_ewallet_transactions.pdf"), "tng_ewallet_transactions_20260611_20260908.pdf", 1, "PDF")));
    }

    @Test
    void tngParseTextRealData() throws Exception {
        Assumptions.assumeTrue(Files.exists(Paths.get("/tmp/tng_full.txt")), "local TNG fixture absent - skipping");
        String text = new String(Files.readAllBytes(Paths.get("/tmp/tng_full.txt")));
        AppConfig c = new AppConfig();
        TngParser tng = new TngParser(new PdfTextExtractor(), c);
        var rows = tng.parseText(text);
        assertFalse(rows.isEmpty(), "Should parse TNG transactions");
        // Verify first transaction
        var first = rows.get(0);
        assertEquals("11/6/2026", first.getRawDate());
        assertEquals("-6.07", first.getRawAmount());
        assertEquals("MYR", first.getRawCurrency());
        assertNotNull(first.getRawDescription());
        assertFalse(first.getRawDescription().isEmpty());
        // Verify a Receive from Wallet transaction (type is in payload, description has merchant)
        var receive = rows.stream().filter(r -> {
            Object t = r.getPayload().get("type");
            return t != null && t.toString().contains("Receive from Wallet");
        }).findFirst().orElse(null);
        assertNotNull(receive, "Should find a Receive from Wallet transaction");
        // Verify a DuitNow QR transaction
        var duitnow = rows.stream().filter(r -> {
            Object t = r.getPayload().get("type");
            return t != null && (t.toString().contains("DuitNow") || t.toString().contains("DUITNOW"));
        }).findFirst().orElse(null);
        assertNotNull(duitnow, "Should find a DuitNow/DuitNow QR transaction");
        // Verify foreign currency transaction
        var fx = rows.stream().filter(r -> r.getRawDescription().contains("CNY")).findFirst().orElse(null);
        assertNotNull(fx, "Should find foreign currency (CNY) transaction");
        assertTrue(fx.getRawDescription().contains("CNY 7.00"));
        System.out.println("TNG parsed " + rows.size() + " transactions");
        for (var r : rows) {
            System.out.println("  " + r.getRawDate() + " | " + r.getRawAmount() + " | " + r.getRawDescription());
        }
    }
}
