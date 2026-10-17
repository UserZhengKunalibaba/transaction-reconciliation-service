package com.transactionprocessor.transactionprocessor.wallet;

import com.transactionprocessor.transactionprocessor.domain.ReconTransaction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ReconTransaction → WalletApp CSV row.
 *
 * <p>WalletApp import columns: {@code Date,Account,Amount,Note,Payee,Category}.
 * The recon table already carries a canonical payee / description / category /
 * account, so no regex payee extraction is needed — the stored values are used
 * verbatim. Date is the calendar date of the canonical timestamp.
 */
@Component
public class WalletFieldMapping {

    public String[] csvHeader() {
        return new String[]{"Date", "Account", "Amount", "Note", "Payee", "Category"};
    }

    public Map<String, String> toRow(ReconTransaction u) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Date", u.getTimestamp() == null ? "" : u.getTimestamp().toLocalDate().toString());
        m.put("Account", u.getAccount() == null ? "" : u.getAccount().name());
        m.put("Amount", u.getAmount() == null ? "" : u.getAmount().toPlainString());
        m.put("Note", u.getDescription() == null ? "" : u.getDescription());
        m.put("Payee", u.getPayee() == null ? "" : u.getPayee());
        m.put("Category", u.getCategory() == null ? "" : u.getCategory());
        return m;
    }
}
