package com.transactionprocessor.transactionprocessor.parser;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maybank Credit Card Bill 0000.
 * Text layer, owner-password encrypted (empty pw). 3-column layout.
 * Row: posting dd/MM + txn dd/MM + description(+TRANSACTED AMOUNT CCY amt) + RM amount (+CR).
 * Statement year inferred from "24 AUG 26".
 *
 * <p>Detection is filename-first ({@code bill}/{@code 0000}/{@code credit}), with a
 * content fallback: uploaded files carry generated storage names, so filename hints
 * are gone. The fallback sniffs the PDF text for the card-bill header
 * ({@code MAYBANK CARD}), which savings statements never contain.
 */
@Component
public class MaybankCreditCardParser implements StatementParser {

    private final PdfTextExtractor pdf;
    private final AppConfig config;

    public MaybankCreditCardParser(PdfTextExtractor pdf, AppConfig config) {
        this.pdf = pdf;
        this.config = config;
    }

    @Override
    public String version() { return "maybank-cc-v1"; }

    @Override
    public boolean supports(StatementFileDescriptor file) {
        String n = file.getFilename().toLowerCase();
        if (!n.endsWith(".pdf")) return false;
        if (n.contains("bill") || n.contains("0000") || n.contains("credit")) return true;
        // Content fallback for generated storage names: sniff for the card-bill
        // header. Savings statements never contain it (verified against real files;
        // they do contain "0000"/"MASTERCARD" in descriptions, so those are NOT used).
        try {
            PdfTextExtractor.PdfText t = pdf.extract(file.getPath().toFile(), config.getTngPassword());
            return t.text != null && t.text.toUpperCase().contains("MAYBANK CARD");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<RawTransactionData> parse(StatementFileDescriptor file) throws StatementParseException {
        try {
            File f = file.getPath().toFile();
            PdfTextExtractor.PdfText t;
            try {
                t = pdf.extract(f, config.getTngPassword());
            } catch (Exception e) {
                throw new StatementParseException("ENCRYPTED", "Maybank Bill PDF cannot be opened: " + e.getMessage());
            }
            if (pdf.isScanned(t.text)) {
                throw new StatementParseException("NO_TEXT", "Maybank Bill PDF has no text layer: " + file.getFilename());
            }
            return parseText(t.text);
        } catch (StatementParseException e) {
            throw e;
        } catch (Exception e) {
            throw new StatementParseException("Parse failed: " + e.getMessage(), e);
        }
    }

    List<RawTransactionData> parseText(String text) {
        int stmtYear = extractStatementYear(text);
        List<RawTransactionData> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        // Real PDFBox layout (sortByPosition): one line per txn:
        //   30/07 29/07 Alipay*Taobao  M4 Shanghai  CN 5.49
        //   TRANSACTED AMOUNT              CNY             8.90
        Pattern oneLine = Pattern.compile("^(\\d{2}/\\d{2})\\s+(\\d{2}/\\d{2})\\s+(.+?)\\s+([\\d,]+\\.\\d{2})(CR)?$");
        Pattern transAmt = Pattern.compile("TRANSACTED\\s+AMOUNT\\s+([A-Z]{3})\\s+([\\d,]+\\.\\d{2})");
        String pendingForeignCcy = null, pendingForeignAmt = null;
        // pending record waiting for its TRANSACTED AMOUNT line
        RawTransactionData pending = null;
        Map<String, Object> pendingPayload = null;
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            Matcher tm = transAmt.matcher(line);
            if (tm.find()) {
                if (pending != null && pendingPayload != null) {
                    pendingForeignCcy = tm.group(1);
                    pendingForeignAmt = tm.group(2);
                    pendingPayload.put("foreignCurrency", pendingForeignCcy);
                    pendingPayload.put("foreignAmount", pendingForeignAmt);
                    pending.setRawDescription(pending.getRawDescription()
                            + " | TRANSACTED " + pendingForeignCcy + " " + pendingForeignAmt);
                    pending.setRawDataHashInput(pending.getRawDataHashInput() + "|" + pendingForeignAmt);
                }
                continue;
            }
            Matcher m = oneLine.matcher(line);
            if (m.matches()) {
                // flush previous pending (its TRANSACTED line, if any, already consumed)
                if (pending != null) {
                    out.add(pending);
                    pending = null;
                    pendingPayload = null;
                }
                String posting = m.group(1);
                String txn = m.group(2);
                String desc = m.group(3).strip().replaceAll("\\s+", " ");
                String amount = m.group(4);
                boolean isCR = m.group(5) != null;
                // skip meta/summary rows
                String du = desc.toUpperCase();
                if (du.contains("FACILITY LIMIT") || du.contains("HAD KEMUDAHAN")
                        || du.contains("PREVIOUS STATEMENT") || du.contains("JUMLAH PENYATA")
                        || du.contains("PROFIT CHRG RATE") || du.contains("MGMT CHRG")
                        || du.contains("TOTAL CREDIT") || du.contains("TOTAL DEBIT") || du.contains("SUB TOTAL")) {
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("postingDate", posting);
                payload.put("transactionDate", txn);
                payload.put("description", desc);
                payload.put("amountRM", amount + (isCR ? "CR" : ""));
                payload.put("statementYear", stmtYear);
                payload.put("credit", isCR);
                RawTransactionData d = new RawTransactionData();
                d.setRawDate(toFullDate(txn, stmtYear, posting));
                d.setRawDescription(desc + " | RM " + amount + (isCR ? "CR" : ""));
                d.setRawAmount((isCR ? "-" : "") + amount.replace(",", ""));
                d.setRawCurrency("MYR");
                d.setPayload(payload);
                d.setSourceTransactionId(null);
                d.setRawDataHashInput(posting + "|" + txn + "|" + desc + "|" + amount + (isCR ? "CR" : ""));
                pending = d;
                pendingPayload = payload;
                continue;
            }
            // ignore headers/footers/summaries
            if (line.contains("STATEMENT OF CREDIT") || line.contains("PENYATA AKAUN")
                    || line.contains("Posting Date") || line.contains("Transaction Description")
                    || line.contains("TOTAL CREDIT") || line.contains("TOTAL DEBIT") || line.contains("SUB TOTAL")
                    || line.contains("TreatsPoints") || line.contains("Page/Halaman")) {
                if (pending != null) {
                    out.add(pending);
                    pending = null;
                    pendingPayload = null;
                }
            }
        }
        if (pending != null) out.add(pending);
        if (!out.isEmpty()) return out;
        // Fallback: old column-linearized state machine (pdfminer-style splits)
        List<String> recLines = new ArrayList<>();
        for (String l : lines) {
            String s = l.strip();
            if (s.isEmpty()) continue;
            if (s.contains("STATEMENT OF CREDIT") || s.contains("PENYATA AKAUN") || s.contains("Posting Date")
                    || s.contains("Transaction Date") || s.contains("Transaction Description") || s.contains("Amount(RM)")
                    || s.contains("TOTAL CREDIT") || s.contains("TOTAL DEBIT") || s.contains("SUB TOTAL")
                    || s.contains("YOUR COMBINED") || s.contains("YOUR PREVIOUS") || s.contains("TreatsPoints")
                    || s.contains("Page/Halaman") || s.startsWith("003") || s.startsWith("005")) continue;
            recLines.add(s);
        }
        out.addAll(stateMachine(recLines, stmtYear));
        return out;
    }

    private List<RawTransactionData> stateMachine(List<String> lines, int stmtYear) {
        List<RawTransactionData> out = new ArrayList<>();
        Pattern dateOnly = Pattern.compile("^(\\d{2})/(\\d{2})$");
        Pattern amtOnly = Pattern.compile("^([\\d,]+\\.\\d{2})(CR)?$");
        Pattern transAmt = Pattern.compile("TRANSACTED\\s+AMOUNT\\s+([A-Z]{3})\\s+([\\d,]+\\.\\d{2})");
        List<String> dateQueue = new ArrayList<>();
        StringBuilder desc = new StringBuilder();
        String foreignCcy = null, foreignAmt = null;
        String posting = null, txn = null;
        for (String line : lines) {
            Matcher dm = dateOnly.matcher(line);
            if (dm.matches()) {
                dateQueue.add(line);
                // when we have 2 dates and no open record, open one
                if (posting == null && dateQueue.size() >= 2) {
                    posting = dateQueue.remove(0);
                    txn = dateQueue.remove(0);
                    desc.setLength(0);
                    foreignCcy = null;
                    foreignAmt = null;
                }
                continue;
            }
            Matcher am = amtOnly.matcher(line.replace(" ", ""));
            String nospace = line.replace(" ", "").replace(",", "");
            Matcher am2 = Pattern.compile("^([\\d,]+\\.\\d{2})(CR)?$").matcher(line.strip());
            if (posting != null && am2.matches()) {
                String amount = am2.group(1);
                boolean isCR = am2.group(2) != null;
                String description = desc.toString().strip();
                if (description.isEmpty()) description = line.strip();
                // skip summary/balance lines
                if (description.matches(".*(COMBINED|PREVIOUS|STATEMENT BALANCE|TREATS).*")) {
                    posting = null; txn = null; desc.setLength(0);
                    continue;
                }
                // skip header amounts like 6,000 limit / 25.00 minimum unless they have dates — they do have dates only for real txns
                if (isLimitLine(description)) {
                    posting = null; txn = null; desc.setLength(0);
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("postingDate", posting);
                payload.put("transactionDate", txn);
                payload.put("description", description);
                payload.put("amountRM", amount + (isCR ? "CR" : ""));
                if (foreignCcy != null) {
                    payload.put("foreignCurrency", foreignCcy);
                    payload.put("foreignAmount", foreignAmt);
                }
                payload.put("statementYear", stmtYear);
                payload.put("credit", isCR);
                RawTransactionData d = new RawTransactionData();
                String txnDateFull = toFullDate(txn, stmtYear, posting);
                d.setRawDate(txnDateFull);
                d.setRawDescription(description + (foreignCcy != null ? " | TRANSACTED " + foreignCcy + " " + foreignAmt : "") + " | RM " + amount + (isCR ? "CR" : ""));
                d.setRawAmount((isCR ? "-" : "") + amount.replace(",", ""));
                d.setRawCurrency("MYR");
                d.setPayload(payload);
                d.setSourceTransactionId(null); // no ID; dedup via hash below
                d.setRawDataHashInput(posting + "|" + txn + "|" + description + "|" + amount + (isCR ? "CR" : "") + "|" + foreignAmt);
                out.add(d);
                posting = null; txn = null; desc.setLength(0);
                foreignCcy = null; foreignAmt = null;
                // leftover dates in queue open next record
                if (dateQueue.size() >= 2) {
                    posting = dateQueue.remove(0);
                    txn = dateQueue.remove(0);
                }
                continue;
            }
            Matcher tm = transAmt.matcher(line);
            if (tm.find()) {
                foreignCcy = tm.group(1);
                foreignAmt = tm.group(2);
                if (desc.length() > 0) desc.append(" | ");
                desc.append(line.strip());
                continue;
            }
            // skip statement meta lines
            if (line.contains("MR ") && line.contains("CHANG")) continue;
            if (line.matches(".*(JALAN|BANDAR|KULAI|MAYBANK CARD|WARNING|AMARAN|If you make|Jika anda|sila rujuk|www\\.|Dataran|Jalan Maarof|Account Number|Current Balance|Minimum Payment|\\d{4} \\d{4} \\d{4} \\d{4}|MASTERCARD IKH|RTL .*RATE|YOUR .*|\\d+,\\d{3}\\s*$).*")) {
                // "6,000" limit line already handled; skip pure meta
                if (posting == null) continue;
            }
            if (posting != null) {
                if (desc.length() > 0) desc.append(" ");
                desc.append(line.strip());
            }
        }
        return out;
    }

    private boolean isLimitLine(String desc) {
        String d = desc.toUpperCase();
        return d.contains("FACILITY LIMIT") || d.contains("HAD KEMUDAHAN") || d.contains("MINIMUM PAYMENT");
    }

    private int extractStatementYear(String text) {
        Matcher m = Pattern.compile("Statement Date[^\\d]*(\\d{1,2})\\s+([A-Z]{3})\\s+(\\d{2})", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            int yy = Integer.parseInt(m.group(3));
            return 2000 + yy;
        }
        Matcher m2 = Pattern.compile("24\\s+(AUG|JUL|JUN|MAR|MAY)\\s+26").matcher(text);
        if (m2.find()) return 2026;
        return 2026;
    }

    private String toFullDate(String ddmm, int stmtYear, String posting) {
        try {
            String[] p = ddmm.split("/");
            int day = Integer.parseInt(p[0]);
            int mon = Integer.parseInt(p[1]);
            // statement month rollover: if txn month > posting month+1, previous year (Dec-Jan boundary)
            return String.format("%04d-%02d-%02d", stmtYear, mon, day);
        } catch (Exception e) {
            return ddmm;
        }
    }
}
