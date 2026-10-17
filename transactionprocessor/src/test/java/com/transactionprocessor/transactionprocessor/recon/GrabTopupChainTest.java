package com.transactionprocessor.transactionprocessor.recon;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import com.transactionprocessor.transactionprocessor.normalizer.CategoryInferenceService;
import com.transactionprocessor.transactionprocessor.normalizer.ForeignCurrencyFormatter;
import com.transactionprocessor.transactionprocessor.normalizer.NormalizerService;
import com.transactionprocessor.transactionprocessor.parser.RawTransactionData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end chain for a realistic Maybank SA Grab top-up row (parser-shaped
 * input, no Spring, no database): normalizer output must stay a negative
 * Maybank expense whose payee triggers the recon top-up mirror.
 */
class GrabTopupChainTest {

    private final NormalizerService normalizer =
            new NormalizerService(new CategoryInferenceService(), new ForeignCurrencyFormatter());

    private static RawTransactionData saRow(String desc, String amount) {
        RawTransactionData d = new RawTransactionData();
        d.setRawDate("2026-03-05");
        d.setRawDescription(desc);
        d.setRawAmount(amount);
        d.setRawCurrency("MYR");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("entryType", "SALE DEBIT");
        d.setPayload(p);
        return d;
    }

    @Test
    void realShapedGrabpayTopupReachesMirror() {
        UnifiedTransaction u = normalizer.normalizeToUnified(
                saRow("SALE DEBIT | GRABPAY-EC * / PETALING JAYA, MY / SALE DEBIT | 20.00-", "-20.00"),
                SourceType.MAYBANK_SA);
        assertEquals(WalletAccount.Maybank, u.getAccount());
        assertEquals(0, new BigDecimal("-20.00").compareTo(u.getAmount()));
        assertEquals("MYR", u.getAmountCurrency());
        assertTrue(u.getPayee().contains("GRABPAY"),
                "top-up payee must retain GRABPAY for mirror detection, got: " + u.getPayee());
        assertTrue(ReconciliationService.isGrabPayTopup(u));

        // Mirror itself: positive Grab income, exact same timestamp.
        ReconTransactionMirrorCheck.check(u);
    }

    @Test
    void grabFoodSpendDoesNotMirror() {
        UnifiedTransaction u = normalizer.normalizeToUnified(
                saRow("SALE DEBIT | GRAB FOOD * / KUALA LUMPUR, MY / SALE DEBIT | 25.50-", "-25.50"),
                SourceType.MAYBANK_SA);
        assertFalse(ReconciliationService.isGrabPayTopup(u));
    }

    /** Tiny helper to keep the mirror assertions legible. */
    static class ReconTransactionMirrorCheck {
        static void check(UnifiedTransaction u) {
            var mirror = ReconciliationService.grabTopupIncomeOf(u);
            assertEquals(WalletAccount.Grab, mirror.getAccount());
            assertEquals("INCOME", mirror.getType());
            assertEquals(0, new BigDecimal("20.00").compareTo(mirror.getAmount()));
            assertEquals(u.getTimestamp(), mirror.getTimestamp());
        }
    }
}
