package com.transactionprocessor.transactionprocessor.parser;

import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alipay: GBK CSV with 23-line preamble, header 交易时间,....
 * See STATEMENT_ANALYSIS.md §2.
 */
@Component
public class AlipayParser implements StatementParser {

    @Override
    public String version() { return "alipay-v1"; }

    @Override
    public boolean supports(StatementFileDescriptor file) {
        String n = file.getFilename().toLowerCase();
        if (n.endsWith(".csv") && (n.contains("alipay") || n.contains("支付宝") || n.contains("交易明细"))) return true;
        // content sniff fallback handled by registry order; keep filename-based
        return false;
    }

    @Override
    public List<RawTransactionData> parse(StatementFileDescriptor file) throws StatementParseException {
        try {
            File f = file.getPath().toFile();
            byte[] raw = Files.readAllBytes(f.toPath());
            String text = decode(raw);
            String[] lines = text.split("\\r?\\n");
            int headerIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("交易时间") && lines[i].contains("交易订单号")) {
                    headerIdx = i;
                    break;
                }
            }
            if (headerIdx < 0) throw new StatementParseException("NO_HEADER", "Alipay CSV header not found (expected 交易时间,...交易订单号)");
            String[] headers = splitCsvLine(lines[headerIdx]);
            List<RawTransactionData> out = new ArrayList<>();
            for (int i = headerIdx + 1; i < lines.length; i++) {
                String line = lines[i];
                if (line == null || line.strip().isEmpty()) continue;
                if (line.startsWith("---") || line.startsWith("共") || line.startsWith("收入") || line.startsWith("支出")) continue;
                String[] cols = splitCsvLine(line);
                if (cols.length < 10) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 0; c < headers.length && c < cols.length; c++) {
                    row.put(headers[c].trim(), cols[c].trim());
                }
                String orderId = str(row.get("交易订单号")).replace("\t", "").strip();
                if (orderId.isEmpty()) continue;
                RawTransactionData d = new RawTransactionData();
                d.setSourceTransactionId(orderId);
                d.setRawDate(str(row.get("交易时间")));
                String counterparty = str(row.get("交易对方"));
                String goods = str(row.get("商品说明"));
                String inout = str(row.get("收/支"));
                String amount = str(row.get("金额"));
                d.setRawDescription(counterparty + " | " + goods + " | " + inout + " " + amount);
                d.setRawAmount(amount);
                d.setRawCurrency("CNY");
                d.setPayload(row);
                d.setRawDataHashInput(orderId + "|" + d.getRawDate() + "|" + amount + "|" + inout);
                out.add(d);
            }
            return out;
        } catch (StatementParseException e) {
            throw e;
        } catch (Exception e) {
            throw new StatementParseException("Parse failed: " + e.getMessage(), e);
        }
    }

    String decode(byte[] raw) {
        // Real Alipay export is GBK; test fixtures may be UTF-8. Detect by header presence.
        String utf8 = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        if (utf8.contains("交易时间") && utf8.contains("交易订单号")) return utf8;
        for (String cs : new String[]{"GBK", "GB18030"}) {
            try {
                String s = new String(raw, Charset.forName(cs));
                if (s.contains("交易时间")) return s;
            } catch (Exception ignored) {}
        }
        try {
            return new String(raw, Charset.forName("GBK"));
        } catch (Exception e) {
            return utf8;
        }
    }

    /** Minimal CSV split honoring double quotes. Fields here contain full-width commas so this is safe. */
    String[] splitCsvLine(String line) {
        List<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQ && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQ = !inQ;
                }
            } else if (c == ',' && !inQ) {
                cols.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cols.add(cur.toString());
        return cols.toArray(new String[0]);
    }

    private String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
