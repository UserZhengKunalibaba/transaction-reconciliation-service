package com.transactionprocessor.transactionprocessor.parser;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TNG eWallet PDF is user-password encrypted (Standard V2, empty pw fails).
 * Strategy: try "" then STATEMENT_TNG_PASSWORD; if still locked → ERROR with guidance.
 * If unlocked but image-only → same NEED_OCR branch as Grab.
 * Wallet account for all TNG rows = Cash (applied at normalization).
 *
 * <p>Direction: the statement prints unsigned RM amounts, so the transaction
 * {@code type} determines the sign — spends (Payment, DuitNow QR,
 * Transfer to Wallet, sent Money Packet) are emitted as negative raw amounts,
 * everything else (Receive from Wallet, Refund, Cashback,
 * DUITNOW_RECEIVEFROM, received Money Packet, unknown) stays positive. The
 * normalizer's sign rule then yields EXPENSE/INCOME directly.
 */
@Component
public class TngParser implements StatementParser {

    private final PdfTextExtractor pdf;
    private final AppConfig config;

    public TngParser(PdfTextExtractor pdf, AppConfig config) {
        this.pdf = pdf;
        this.config = config;
    }

    @Override
    public String version() { return "tng-v1"; }

    @Override
    public boolean supports(StatementFileDescriptor file) {
        String n = file.getFilename().toLowerCase();
        if (n.endsWith(".pdf") && (n.contains("tng") || n.contains("touch") || n.contains("ewallet") || n.contains("e-wallet"))) return true;
        return false;
    }

    @Override
    public List<RawTransactionData> parse(StatementFileDescriptor file) throws StatementParseException {
        File f = file.getPath().toFile();
        try {
            PdfTextExtractor.PdfText t = pdf.extract(f, config.getTngPassword());
            if (pdf.isScanned(t.text)) {
                throw new StatementParseException("NEED_OCR",
                        "TNG statement has no text layer after decryption. Enable OCR (OCR_ENABLED=true + tesseract) or export CSV from TNG app.");
            }
            return parseText(t.text);
        } catch (java.io.IOException e) {
            throw new StatementParseException("ENCRYPTED",
                    "TNG PDF is password-protected (user password required). Set TNG_PDF_PASSWORD env to the PDF password, "
                    + "or export CSV from the TNG eWallet app and upload it via the Statements page. Original error: " + e.getMessage());
        }
    }

    List<RawTransactionData> parseText(String text) {
        List<RawTransactionData> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;

        String[] lines = text.split("\\r?\\n");
        java.util.List<String> block = new ArrayList<>();
        String pendingDate = null;

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("=====") || trimmed.startsWith("*This is a system")
                    || trimmed.startsWith("TNG WALLET") || trimmed.startsWith("Date Status")
                    || trimmed.equals("Generated Date & Time") || trimmed.equals("Transaction Period")
                    || trimmed.equals("Registered Name") || trimmed.equals("Wallet ID")
                    || trimmed.equals("Account Status") || trimmed.matches("^\\d{1,2} \\w{3} \\d{4}$")
                    || trimmed.equals("Active") || trimmed.matches("^\\d{10,}$")) {
                continue;
            }
            if (trimmed.startsWith("+603 5022")) continue;

