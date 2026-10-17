package com.transactionprocessor.transactionprocessor.parser;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

@Disabled("Requires private PDF fixtures; repository tests use synthetic data only")
class MaybankRealFileTest {

    @Test
    void realBillAndSaParseNonEmpty() throws Exception {
        String base = System.getenv("HOME") + "/Downloads/statements/Maybank/";
        AppConfig c = new AppConfig();
        PdfTextExtractor pdf = new PdfTextExtractor();
        MaybankCreditCardParser cc = new MaybankCreditCardParser(pdf, c);
        MaybankSavingsParser sa = new MaybankSavingsParser(pdf, c);

        File bill = new File(base + "M2U Bill 0000 Aug 2026.pdf");
        var ccRows = cc.parse(new StatementFileDescriptor(bill.toPath(), bill.getName(), bill.length(), "PDF"));
        System.out.println("BILL Aug rows=" + ccRows.size());
        assertTrue(ccRows.size() >= 4, "expected >=4 bill rows, got " + ccRows.size());

        File sav = new File(base + "M2U SA 000000 Feb 2026.pdf");
        var saRows = sa.parse(new StatementFileDescriptor(sav.toPath(), sav.getName(), sav.length(), "PDF"));
        System.out.println("SA Feb rows=" + saRows.size());
        assertTrue(saRows.size() >= 20, "expected >=20 SA rows, got " + saRows.size());
        assertTrue(saRows.stream().anyMatch(r -> r.getRawDescription().contains("GRABPAY")));
        assertTrue(saRows.stream().anyMatch(r -> r.getRawDescription().contains("PRE-AUTH")));
    }
}
