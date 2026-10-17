package com.transactionprocessor.transactionprocessor.parser;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grab: Member Statement PDF is image-only (Print to PDF screenshots, 136 JPEGs, no text).
 * Strategy: render to 300dpi PNGs, OCR with tesseract, parse TSV word positions to reconstruct
 * table rows. Per-column cropping for booking codes + whitelist for currency.
 */
@Component
public class GrabParser implements StatementParser {
    private static final Logger log = LoggerFactory.getLogger(GrabParser.class);

    private final PdfTextExtractor pdf;
    private final AppConfig config;

    public GrabParser(PdfTextExtractor pdf, AppConfig config) {
        this.pdf = pdf;
        this.config = config;
    }

    @Override
    public String version() { return "grab-v1"; }

    @Override
    public boolean supports(StatementFileDescriptor file) {
        String n = file.getFilename().toLowerCase();
        if (n.endsWith(".pdf") && (n.contains("grab"))) return true;
        return false;
    }

    @Override
    public List<RawTransactionData> parse(StatementFileDescriptor file) throws StatementParseException {
        File f = file.getPath().toFile();
        String text;
        try {
            PdfTextExtractor.PdfText t = pdf.extract(f, null);
            text = t.text;
        } catch (Exception e) {
            throw new StatementParseException("Parse failed: " + e.getMessage(), e);
        }
        if (!pdf.isScanned(text)) {
            return parseTextTable(text);
        }
        // Scanned branch → try OCR if enabled
        if (config.isOcrEnabled() && tesseractAvailable()) {
            List<RawTransactionData> ocr = ocrParse(f);
            if (ocr != null) return ocr;
        }
        throw new StatementParseException("NEED_OCR",
                "Grab statement is image-only PDF (no text layer, ~72 trips). " +
                "Install tesseract and set OCR_ENABLED=true, or export Grab history as CSV. " +
                "File kept as ERROR without blocking other statements.");
    }

    List<RawTransactionData> parseTextTable(String text) throws StatementParseException {
        List<RawTransactionData> out = new ArrayList<>();
        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "(\\d{1,2}\\s+\\w{3}\\s+\\d{4}[^\\n]*?\\d{1,2}:\\d{2}\\s*[AP]M)\\s+(A-[A-Z0-9]+)\\s+(.+?)\\s+(MYR|THB)\\s+([\\d,]+\\.\\d{2})",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = row.matcher(text);
        while (m.find()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dateTime", m.group(1).strip());
            payload.put("bookingCode", m.group(2).strip());
            payload.put("route", m.group(3).strip());
            payload.put("currency", m.group(4).strip());
            payload.put("amount", m.group(5).strip());
            RawTransactionData d = new RawTransactionData();
            d.setSourceTransactionId(m.group(2).strip());
            d.setRawDate(m.group(1).strip());
            d.setRawDescription(m.group(3).strip());
            d.setRawAmount(m.group(5).strip().replace(",", ""));
            d.setRawCurrency(m.group(4).strip());
            d.setPayload(payload);
            d.setRawDataHashInput(m.group(2).strip() + "|" + m.group(5).strip());
            out.add(d);
        }
        if (out.isEmpty()) {
            throw new StatementParseException("NO_ROWS",
                    "Grab text found but no trip rows matched (expected Booking Code A-...).");
        }
        return out;
    }

