package com.transactionprocessor.transactionprocessor.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * Low-level PDF text extraction.
 * - Tries no-password, then empty-string password (Maybank owner-password files).
 * - Returns text; caller decides whether it is scanned (text length &lt; threshold).
 * - Never runs OCR itself (see GrabParser OCR branch); keeps text path default.
 */
@Component
public class PdfTextExtractor {

    public static class PdfText {
        public final String text;
        public final int pages;
        public final boolean encrypted;

        public PdfText(String text, int pages, boolean encrypted) {
            this.text = text;
            this.pages = pages;
            this.encrypted = encrypted;
        }
    }

    public PdfText extract(File file, String password) throws IOException {
        IOException last = null;
        String[] attempts = password != null && !password.isEmpty()
                ? new String[]{password, "", null}
                : new String[]{"", null};
        for (String pw : attempts) {
            try {
                PDDocument doc;
                if (pw == null) doc = PDDocument.load(file);
                else doc = PDDocument.load(file, pw);
                try {
                    boolean enc = doc.isEncrypted();
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true);
                    String text = stripper.getText(doc);
                    int pages = doc.getNumberOfPages();
                    return new PdfText(text == null ? "" : text, pages, enc);
                } finally {
                    doc.close();
                }
            } catch (IOException e) {
                String msg = String.valueOf(e.getMessage()).toLowerCase();
                if (msg.contains("password") || msg.contains("decrypt") || msg.contains("encrypted")) {
                    last = e;
                    continue;
                }
                throw e;
            }
        }
        throw new IOException("PDF is password-protected and no valid password was provided", last);
    }

    public boolean isScanned(String text) {
        if (text == null) return true;
        return text.strip().length() < 50;
    }
}
