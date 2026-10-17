package com.transactionprocessor.transactionprocessor.wallet;

import com.transactionprocessor.transactionprocessor.domain.ReconTransaction;
import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class WalletFieldMappingTest {

    private WalletFieldMapping mapping;

    @BeforeEach
    void setUp() {
        mapping = new WalletFieldMapping();
    }

    private ReconTransaction tx(String timestamp, WalletAccount account, BigDecimal amount,
                                String payee, String desc, String category) {
        ReconTransaction u = new ReconTransaction();
        u.setSourceUnifiedId(1L);
        u.setTimestamp(LocalDateTime.parse(timestamp));
        u.setAccount(account);
        u.setAmount(amount);
        u.setAmountCurrency("MYR");
        u.setSource(SourceType.MAYBANK_CC);
        u.setPayee(payee);
        u.setDescription(desc);
        u.setCategory(category);
        u.setType("EXPENSE");
        return u;
    }

    @Test
    void csvHeaderHasCorrectColumns() {
        assertArrayEquals(new String[]{"Date", "Account", "Amount", "Note", "Payee", "Category"},
                mapping.csvHeader());
    }

    @Test
    void toRow_mapsStoredFieldsVerbatim() {
        ReconTransaction u = tx("2026-09-04T15:51:53", WalletAccount.Maybank,
                new BigDecimal("-186.14"), "TAOBAO",
                "Taobao TRANSACTED CNY 300.76", "Shopping");
        var row = mapping.toRow(u);
        assertEquals("2026-09-04", row.get("Date"));
        assertEquals("Maybank", row.get("Account"));
        assertEquals("-186.14", row.get("Amount"));
        assertEquals("Taobao TRANSACTED CNY 300.76", row.get("Note"));
        assertEquals("TAOBAO", row.get("Payee"));
        assertEquals("Shopping", row.get("Category"));
    }

    @Test
    void toRow_usesTimestampDatePart() {
        ReconTransaction u = tx("2026-09-04T23:59:59", WalletAccount.Grab,
                new BigDecimal("-8.50"), "GRAB TRIP", "Grab ride", "Transport");
        assertEquals("2026-09-04", mapping.toRow(u).get("Date"));
    }

    @Test
    void toRow_nullsBecomeEmpty() {
        ReconTransaction u = new ReconTransaction();
        var row = mapping.toRow(u);
        assertEquals("", row.get("Date"));
        assertEquals("", row.get("Account"));
        assertEquals("", row.get("Amount"));
        assertEquals("", row.get("Note"));
        assertEquals("", row.get("Payee"));
        assertEquals("", row.get("Category"));
    }

    @Test
    void toRow_zeroAmount() {
        ReconTransaction u = tx("2026-09-04T10:00:00", WalletAccount.Cash,
                BigDecimal.ZERO, "X", "test", "Other");
        assertEquals("0", mapping.toRow(u).get("Amount"));
    }
}
