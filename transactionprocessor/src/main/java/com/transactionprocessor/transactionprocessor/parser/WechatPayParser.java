package com.transactionprocessor.transactionprocessor.parser;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WeChat Pay: XLSX single sheet, 17 preamble rows, header row 交易时间|...|备注, 12 data rows.
 * See STATEMENT_ANALYSIS.md §3.
 */
@Component
public class WechatPayParser implements StatementParser {

    @Override
    public String version() { return "wechat-v1"; }

    @Override
    public boolean supports(StatementFileDescriptor file) {
        String n = file.getFilename().toLowerCase();
        if ((n.endsWith(".xlsx") || n.endsWith(".xls")) && (n.contains("微信") || n.contains("wechat") || n.contains("微信支付"))) return true;
        return false;
    }

    @Override
    public List<RawTransactionData> parse(StatementFileDescriptor file) throws StatementParseException {
        try {
            File f = file.getPath().toFile();
            List<RawTransactionData> out = new ArrayList<>();
            try (FileInputStream in = new FileInputStream(f);
                 Workbook wb = WorkbookFactory.create(in)) {
                Sheet sheet = wb.getSheetAt(0);
                int headerRow = -1;
                List<String> headers = new ArrayList<>();
                for (int r = 0; r <= Math.min(40, sheet.getLastRowNum()); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String first = cell(row.getCell(0));
                    if ("交易时间".equals(first.strip())) {
                        headerRow = r;
                        for (int c = 0; c < 11; c++) headers.add(cell(row.getCell(c)).strip());
                        break;
                    }
                }
                if (headerRow < 0) throw new StatementParseException("NO_HEADER", "WeChat XLSX header row (交易时间) not found");
                DataFormatter fmt = new DataFormatter();
                for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    boolean empty = true;
                    for (int c = 0; c < headers.size(); c++) {
                        Cell cell = row.getCell(c);
                        String v = cell == null ? "" : fmt.formatCellValue(cell).strip();
                        if (!v.isEmpty() && !"/".equals(v)) {
                            // keep "/" in payload but don't count toward emptiness except time/id
                        }
                        if (c <= 1 && !v.isEmpty()) empty = false;
                        m.put(headers.get(c), v);
                    }
                    String time = String.valueOf(m.getOrDefault("交易时间", "")).strip();
                    String txnId = String.valueOf(m.getOrDefault("交易单号", "")).strip();
                    if (time.isEmpty() || txnId.isEmpty()) {
                        if (empty) continue;
                        // skip separator/empty rows
                        if (!time.matches(".*\\d{4}-\\d{2}-\\d{2}.*")) continue;
                    }
                    RawTransactionData d = new RawTransactionData();
                    d.setSourceTransactionId(txnId);
                    d.setRawDate(time);
                    d.setRawDescription(String.valueOf(m.getOrDefault("交易对方", "")) + " | "
                            + String.valueOf(m.getOrDefault("商品", "")) + " | "
                            + String.valueOf(m.getOrDefault("收/支", "")) + " "
                            + String.valueOf(m.getOrDefault("金额(元)", "")));
                    d.setRawAmount(String.valueOf(m.getOrDefault("金额(元)", "")));
                    d.setRawCurrency("CNY");
                    d.setPayload(m);
                    d.setRawDataHashInput(txnId + "|" + time + "|" + d.getRawAmount());
                    out.add(d);
                }
            }
            return out;
        } catch (StatementParseException e) {
            throw e;
        } catch (Exception e) {
            throw new StatementParseException("Parse failed: " + e.getMessage(), e);
        }
    }

    private String cell(Cell c) {
        if (c == null) return "";
        return new DataFormatter().formatCellValue(c);
    }
}
