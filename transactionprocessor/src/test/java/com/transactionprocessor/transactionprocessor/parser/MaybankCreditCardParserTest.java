package com.transactionprocessor.transactionprocessor.parser;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaybankCreditCardParserTest {

    private MaybankCreditCardParser parser() {
        AppConfig c = new AppConfig();
        return new MaybankCreditCardParser(new PdfTextExtractor(), c);
    }

    @Test
    void parsesBillRowsWithForeignAmount() {
        String text = """
                Statement Date/ 24 AUG 26
                Posting Date / Transaction Date / Transaction Description / Amount(RM)
                25/07
                25/07
                RTL PROFIT CHRG RATE 17.00%
                Alipay*Taobao       M4
                TRANSACTED AMOUNT              CNY             8.90
                Shanghai      CN
                5.49
                30/07
                29/07
                Alipay*Taobao       M4
                TRANSACTED AMOUNT              CNY           300.76
                Shanghai      CN
                186.14
                """;
        var rows = parser().parseText(text);
        assertTrue(rows.size() >= 2, "expected >=2 rows, got " + rows.size());
        assertEquals("CNY", String.valueOf(rows.get(0).getPayload().get("foreignCurrency")));
    }

    @Test
    void supportsBillFilenames() {
        var d = new StatementFileDescriptor(java.nio.file.Paths.get("/s/M2U Bill 0000 Aug 2026.pdf"),
                "M2U Bill 0000 Aug 2026.pdf", 1, "PDF");
        assertTrue(parser().supports(d));
    }
}
