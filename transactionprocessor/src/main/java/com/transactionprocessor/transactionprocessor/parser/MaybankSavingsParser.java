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
 * Maybank Savings Account statements.
 * Real PDFBox layout: one line per txn "01/02/26 PYMT FROM A/C 24.93- 25,034.00"
 * followed by indented merchant lines. Legacy pdfminer column splits handled as fallback.
 */
@Component
public class MaybankSavingsParser implements StatementParser {

    private final PdfTextExtractor pdf;
    private final AppConfig config;

    public MaybankSavingsParser(PdfTextExtractor pdf, AppConfig config) {
        this.pdf = pdf;
        this.config = config;
    }

    @Override
    public String version() { return "maybank-sa-v1"; }

    @Override
    public boolean supports(StatementFileDescriptor file) {
        String n = file.getFilename().toLowerCase();
        if (!n.endsWith(".pdf")) return false;
        return n.contains("sa ") || n.contains("sa_") || n.contains("saving")
                || (n.contains("m2u") && !n.contains("bill"));
    }

    @Override
    public List<RawTransactionData> parse(StatementFileDescriptor file) throws StatementParseException {
        try {
            File f = file.getPath().toFile();
            PdfTextExtractor.PdfText t;
            try {
                t = pdf.extract(f, config.getTngPassword());
            } catch (Exception e) {
                throw new StatementParseException("ENCRYPTED", "Maybank SA PDF cannot be opened: " + e.getMessage());
            }
            if (pdf.isScanned(t.text)) {
                throw new StatementParseException("NO_TEXT", "Maybank SA PDF has no text layer: " + file.getFilename());
            }
            return parseText(t.text);
        } catch (StatementParseException e) {
            throw e;
        } catch (Exception e) {
            throw new StatementParseException("Parse failed: " + e.getMessage(), e);
        }
    }

    List<RawTransactionData> parseText(String text) {
        List<RawTransactionData> out = new ArrayList<>();
        Pattern oneLine = Pattern.compile("^(\\d{2}/\\d{2}/\\d{2})\\s+(.+?)\\s+([\\d,]+\\.\\d{2})([+-])\\s+([\\d,]+\\.\\d{2})$");
        String[] lines = text.split("\\r?\\n");
        RawTransactionData pending = null;
        List<String> pendingDesc = null;
        String pendingDate = null, pendingType = null, pendingAmount = null, pendingSign = null, pendingBalance = null;
        for (String raw : lines) {
            String s = raw.strip();
            if (s.isEmpty()) continue;
            if (s.equals("BEGINNING BALANCE")) continue;
            if (isMetaLine(s) && !oneLine.matcher(s).matches()) continue;
            // Bare amounts are never legitimate content in one-line layout (every
            // record carries its amount on the header line): they are beginning /
            // ending balances or statement totals spilling onto their own line.
            if (s.matches("^[\\d,]+\\.\\d{2}$")) continue;
            Matcher m = oneLine.matcher(s);
            if (m.matches()) {
                if (pending != null) {
                    finishPending(out, pending, pendingDesc, pendingDate, pendingType, pendingAmount, pendingSign, pendingBalance);
                    pending = null;
                    pendingDesc = null;
                }
                pendingDate = m.group(1);
                pendingType = m.group(2).strip().replaceAll("\\s+", " ");
                pendingAmount = m.group(3);
                pendingSign = m.group(4);
                pendingBalance = m.group(5);
                pendingDesc = new ArrayList<>();
                pending = new RawTransactionData();
                continue;
            }
            if (pending != null) {
                if (isMetaLine(s)) {
                    finishPending(out, pending, pendingDesc, pendingDate, pendingType, pendingAmount, pendingSign, pendingBalance);
                    pending = null;
                    pendingDesc = null;
                    continue;
                }
                pendingDesc.add(s);
            }
        }
        if (pending != null) {
            finishPending(out, pending, pendingDesc, pendingDate, pendingType, pendingAmount, pendingSign, pendingBalance);
        }
        if (!out.isEmpty()) return out;
        return parseSplitColumns(text);
    }

    private void finishPending(List<RawTransactionData> out, RawTransactionData pending, List<String> descLines,
                               String date, String type, String amount, String sign, String balance) {
        String desc = type + (descLines == null || descLines.isEmpty() ? "" : " | " + String.join(" / ", descLines));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entryDate", date);
        payload.put("entryType", type);
        payload.put("descriptionLines", descLines == null ? new ArrayList<>() : new ArrayList<>(descLines));
        payload.put("amount", amount + sign);
        payload.put("balance", balance);
        payload.put("debit", "-".equals(sign));
        pending.setRawDate(toIso(date));
        pending.setRawDescription(desc + " | " + amount + sign);
        pending.setRawAmount(("-".equals(sign) ? "-" : "") + amount.replace(",", ""));
        pending.setRawCurrency("MYR");
        pending.setPayload(payload);
        pending.setSourceTransactionId(null);
        pending.setRawDataHashInput(date + "|" + type + "|" + String.join("|", descLines == null ? List.of() : descLines) + "|" + amount + sign);
        out.add(pending);
    }

