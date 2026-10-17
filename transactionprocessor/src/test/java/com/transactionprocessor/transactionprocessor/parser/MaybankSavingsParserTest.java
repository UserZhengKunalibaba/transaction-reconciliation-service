package com.transactionprocessor.transactionprocessor.parser;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaybankSavingsParserTest {

    private MaybankSavingsParser parser() {
        AppConfig c = new AppConfig();
        return new MaybankSavingsParser(new PdfTextExtractor(), c);
    }

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
        var rows = parser().parseText(text);
        assertEquals(2, rows.size());
        assertEquals("14.26", rows.get(0).getRawAmount().replace("-", ""));
        assertTrue(rows.get(0).getRawDescription().contains("PRE-AUTH REFUND"));
        assertTrue(rows.get(1).getRawDescription().contains("ALP*GANKAIPIN"));
    }

    @Test
    void supportsSavingsFilenames() {
        var d = new StatementFileDescriptor(java.nio.file.Paths.get("/s/M2U SA 000000 Apr 2026.pdf"),
                "M2U SA 000000 Apr 2026.pdf", 1, "PDF");
        assertTrue(parser().supports(d));
    }
}
