package com.transactionprocessor.transactionprocessor.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlipayParserExtendedTest {

    private AlipayParser parser;

    @BeforeEach
    void setUp() {
        parser = new AlipayParser();
    }

    // --- supports ---

    @Test
    void supportsAlipayFilename() {
        assertTrue(parser.supports(new StatementFileDescriptor(
                Paths.get("/x/支付宝交易明细.csv"), "支付宝交易明细.csv", 1, "CSV")));
    }

    @Test
    void supportsEnglishAlipayFilename() {
        assertTrue(parser.supports(new StatementFileDescriptor(
                Paths.get("/x/Alipay-bill.csv"), "Alipay-bill.csv", 1, "CSV")));
    }

    @Test
    void supports交易明细Filename() {
        assertTrue(parser.supports(new StatementFileDescriptor(
                Paths.get("/x/交易明细.csv"), "交易明细.csv", 1, "CSV")));
    }

    @Test
    void doesNotSupportXlsx() {
        assertFalse(parser.supports(new StatementFileDescriptor(
                Paths.get("/x/alipay.xlsx"), "alipay.xlsx", 1, "XLSX")));
    }

    @Test
    void doesNotSupportRandomCsv() {
        assertFalse(parser.supports(new StatementFileDescriptor(
                Paths.get("/x/data.csv"), "data.csv", 1, "CSV")));
    }

    // --- version ---

    @Test
    void versionIsAlipayV1() {
        assertEquals("alipay-v1", parser.version());
    }

    // --- decode ---

    @Test
    void decodeUtf8() {
        byte[] utf8 = "交易时间,交易订单号\n2026-09-04,12345".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String result = parser.decode(utf8);
        assertTrue(result.contains("交易时间"));
        assertTrue(result.contains("12345"));
    }

    @Test
    void decodeGbk() {
        byte[] gbk = {(byte) 0xBD, (byte) 0xBB, (byte) 0xD2, (byte) 0xD7, (byte) 0xCA, (byte) 0xB1, (byte) 0xBC, (byte) 0xE4};
        String s = parser.decode(gbk);
        assertEquals("交易时间", s);
    }

    @Test
    void decodeEmpty() {
        byte[] empty = new byte[0];
        String s = parser.decode(empty);
        assertNotNull(s);
    }

    // --- splitCsvLine ---

    @Test
    void splitCsvLineSimple() {
        String[] parts = parser.splitCsvLine("a,b,c");
        assertArrayEquals(new String[]{"a", "b", "c"}, parts);
    }

    @Test
    void splitCsvLineWithQuotes() {
        String[] parts = parser.splitCsvLine("\"a,b\",c");
        assertArrayEquals(new String[]{"a,b", "c"}, parts);
    }

    @Test
    void splitCsvLineEscapedQuotes() {
        String[] parts = parser.splitCsvLine("\"a\"\"b\",c");
        assertArrayEquals(new String[]{"a\"b", "c"}, parts);
    }

    @Test
    void splitCsvLineEmptyFields() {
        String[] parts = parser.splitCsvLine("a,,c");
        assertArrayEquals(new String[]{"a", "", "c"}, parts);
    }

    @Test
    void splitCsvLineSingleField() {
        String[] parts = parser.splitCsvLine("hello");
        assertArrayEquals(new String[]{"hello"}, parts);
    }

    // --- parse real file ---

    @Test
    void parsesRealFile() throws Exception {
        var res = parser.parse(new StatementFileDescriptor(
                Paths.get("src/test/resources/fixtures/alipay-sample.csv"),
                "支付宝交易明细(20260608-20260908).csv", 100, "CSV"));
        assertEquals(3, res.size());
        assertEquals("TEST-ALIPAY-ORDER-0001", res.get(0).getSourceTransactionId());
        assertEquals("CNY", res.get(0).getRawCurrency());
        assertEquals("197.00", res.get(0).getRawAmount());
    }

    @Test
    void parsesDescriptionFormat() throws Exception {
        var res = parser.parse(new StatementFileDescriptor(
                Paths.get("src/test/resources/fixtures/alipay-sample.csv"),
                "支付宝交易明细(20260608-20260908).csv", 100, "CSV"));
        // Description should be: counterparty | goods | inout amount
        String desc = res.get(0).getRawDescription();
        assertTrue(desc.contains("|"), "Description should contain pipe separator");
    }

    @Test
    void payloadContainsExpectedKeys() throws Exception {
        var res = parser.parse(new StatementFileDescriptor(
                Paths.get("src/test/resources/fixtures/alipay-sample.csv"),
                "支付宝交易明细(20260608-20260908).csv", 100, "CSV"));
        assertFalse(res.get(0).getPayload().isEmpty());
    }

    @Test
    void rawDataHashInputContainsOrderId() throws Exception {
        var res = parser.parse(new StatementFileDescriptor(
                Paths.get("src/test/resources/fixtures/alipay-sample.csv"),
                "支付宝交易明细(20260608-20260908).csv", 100, "CSV"));
        assertTrue(res.get(0).getRawDataHashInput().contains("TEST-ALIPAY-ORDER-0001"));
    }
}
