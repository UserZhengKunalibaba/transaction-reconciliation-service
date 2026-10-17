package com.transactionprocessor.transactionprocessor.parser;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class WechatPayParserTest {

    private final WechatPayParser parser = new WechatPayParser();

    @Test
    void parsesSampleXlsx() throws Exception {
        var res = parser.parse(new StatementFileDescriptor(
                Paths.get("src/test/resources/fixtures/wechat-sample.xlsx"),
                "微信支付账单流水文件.xlsx", 100, "XLSX"));
        assertEquals(2, res.size());
        assertEquals("TEST-WECHAT-ORDER-0001", res.get(0).getSourceTransactionId());
        assertEquals("333.72", res.get(0).getRawAmount());
        assertEquals("CNY", res.get(0).getRawCurrency());
    }
}