    /** Legacy path for pdfminer-style output where date/type/amount/balance each sit on their own line. */
    List<RawTransactionData> parseSplitColumns(String text) {
        List<RawTransactionData> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        Pattern datePat = Pattern.compile("^(\\d{2}/\\d{2}/\\d{2})$");
        Pattern balPat = Pattern.compile("^([\\d,]+\\.\\d{2})$");
        String pendingDate = null, pendingType = null, pendingAmount = null, pendingSign = null, pendingBalance = null;
        List<String> descLines = new ArrayList<>();
        boolean inRecord = false;
        for (String raw : lines) {
            String s = raw.strip();
            if (s.isEmpty() || s.equals("BEGINNING BALANCE")) continue;
            if (isMetaLine(s) && !datePat.matcher(s).matches()) continue;
            Matcher dm = datePat.matcher(s);
            if (dm.matches()) {
                if (inRecord && pendingAmount == null) {
                    inRecord = false;
                    descLines.clear();
                }
                pendingDate = s;
                inRecord = true;
                pendingType = null;
                pendingAmount = null;
                pendingSign = null;
                pendingBalance = null;
                descLines.clear();
                continue;
            }
            if (!inRecord) {
                if (!out.isEmpty() && !isMetaLine(s) && !datePat.matcher(s).matches()
                        && !Pattern.compile("^([\\d,]+\\.\\d{2})([+-])$").matcher(s.replace(" ", "")).matches()
                        && !Pattern.compile("^([\\d,]+\\.\\d{2})$").matcher(s.replace(" ", "")).matches()) {
                    appendTrailingDesc(out.get(out.size() - 1), s);
                }
                continue;
            }
            String compact = s.replace(" ", "");
            Matcher amStrict = Pattern.compile("^([\\d,]+\\.\\d{2})([+-])$").matcher(compact);
            if (pendingType == null) {
                pendingType = s;
                continue;
            }
            if (pendingAmount == null && amStrict.matches()) {
                pendingAmount = amStrict.group(1);
                pendingSign = amStrict.group(2);
                continue;
            }
            if (pendingAmount != null && pendingBalance == null) {
                Matcher bm = balPat.matcher(compact);
                if (bm.matches()) {
                    pendingBalance = bm.group(1);
                    emit(out, pendingDate, pendingType, descLines, pendingAmount, pendingSign, pendingBalance);
                    inRecord = false;
                    pendingDate = null;
                    pendingType = null;
                    pendingAmount = null;
                    pendingBalance = null;
                    descLines.clear();
                    continue;
                }
            }
            descLines.add(s);
        }
        return out;
    }

    private void emit(List<RawTransactionData> out, String date, String type, List<String> descLines,
                      String amount, String sign, String balance) {
        String desc = type + (descLines.isEmpty() ? "" : " | " + String.join(" / ", descLines));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entryDate", date);
        payload.put("entryType", type);
        payload.put("descriptionLines", new ArrayList<>(descLines));
        payload.put("amount", amount + sign);
        payload.put("balance", balance);
        payload.put("debit", "-".equals(sign));
        RawTransactionData d = new RawTransactionData();
        d.setRawDate(toIso(date));
        d.setRawDescription(desc + " | " + amount + sign);
        d.setRawAmount(("-".equals(sign) ? "-" : "") + amount.replace(",", ""));
        d.setRawCurrency("MYR");
        d.setPayload(payload);
        d.setSourceTransactionId(null);
        d.setRawDataHashInput(date + "|" + type + "|" + String.join("|", descLines) + "|" + amount + sign);
        out.add(d);
    }

    @SuppressWarnings("unchecked")
    private void appendTrailingDesc(RawTransactionData last, String line) {
        String cur = last.getRawDescription();
        last.setRawDescription(cur + " / " + line);
        try {
            Object dl = last.getPayload().get("descriptionLines");
            if (dl instanceof List) ((List<String>) dl).add(line);
        } catch (Exception ignored) {}
        last.setRawDataHashInput(last.getRawDataHashInput() + "|" + line);
    }

    private boolean isMetaLine(String s) {
        // Merchant lines are indented and star-suffixed ("   CHANG YIN CHENG    *");
        // the account-holder footer line is not. Never treat a starred line as the
        // holder footer, or genuine merchant lines are eaten.
        boolean holderLine = s.contains("CHANG YIN CHENG") && !s.strip().endsWith("*");
        return s.contains("Maybank Islamic Berhad") || s.contains("15th Floor") || s.contains("IBS JOHOR")
                || holderLine || s.contains("NO 9803") || s.contains("MUKA")
                || s.contains("TARIKH PENYATA") || s.contains("NOMBOR AKAUN") || s.contains("ACCOUNT")
                || s.contains("PROTECTED BY PIDM") || s.contains("SAVINGS ACCOUNT") || s.contains("URUSNIAGA AKAUN")
                || s.contains("TARIKH MASUK") || s.contains("BUTIR URUSNIAGA") || s.contains("JUMLAH URUSNIAGA")
                || s.contains("BAKI PENYATA") || s.contains("ENTRY DATE") || s.contains("TRANSACTION DESCRIPTION")
                || s.contains("TRANSACTION AMOUNT") || s.contains("STATEMENT BALANCE") || s.contains("Perhation")
                || s.contains("Semua maklumat") || s.contains("若银行") || s.contains("All items and balances")
                || s.contains("Please notify") || s.contains("Sila beritahu") || s.contains("請通知")
                // Statement-note fragments that otherwise glue into the pending record
                // when it spans a page break (footer order varies by page).
                || s.contains("tempoh 21 hari") || s.contains("Faedah merujuk") || s.contains("Interest refers")
                || s.contains("戶號") || s.contains("進支日期") || s.contains("常规账户") || s.contains("书面通知")
                || s.equals("NUMBER")
                // Statement summary block after the last record.
                || s.startsWith("ENDING BALANCE") || s.startsWith("TOTAL CREDIT") || s.startsWith("TOTAL DEBIT")
                || s.matches("^0{6}\\d*$") || s.matches("^[:：]$") || s.matches("^[12]$") || s.equals("BEGINNING BALANCE");
    }

    private String toIso(String ddMMyy) {
        try {
            String[] p = ddMMyy.split("/");
            return String.format("20%s-%s-%s", p[2], p[1], p[0]);
        } catch (Exception e) {
            return ddMMyy;
        }
    }
}
