package com.transactionprocessor.transactionprocessor.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WechatPayParserExtendedTest {

    private WechatPayParser parser;

    @BeforeEach
    void setUp() {
        parser = new WechatPayParser();
    }

    // --- supports ---

    @Test
    void supportsWechatXlsx() {
        assertTrue(parser.supports(new StatementFileDescriptor(
                Paths.get("/x/微信支付账单流水文件.xlsx"), "微信支付账单流水文件.xlsx", 1, "XLSX")));
    }

    @Test
    void supportsEnglishWechatXlsx() {
        assertTrue(parser.supports(new StatementFileDescriptor(
                Paths.get("/x/wechat-bill.xlsx"), "wechat-bill.xlsx", 1, "XLSX")));
    }

    @Test
    void supportsXls() {
        assertTrue(parser.supports(new StatementFileDescriptor(
                Paths.get("/x/微信支付.xls"), "微信支付.xls", 1, "XLS")));
    }

    @Test
    void doesNotSupportCsv() {
        assertFalse(parser.supports(new StatementFileDescriptor(
                Paths.get("/x/wechat.csv"), "wechat.csv", 1, "CSV")));
    }

    @Test
    void doesNotSupportRandomXlsx() {
        assertFalse(parser.supports(new StatementFileDescriptor(
                Paths.get("/x/data.xlsx"), "data.xlsx", 1, "XLSX")));
    }

    // --- version ---

    @Test
    void versionIsWechatV1() {
        assertEquals("wechat-v1", parser.version());
    }

    // --- parse real file ---

    @Test
    void parsesRealFile() throws Exception {
        var res = parser.parse(new StatementFileDescriptor(
                Paths.get("src/test/resources/fixtures/wechat-sample.xlsx"),
                "微信支付账单流水文件.xlsx", 100, "XLSX"));
        assertEquals(2, res.size());
        assertEquals("TEST-WECHAT-ORDER-0001", res.get(0).getSourceTransactionId());
        assertEquals("333.72", res.get(0).getRawAmount());
        assertEquals("CNY", res.get(0).getRawCurrency());
    }

    @Test
    void parsesDescriptionFormat() throws Exception {
        var res = parser.parse(new StatementFileDescriptor(
                Paths.get("src/test/resources/fixtures/wechat-sample.xlsx"),
                "微信支付账单流水文件.xlsx", 100, "XLSX"));
        String desc = res.get(0).getRawDescription();
        assertTrue(desc.contains("|"), "Description should contain pipe separators");
    }

    @Test
    void rawDataHashInputContainsTxnId() throws Exception {
        var res = parser.parse(new StatementFileDescriptor(
                Paths.get("src/test/resources/fixtures/wechat-sample.xlsx"),
                "微信支付账单流水文件.xlsx", 100, "XLSX"));
        assertTrue(res.get(0).getRawDataHashInput().contains("TEST-WECHAT-ORDER-0001"));
    }

    @Test
    void payloadContainsExpectedKeys() throws Exception {
        var res = parser.parse(new StatementFileDescriptor(
                Paths.get("src/test/resources/fixtures/wechat-sample.xlsx"),
                "微信支付账单流水文件.xlsx", 100, "XLSX"));
        assertFalse(res.get(0).getPayload().isEmpty());
    }

    @Test
    void rawDateIsNonEmpty() throws Exception {
        var res = parser.parse(new StatementFileDescriptor(
                Paths.get("src/test/resources/fixtures/wechat-sample.xlsx"),
                "微信支付账单流水文件.xlsx", 100, "XLSX"));
        assertFalse(res.get(0).getRawDate().isEmpty());
    }

    @Test
    void allTransactionsHaveSameCurrency() throws Exception {
        var res = parser.parse(new StatementFileDescriptor(
                Paths.get("src/test/resources/fixtures/wechat-sample.xlsx"),
                "微信支付账单流水文件.xlsx", 100, "XLSX"));
        for (var txn : res) {
            assertEquals("CNY", txn.getRawCurrency());
        }
    }

    @Test
    void allTransactionsHaveNonEmptyIds() throws Exception {
        var res = parser.parse(new StatementFileDescriptor(
                Paths.get("src/test/resources/fixtures/wechat-sample.xlsx"),
                "微信支付账单流水文件.xlsx", 100, "XLSX"));
        for (var txn : res) {
            assertFalse(txn.getSourceTransactionId().isEmpty());
        }
    }
}
