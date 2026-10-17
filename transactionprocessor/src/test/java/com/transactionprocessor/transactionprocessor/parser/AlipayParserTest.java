package com.transactionprocessor.transactionprocessor.parser;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlipayParserTest {

    private final AlipayParser parser = new AlipayParser();

    @Test
    void parsesRealHeaderAndRows() throws Exception {
        var res = parser.parse(new StatementFileDescriptor(
                Paths.get("src/test/resources/fixtures/alipay-sample.csv"),
                "支付宝交易明细(20260608-20260908).csv", 100, "CSV"));
        assertEquals(3, res.size());
        assertEquals("TEST-ALIPAY-ORDER-0001", res.get(0).getSourceTransactionId());
        assertEquals("CNY", res.get(0).getRawCurrency());
        assertEquals("197.00", res.get(0).getRawAmount());
        assertTrue(((String) res.get(0).getPayload().get("收/付款方式")).contains("0000"));
    }

    @Test
    void supportsByFilename() {
        assertTrue(parser.supports(new StatementFileDescriptor(Paths.get("/x/支付宝交易明细.csv"), "支付宝交易明细.csv", 1, "CSV")));
        assertTrue(parser.supports(new StatementFileDescriptor(Paths.get("/x/Alipay-bill.csv"), "Alipay-bill.csv", 1, "CSV")));
    }

    @Test
    void decodeGbk() {
        // GBK bytes for 交易时间
        byte[] gbk = {(byte) 0xBD, (byte) 0xBB, (byte) 0xD2, (byte) 0xD7, (byte) 0xCA, (byte) 0xB1, (byte) 0xBC, (byte) 0xE4};
        String s = parser.decode(gbk);
        assertEquals("交易时间", s);
    }
}
