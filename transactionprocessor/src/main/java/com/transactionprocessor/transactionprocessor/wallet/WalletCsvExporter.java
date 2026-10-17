package com.transactionprocessor.transactionprocessor.wallet;

import com.transactionprocessor.transactionprocessor.domain.ReconTransaction;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class WalletCsvExporter {
    private final WalletFieldMapping mapping;

    public WalletCsvExporter(WalletFieldMapping mapping) { this.mapping = mapping; }

    public String export(List<ReconTransaction> txns) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", mapping.csvHeader())).append("\n");
        for (ReconTransaction u : txns) {
            Map<String, String> row = mapping.toRow(u);
            sb.append(esc(row.get("Date"))).append(",")
              .append(esc(row.get("Account"))).append(",")
              .append(esc(row.get("Amount"))).append(",")
              .append(esc(row.get("Note"))).append(",")
              .append(esc(row.get("Payee"))).append(",")
              .append(esc(row.get("Category"))).append("\n");
        }
        return sb.toString();
    }

    private String esc(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