    /**
     * Render each PDF page to a PNG at 300 DPI, run tesseract with TSV output,
     * parse word positions to reconstruct table rows.
     */
    protected List<RawTransactionData> ocrParse(File pdfFile) throws StatementParseException {
        List<RawTransactionData> out = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            File tmpDir = java.nio.file.Files.createTempDirectory("grab-ocr").toFile();
            try {
                for (int page = 0; page < doc.getNumberOfPages(); page++) {
                    BufferedImage fullImg = renderer.renderImageWithDPI(page, 300);
                    int imgW = fullImg.getWidth();
                    int imgH = fullImg.getHeight();
                    File imgFile = new File(tmpDir, "page_" + page + ".png");
                    ImageIO.write(fullImg, "png", imgFile);

                    // Full-page OCR with TSV for word positions
                    File tsvFile = new File(tmpDir, "page_" + page + ".tsv");
                    runTesseract(imgFile, tsvFile);
                    List<RawTransactionData> pageRows = parseFullPageTsv(tsvFile, imgW, imgH);
                    out.addAll(pageRows);

                    // Also run per-column OCR for booking codes with whitelist
                    File bkImgFile = cropColumn(fullImg, 351, 623, 620, tmpDir, "p" + page + "_bk.png");
                    String bkText = runTesseractText(bkImgFile, "-l", "eng", "--psm", "6",
                            "-c", "tessedit_char_whitelist=A-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ");
                    List<String[]> bkWords = parseColumnLines(bkText);

                    // Try to improve bookings from per-column OCR by matching y-position
                    for (RawTransactionData r : pageRows) {
                        String existingBk = (String) r.getPayload().get("bookingCode");
                        if (existingBk != null && BK_RE.matcher(existingBk).matches()) continue;
                        // Try to find a better booking code from the column OCR
                        int y = (int) r.getPayload().getOrDefault("y", 0);
                        for (String[] bw : bkWords) {
                            int bwY = Integer.parseInt(bw[1]);
                            if (Math.abs(bwY - y) < 30) {
                                String fixed = normalizeBookingCode(bw[0]);
                                if (BK_RE.matcher(fixed).matches()) {
                                    r.getPayload().put("bookingCode", fixed);
                                    r.setSourceTransactionId(fixed);
                                    r.setRawDataHashInput(fixed + "|" + r.getRawAmount());
                                    break;
                                }
                            }
                        }
                    }

                    imgFile.delete();
                    tsvFile.delete();
                    if (bkImgFile != null) bkImgFile.delete();
                }
            } finally {
                tmpDir.delete();
            }
        } catch (Exception e) {
            throw new StatementParseException("OCR_FAILED", "Grab OCR failed: " + e.getMessage());
        }
        if (out.isEmpty()) {
            throw new StatementParseException("NO_ROWS", "Grab OCR produced no recognized trips.");
        }
        return out;
    }

    private File cropColumn(BufferedImage img, int x1, int x2, int yStart, File tmpDir, String name) throws java.io.IOException {
        int w = Math.min(x2, img.getWidth()) - x1;
        if (w <= 0) return null;
        int h = img.getHeight() - yStart;
        if (h <= 0) return null;
        BufferedImage crop = img.getSubimage(x1, yStart, w, h);
        File f = new File(tmpDir, name);
        ImageIO.write(crop, "png", f);
        return f;
    }

    private void runTesseract(File imgFile, File tsvOut) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "tesseract", imgFile.getAbsolutePath(), tsvOut.getAbsolutePath().replace(".tsv", ""),
                "-l", "eng", "--psm", "6", "tsv");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            while (r.readLine() != null) { }
        }
        int rc = p.waitFor();
        if (rc != 0) throw new RuntimeException("tesseract exited with code " + rc);
    }

    private String runTesseractText(File imgFile, String... extraArgs) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("tesseract");
        cmd.add(imgFile.getAbsolutePath());
        cmd.add("stdout");
        for (String arg : extraArgs) cmd.add(arg);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
        }
        p.waitFor();
        return sb.toString();
    }

    private static final Pattern DATE_RE = Pattern.compile(
            "(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(\\d{4})\\s*,?\\s*(\\d{1,2}:\\d{2}\\s*[AP]M)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BK_RE = Pattern.compile("^A-[A-Z0-9]{14,18}$");
    private static final Pattern AMT_RE = Pattern.compile("^\\d+\\.\\d{2}$");

    private static final int[] COL_BOUNDS = {351, 623, 1104, 1711, 2078, 2271};

    private int assignCol(int wordCenterX) {
        for (int i = 0; i < COL_BOUNDS.length; i++) {
            if (wordCenterX < COL_BOUNDS[i]) return i;
        }
        return 6;
    }

    /** Parse full-page TSV: extract words at any non-page level, group into rows, assign columns. */
    private List<RawTransactionData> parseFullPageTsv(File tsvFile, int imgW, int imgH) {
        List<RawTransactionData> out = new ArrayList<>();
        int droppedNoDate = 0, droppedNoBooking = 0, droppedBadAmount = 0;
        List<String[]> words = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(tsvFile))) {
            br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length >= 12 && f[11] != null && !f[11].isBlank() && !"0".equals(f[3])) {
                    int left = Integer.parseInt(f[6]);
                    int width = Integer.parseInt(f[8]);
                    int top = Integer.parseInt(f[7]);
                    words.add(new String[]{f[11], String.valueOf(left), String.valueOf(left + width),
                            String.valueOf(top), f[9]});
                }
            }
        } catch (Exception e) {
            return out;
        }
        if (words.isEmpty()) return out;

        // Group into rows by y-proximity
        words.sort(Comparator.comparingInt(w -> Integer.parseInt(w[3])));
        List<List<String[]>> rows = new ArrayList<>();
        List<String[]> curRow = new ArrayList<>();
        int curBottom = -1;
        for (String[] w : words) {
            int top = Integer.parseInt(w[3]);
            int height = Integer.parseInt(w[4]);
            int bottom = top + height;
            if (curRow.isEmpty() || top <= curBottom + 12) {
                curRow.add(w);
                curBottom = Math.max(curBottom, bottom);
            } else {
                rows.add(curRow);
                curRow = new ArrayList<>();
                curRow.add(w);
                curBottom = bottom;
            }
        }
        if (!curRow.isEmpty()) rows.add(curRow);

        for (List<String[]> row : rows) {
            // Skip header row (words at y < 620)
            int rowY = row.stream().mapToInt(w -> Integer.parseInt(w[3])).min().orElse(0);
            if (rowY < 620) continue;

            @SuppressWarnings("unchecked")
            List<String>[] cols = new ArrayList[7];
            for (int i = 0; i < 7; i++) cols[i] = new ArrayList<>();
            for (String[] w : row) {
                int left = Integer.parseInt(w[1]);
                int right = Integer.parseInt(w[2]);
                int center = (left + right) / 2;
                cols[assignCol(center)].add(w[0]);
            }

            String dtStr = String.join(" ", cols[0]).trim();
            String bkRaw = String.join("", cols[1]).replace(" ", "");
            String currency = String.join(" ", cols[5]).trim().toUpperCase();
            String amount = String.join("", cols[6]).replace(" ", "");

            // Parse date
            Matcher dtm = DATE_RE.matcher(dtStr);
            if (!dtm.find()) { droppedNoDate++; continue; }
            String normalizedDate = dtm.group(1) + " " + dtm.group(2) + " " + dtm.group(3)
                    + ", " + dtm.group(4).replace(" ", "");

            // Normalize booking code
            String bkCode = normalizeBookingCode(bkRaw);
            if (!BK_RE.matcher(bkCode).matches()) { droppedNoBooking++; continue; }

            // Validate currency and amount
            if (!currency.equals("MYR") && !currency.equals("THB")) currency = "MYR";
            if (!AMT_RE.matcher(amount).matches()) { droppedBadAmount++; continue; }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dateTime", normalizedDate);
            payload.put("bookingCode", bkCode);
            payload.put("currency", currency);
            payload.put("amount", amount);
            payload.put("y", rowY);

            RawTransactionData d = new RawTransactionData();
            d.setSourceTransactionId(bkCode);
            d.setRawDate(normalizedDate);
            d.setRawDescription("Grab " + bkCode);
            d.setRawAmount(amount);
            d.setRawCurrency(currency);
            d.setPayload(payload);
            d.setRawDataHashInput(bkCode + "|" + amount);
            out.add(d);
        }
        if (droppedNoDate + droppedNoBooking + droppedBadAmount > 0) {
            log.info("Grab OCR page: {} trips kept, {} row groups dropped (no date: {}, no booking: {}, bad amount: {})",
                    out.size(), droppedNoDate + droppedNoBooking + droppedBadAmount,
                    droppedNoDate, droppedNoBooking, droppedBadAmount);
        }
        return out;
    }

    /** Normalize common OCR misreads in booking codes. */
    private String normalizeBookingCode(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9-]", "").toUpperCase();
        // Common OCR errors: O→0, I→1, l→1, etc. but only after A-
        if (!s.startsWith("A-")) {
            if (s.length() >= 14) s = "A-" + s;
        }
        // Fix specific known patterns
        s = s.replace("A= ", "A-").replace("A=", "A-");
        return s;
    }

    private List<String[]> parseColumnLines(String text) {
        List<String[]> words = new ArrayList<>();
        String[] lines = text.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.isEmpty()) {
                words.add(new String[]{trimmed, String.valueOf(i * 18)});
            }
        }
        return words;
    }

    private boolean tesseractAvailable() {
        try {
            Process p = new ProcessBuilder("tesseract", "--version").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                p.waitFor();
                return line != null && line.toLowerCase().contains("tesseract");
            }
        } catch (Exception e) {
            return false;
        }
    }
}
