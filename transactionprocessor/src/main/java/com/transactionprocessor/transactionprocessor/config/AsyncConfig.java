package com.transactionprocessor.transactionprocessor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Background execution for post-upload statement processing.
 *
 * <p>The pool is deliberately small: uploads stay fast while a couple of workers
 * drain the processing queue. Import is idempotent (transaction-level dedup), so
 * concurrent workers are safe. Rejected tasks surface loudly via the caller's
 * error handling rather than silently dropping files.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String PROCESSING_EXECUTOR = "statementProcessingExecutor";

    @Bean(PROCESSING_EXECUTOR)
    public Executor statementProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("stmt-proc-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
