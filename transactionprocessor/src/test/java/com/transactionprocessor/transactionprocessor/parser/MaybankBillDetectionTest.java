package com.transactionprocessor.transactionprocessor.parser;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generated storage names (uuid.pdf) carry no filename hints, so the Bill parser
 * must detect card bills by content. Plain unit tests with synthetic PDFs.
 */
class MaybankBillDetectionTest {

    @TempDir
    Path tempDir;

    private Path writePdf(String name, List<String> lines) throws Exception {
        Path p = tempDir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 10);
                cs.newLineAtOffset(50, 750);
                for (String l : lines) {
                    cs.showText(l);
                    cs.newLineAtOffset(0, -14);
                }
                cs.endText();
            }
            doc.save(p.toFile());
        }
        return p;
    }

    private StatementFileDescriptor desc(Path p) throws Exception {
        return new StatementFileDescriptor(p, p.getFileName().toString(), Files.size(p), "PDF");
    }

    private ParserRegistry registry(AppConfig c) {
        PdfTextExtractor pdf = new PdfTextExtractor();
        return new ParserRegistry(new AlipayParser(), new WechatPayParser(),
                new MaybankCreditCardParser(pdf, c), new MaybankSavingsParser(pdf, c),
                new GrabParser(pdf, c), new TngParser(pdf, c));
    }

    @Test
    void uuidNamedBillIsDetectedByContent() throws Exception {
        Path bill = writePdf("20260910-a1b2c3d4.pdf", List.of(
                "MAYBANK CARD - CONVENIENCE, SAVINGS AND REWARDS",
                "5460 1500 3352 0000 62.18 25.00",
                "24 AUG 26 13 SEP 26",
                "06/06 05/06 Alipay*Taobao M4 Shanghai CN 30.31"));
        AppConfig c = new AppConfig();
        MaybankCreditCardParser cc = new MaybankCreditCardParser(new PdfTextExtractor(), c);

        assertTrue(cc.supports(desc(bill)), "bill header must match despite uuid filename");

        // Registry routes it to CC even with no path hints (temp dir, uuid name).
        StatementParser found = registry(c).find(desc(bill));
        assertInstanceOf(MaybankCreditCardParser.class, found);

        var rows = cc.parse(desc(bill));
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).getRawDescription().contains("Alipay*Taobao"));
        assertEquals("30.31", rows.get(0).getRawAmount());
    }

    @Test
    void savingsPdfIsNotClaimedByBillParser() throws Exception {
        Path sa = writePdf("20260910-e5f6a7b8.pdf", List.of(
                "SAVINGS ACCOUNT 000000-000000",
                "01/02/26 PYMT FROM A/C 24.93- 25,034.00"));
        AppConfig c = new AppConfig();
        MaybankCreditCardParser cc = new MaybankCreditCardParser(new PdfTextExtractor(), c);
        assertFalse(cc.supports(desc(sa)));
    }

    @Test
    void filenameHintsStillWorkWithoutContentSniff() {
        AppConfig c = new AppConfig();
        MaybankCreditCardParser cc = new MaybankCreditCardParser(new PdfTextExtractor(), c);
        assertTrue(cc.supports(new StatementFileDescriptor(
                Path.of("/x/Bill-0000.pdf"), "Bill-0000.pdf", 1, "PDF")));
        assertFalse(cc.supports(new StatementFileDescriptor(
                Path.of("/x/notes.txt"), "notes.txt", 1, "PDF")));
    }
}
