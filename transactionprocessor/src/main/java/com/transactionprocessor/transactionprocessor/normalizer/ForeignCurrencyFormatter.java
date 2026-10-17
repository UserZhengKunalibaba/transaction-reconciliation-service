package com.transactionprocessor.transactionprocessor.normalizer;

import org.springframework.stereotype.Component;

/** Formats foreign amounts for Wallet descriptions. USD must be US$, never bare $. */
@Component
public class ForeignCurrencyFormatter {

    public String format(String currency, String amount) {
        if (currency == null || amount == null) return "";
        String c = currency.strip().toUpperCase();
        String a = amount.strip().replace(",", "");
        switch (c) {
            case "JPY":
            case "CNY":
            case "RMB":
                // Spec examples: Japan ¥500, China CN¥200.00
                if ("JPY".equals(c)) return "¥" + a;
                return "CN¥" + a;
            case "USD":
                return "US$" + a;
            case "SGD":
                return "S$" + a;
            case "THB":
                return "฿" + a + " (THB)";
            case "MYR":
            case "RM":
                return "RM" + a;
            default:
                return c + " " + a;
        }
    }

    public String wrap(String description, String currency, String amount) {
        if (currency == null || currency.isBlank() || "MYR".equalsIgnoreCase(currency)) return description;
        String f = format(currency, amount);
        if (description != null && description.contains(f)) return description;
        return description + "（" + f + "）";
    }
}