            java.util.regex.Matcher dateStart = DATE_START.matcher(trimmed);
            if (dateStart.find()) {
                if (pendingDate != null && !block.isEmpty()) {
                    processBlock(block, pendingDate, out);
                }
                pendingDate = dateStart.group(0).strip();
                String rest = trimmed.substring(dateStart.end()).strip();
                block.clear();
                if (!rest.isEmpty()) block.add(rest);
            } else {
                block.add(trimmed);
            }
        }
        if (pendingDate != null && !block.isEmpty()) {
            processBlock(block, pendingDate, out);
        }
        return out;
    }

    private static final java.util.regex.Pattern DATE_START =
            java.util.regex.Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{4}");

    private static final java.util.regex.Pattern AMOUNT_LINE =
            java.util.regex.Pattern.compile("RM([\\d,]+\\.\\d{2})\\s+RM([\\d,]+\\.\\d{2})");

    private void processBlock(java.util.List<String> lines, String dateStr,
                              java.util.List<RawTransactionData> out) {
        if (lines.isEmpty()) return;

        String type = "";
        String description = "";
        String amount = "";
        String walletBalance = "";
        StringBuilder descParts = new StringBuilder();

        for (String line : lines) {
            java.util.regex.Matcher amt = AMOUNT_LINE.matcher(line);
            if (amt.find()) {
                // The type usually shares this line (PDFBox merges table columns),
                // so scan it too when nothing was detected yet.
                if (type.isEmpty()) {
                    String hit = detectType(line);
                    if (!hit.isEmpty()) type = hit;
                }
                amount = amt.group(1);
                walletBalance = amt.group(2);
                // Still capture the text before RM for foreign currency info
                String before = line.substring(0, amt.start()).trim();
                if (!before.isEmpty()) descParts.append(descParts.length() > 0 ? " " : "").append(before);
                continue;
            }
            if (amount.isEmpty()) {
                String hit = detectType(line);
                if (!hit.isEmpty()) {
                    type = hit;
                    // Money Packet lines stay in the description as well: the
                    // merchant/payee extractor keys off their leading words, so
                    // consuming the line would silently change payees (and
                    // therefore dedup keys) for reimports.
                    if (!"Money Packet".equals(hit)) continue;
                }
                java.util.regex.Matcher rm = java.util.regex.Pattern.compile("^RM([\\d,]+\\.\\d{2})\\s+RM([\\d,]+\\.\\d{2})$").matcher(line);
                if (rm.find()) { amount = rm.group(1); walletBalance = rm.group(2); continue; }
            }
            descParts.append(descParts.length() > 0 ? " " : "").append(line);
        }

        description = descParts.toString().trim();
        if (description.isEmpty()) description = type;
        if (amount.isEmpty()) return;

        // Unsigned in the statement: spends carry a negative raw amount so the
        // normalizer classifies them as EXPENSE (verified against wallet-balance
        // movement, e.g. 117.90 − 9.54 = 108.36 for Transfer to Wallet).
        if (isMoneyOut(type) || isSentMoneyPacket(type, description)) amount = "-" + amount;

        // Clean up description: remove "Success" prefix if present
        if (description.startsWith("Success ")) description = description.substring(8);

        String fxDesc = extractForeignCurrency(description);
        if (fxDesc != null) description = fxDesc;

        String sourceId = dateStr + "|" + amount + "|" + type;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("date", dateStr);
        payload.put("type", type);
        payload.put("description", description);
        payload.put("walletBalance", walletBalance);

        RawTransactionData d = new RawTransactionData();
        d.setSourceTransactionId(sourceId);
        d.setRawDate(dateStr);
        d.setRawDescription(description);
        d.setRawAmount(amount);
        d.setRawCurrency("MYR");
        d.setPayload(payload);
        d.setRawDataHashInput(sourceId);
        out.add(d);
    }

    /**
     * Type keyword on a block line (after stripping a "Success " prefix).
     * Returns "" when the line carries no type.
     */
    static String detectType(String line) {
        String l = line.startsWith("Success ") ? line.substring(8) : line;
        if (l.startsWith("Payment")) return "Payment";
        if (l.startsWith("DuitNow QR")) return "DuitNow QR";
        if (l.startsWith("Cashback")) return "Cashback";
        if (l.startsWith("Refund")) return "Refund";
        if (l.startsWith("Transfer to Wallet")) return "Transfer to Wallet";
        if (l.startsWith("Receive from Wallet")) return "Receive from Wallet";
        if (l.startsWith("Money Packet")) return "Money Packet";
        if (l.startsWith("DUITNOW_RECEIVEFROM") || l.startsWith("DUITNOW_RECEI")) return "DUITNOW_RECEIVEFROM";
        return "";
    }

    /**
     * Money-out types decrease the wallet balance. Unknown/empty types default to
     * money-in, preserving previous behavior; the type stays in the payload and
     * description for review.
     */
    static boolean isMoneyOut(String type) {
        return "Payment".equals(type)
                || "DuitNow QR".equals(type)
                || "Transfer to Wallet".equals(type);
    }

    /**
     * Sent money packets ("Random for N Receivers …", reference soup, no
     * {@code Received} marker) leave the wallet; received ones
     * ({@code … Received …}) stay positive. The statement prints no separate
     * sent/received type — only the reference text distinguishes them — so
     * this check runs on the block description, not the type alone. A blank
     * description defaults to money-in, like unknown types.
     */
    static boolean isSentMoneyPacket(String type, String description) {
        if (!"Money Packet".equals(type)) return false;
        if (description == null || description.isBlank()) return false;
        String d = description.toUpperCase(java.util.Locale.ROOT);
        return !d.contains("RECEIVED") && !d.contains("VEFROM");
    }

    private String extractForeignCurrency(String desc) {        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(([^)]+)\\)\\s+([A-Z]{3})\\s+([\\d.]+)").matcher(desc);
        if (m.find()) {
            String sender = m.group(1);
            String curr = m.group(2);
            String origAmt = m.group(3);
            return sender + " [" + curr + " " + origAmt + "]";
        }
        return null;
    }
}
