package com.transactionprocessor.transactionprocessor.web;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationControllerTest {

    @Autowired MockMvc mvc;
    @Autowired com.transactionprocessor.transactionprocessor.repo.UnifiedTransactionRepository unifiedRepo;
    @Autowired com.transactionprocessor.transactionprocessor.repo.ReconTransactionRepository reconRepo;

    @BeforeEach
    void clean() {
        reconRepo.deleteAll();
        unifiedRepo.deleteAll();
    }

    private static UnifiedTransaction row(LocalDateTime ts, String payee, String amount, SourceType source) {
        UnifiedTransaction u = new UnifiedTransaction();
        u.setTimestamp(ts);
        u.setPayee(payee);
        u.setAmount(new BigDecimal(amount));
        u.setAmountCurrency("MYR");
        u.setSource(source);
        u.setType(new BigDecimal(amount).signum() < 0 ? "EXPENSE" : "INCOME");
        u.setDescription(payee);
        return u;
    }

    private void seedAndRun() throws Exception {
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 1, 10, 0), "ALP*GANKAIPIN", "-14.12", SourceType.MAYBANK_SA));
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 3, 9, 15), "GRABPAY-EC", "-20.00", SourceType.MAYBANK_SA));
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 4, 11, 58), "GRAB TRIP", "-20.10", SourceType.GRAB));
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 6, 10, 0), "TNG MERCHANT", "-5.00", SourceType.TNG));
        mvc.perform(post("/api/reconciliation/run")).andExpect(status().isOk());
    }

    @Test
    void runReturnsNewCounters() throws Exception {
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 1, 10, 0), "ALP*GANKAIPIN", "-14.12", SourceType.MAYBANK_SA));
        mvc.perform(post("/api/reconciliation/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maybankCopied").value(1))
                .andExpect(jsonPath("$.inserted").value(1));
    }

    @Test
    void runRemovesCnyMatchedAlipayFromCash() throws Exception {
        UnifiedTransaction maybank = row(LocalDateTime.of(2026, 9, 2, 11, 0), "ALIPAY*TAOBAO", "-186.14",
                SourceType.MAYBANK_CC);
        maybank.setOriginalAmount(new BigDecimal("300.76"));
        maybank.setOriginalCurrency("CNY");
        unifiedRepo.save(maybank);
        UnifiedTransaction alipay = row(LocalDateTime.of(2026, 9, 4, 15, 51), "ALIPAY MERCHANT", "-300.76",
                SourceType.ALIPAY);
        alipay.setAmountCurrency("CNY");
        alipay.setOriginalAmount(new BigDecimal("300.76"));
        alipay.setOriginalCurrency("CNY");
        unifiedRepo.save(alipay);
        unifiedRepo.save(row(LocalDateTime.of(2026, 9, 6, 10, 0), "TNG MERCHANT", "-5.00", SourceType.TNG));
        mvc.perform(post("/api/reconciliation/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashMatched").value(1))
                .andExpect(jsonPath("$.cashPlaceholder").value(0));
        mvc.perform(get("/api/reconciliation/transactions").param("account", "Cash").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].payee").value("TNG MERCHANT"));
    }

    @Test
    void subtotalRespectsFiltersAndGroupsByCurrency() throws Exception {
        seedAndRun();
        // 2 Maybank (-14.12, -20.00) + 2 Grab (-20.10, +20.00 top-up) + 1 Cash (-5.00), all MYR.
        mvc.perform(get("/api/reconciliation/transactions/subtotal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(5))
                .andExpect(jsonPath("$.byCurrency.MYR").value(-39.22));
        mvc.perform(get("/api/reconciliation/transactions/subtotal").param("account", "Grab"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.byCurrency.MYR").value(-0.10));
        mvc.perform(get("/api/reconciliation/transactions/subtotal")
                        .param("from", "2026-09-03").param("to", "2026-09-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void transactionsFilterByAccount() throws Exception {
        seedAndRun();
        // 2 Maybank + 2 Grab (trip + top-up income) + 1 Cash = 5
        mvc.perform(get("/api/reconciliation/transactions").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5));
        mvc.perform(get("/api/reconciliation/transactions").param("account", "Grab").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/reconciliation/transactions").param("account", "Maybank").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/reconciliation/transactions").param("account", "Cash").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void transactionsFilterByTimestamp() throws Exception {
        seedAndRun();
        mvc.perform(get("/api/reconciliation/transactions")
                        .param("from", "2026-09-03").param("to", "2026-09-04").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void transactionsSortByTimestamp() throws Exception {
        seedAndRun();
        mvc.perform(get("/api/reconciliation/transactions")
                        .param("size", "20").param("sort", "timestamp,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].payee").value("ALP*GANKAIPIN"));
        mvc.perform(get("/api/reconciliation/transactions")
                        .param("size", "20").param("sort", "timestamp,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].payee").value("TNG MERCHANT"));
    }
}
