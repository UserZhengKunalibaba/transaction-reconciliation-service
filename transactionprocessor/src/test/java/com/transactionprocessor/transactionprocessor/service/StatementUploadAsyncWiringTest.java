package com.transactionprocessor.transactionprocessor.service;

import com.transactionprocessor.transactionprocessor.config.AsyncConfig;
import com.transactionprocessor.transactionprocessor.repo.UnifiedTransactionRepository;
import com.transactionprocessor.transactionprocessor.upload.StatementBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the async handoff is actually wired: the listener is a Spring async proxy,
 * the executor bean exists, and driving the listener with real beans inserts rows.
 * (The method is invoked directly so the test stays deterministic — the thread hop
 * itself is Spring's @Async machinery, verified via the proxy + annotation.)
 */
@SpringBootTest
class StatementUploadAsyncWiringTest {

    static final Path uploadRoot;

    static {
        try {
            uploadRoot = Files.createTempDirectory("upload-async-wiring-test");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    StatementUploadListener listener;

    @Autowired
    UnifiedTransactionRepository unifiedRepo;

    @Autowired
    org.springframework.context.ApplicationContext context;

    @DynamicPropertySource
    static void uploadDir(DynamicPropertyRegistry registry) {
        registry.add("app.statement-upload-directory", () -> uploadRoot.toString());
    }

    @BeforeEach
    void clean() throws IOException {
        unifiedRepo.deleteAll();
        for (String b : List.of("MAYBANK_SA", "MAYBANK_CC", "GRAB", "TNG", "ALIPAY", "WECHAT")) {
            Files.createDirectories(uploadRoot.resolve(b));
        }
    }

    @Test
    void listenerIsAsyncProxiedWithDedicatedExecutor() throws Exception {
        assertTrue(AopUtils.isAopProxy(listener), "listener must be a Spring async proxy");
        Method m = StatementUploadListener.class.getMethod("onUploadCompleted", StatementUploadedEvent.class);
        Async async = m.getAnnotation(Async.class);
        assertNotNull(async, "handler must run via @Async");
        assertEquals(AsyncConfig.PROCESSING_EXECUTOR, async.value());
        assertTrue(context.containsBean(AsyncConfig.PROCESSING_EXECUTOR));
    }

    @Test
    void uploadedFixtureFlowsToUnifiedRows() throws Exception {
        Path target = uploadRoot.resolve("ALIPAY").resolve("20260910-alipay.csv");
        Files.copy(Path.of("src/test/resources/fixtures/alipay-sample.csv"), target,
                StandardCopyOption.REPLACE_EXISTING);

        StatementUploadedEvent event = new StatementUploadedEvent(StatementBucket.ALIPAY, List.of(
                new StatementUploadedEvent.UploadedFile(
                        "支付宝交易明细.csv", "20260910-alipay.csv")));
        // Unwrap the async proxy: async delivery is verified by the proxy test above;
        // here the real beans run inline so the assertion is deterministic.
        StatementUploadListener inline =
                (StatementUploadListener) ((org.springframework.aop.framework.Advised) listener)
                        .getTargetSource().getTarget();
        inline.onUploadCompleted(event);

        assertEquals(3, unifiedRepo.count());
    }
}
