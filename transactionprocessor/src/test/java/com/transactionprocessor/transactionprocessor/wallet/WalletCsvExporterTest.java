package com.transactionprocessor.transactionprocessor.wallet;

import com.transactionprocessor.transactionprocessor.domain.ReconTransaction;
import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalletCsvExporterTest {

    private WalletCsvExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new WalletCsvExporter(new WalletFieldMapping());
    }

    private ReconTransaction tx(String timestamp, WalletAccount account, BigDecimal amount,
                                String payee, String desc, String category) {
        ReconTransaction u = new ReconTransaction();
        u.setSourceUnifiedId(1L);
        u.setTimestamp(LocalDateTime.parse(timestamp));
        u.setAccount(account);
        u.setAmount(amount);
        u.setAmountCurrency("MYR");
        u.setSource(SourceType.TNG);
        u.setPayee(payee);
        u.setDescription(desc);
        u.setCategory(category);
        u.setType("EXPENSE");
        return u;
    }

    @Test
    void emptyListProducesOnlyHeader() {
        assertEquals("Date,Account,Amount,Note,Payee,Category\n", exporter.export(List.of()));
    }

    @Test
    void singleRowNoEscaping() {
        String csv = exporter.export(List.of(
                tx("2026-09-04T15:51:53", WalletAccount.Maybank, new BigDecimal("-186.14"),
                        "TAOBAO", "Taobao TRANSACTED CNY 300.76", "Shopping")
        ));
        String[] lines = csv.split("\n");
        assertEquals(2, lines.length);
        assertEquals("Date,Account,Amount,Note,Payee,Category", lines[0]);
        assertTrue(lines[1].contains("2026-09-04"));
        assertTrue(lines[1].contains("Maybank"));
        assertTrue(lines[1].contains("-186.14"));
        assertTrue(lines[1].contains("Shopping"));
        assertTrue(lines[1].contains("TAOBAO"));
    }

    @Test
    void commasInDescriptionAreEscaped() {
        String csv = exporter.export(List.of(
                tx("2026-09-04T10:00:00", WalletAccount.Cash, new BigDecimal("-0.01"),
                        "X", "Item, with comma", "Other")
        ));
        assertTrue(csv.contains("\"Item, with comma\""));
    }

    @Test
    void quotesInDescriptionAreDoubled() {
        String csv = exporter.export(List.of(
                tx("2026-09-04T10:00:00", WalletAccount.Cash, new BigDecimal("-0.01"),
                        "X", "He said \"hello\"", "Other")
        ));
        assertTrue(csv.contains("\"He said \"\"hello\"\"\""));
    }

    @Test
    void newlinesInDescriptionAreEscaped() {
        String csv = exporter.export(List.of(
                tx("2026-09-04T10:00:00", WalletAccount.Cash, new BigDecimal("-0.01"),
                        "X", "Line1\nLine2", "Other")
        ));
        assertTrue(csv.contains("\"Line1\nLine2\""));
    }

    @Test
    void multipleRows() {
        String csv = exporter.export(List.of(
                tx("2026-09-04T10:00:00", WalletAccount.Maybank, new BigDecimal("-186.14"), "A", "Taobao", "Shopping"),
                tx("2026-09-05T10:00:00", WalletAccount.Grab, new BigDecimal("-8.50"), "B", "Grab ride", "Transport"),
                tx("2026-09-06T10:00:00", WalletAccount.Cash, new BigDecimal("-0.01"), "C", "Alipay - coffee", "Food")
        ));
        assertEquals(4, csv.split("\n").length); // header + 3 rows
    }

    @Test
    void chineseCharactersInDescription() {
        String csv = exporter.export(List.of(
                tx("2026-09-04T10:00:00", WalletAccount.Cash, new BigDecimal("-0.01"),
                        "X", "Alipay - 叮咚买菜 蔬菜", "Food")
        ));
        assertTrue(csv.contains("叮咚买菜"));
    }

    @Test
    void rowOrderMatchesInputOrder() {
        String csv = exporter.export(List.of(
                tx("2026-09-06T10:00:00", WalletAccount.Cash, new BigDecimal("-0.01"), "A", "alpha purchase", "Other"),
                tx("2026-09-04T10:00:00", WalletAccount.Maybank, new BigDecimal("-10.00"), "B", "beta purchase", "Other"),
                tx("2026-09-05T10:00:00", WalletAccount.Grab, new BigDecimal("-5.00"), "C", "gamma purchase", "Other")
        ));
        int alphaIdx = csv.indexOf("alpha purchase");
        int betaIdx = csv.indexOf("beta purchase");
        int gammaIdx = csv.indexOf("gamma purchase");
        assertTrue(alphaIdx < betaIdx && betaIdx < gammaIdx);
    }

    @Test
    void largeAmountPreservesPrecision() {
        String csv = exporter.export(List.of(
                tx("2026-09-04T10:00:00", WalletAccount.Maybank, new BigDecimal("-1234567.89"), "A", "Big purchase", "Shopping")
        ));
        assertTrue(csv.contains("-1234567.89"));
    }

    @Test
    void grabTopupIncomeExportsAsPositiveGrabRow() {
        ReconTransaction income = tx("2026-09-03T09:15:00", WalletAccount.Grab,
                new BigDecimal("20.00"), "GRABPAY-EC", "Grab top-up from Maybank: TRANSFER GRABPAY-EC", "Top-up");
        income.setType("INCOME");
        String csv = exporter.export(List.of(income));
        String[] lines = csv.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[1].contains("Grab"));
        assertTrue(lines[1].contains("20.00"));
        assertTrue(lines[1].contains("Grab top-up from Maybank"));
    }
}
