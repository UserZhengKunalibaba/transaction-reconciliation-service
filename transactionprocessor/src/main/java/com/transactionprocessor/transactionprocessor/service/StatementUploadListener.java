package com.transactionprocessor.transactionprocessor.service;

import com.transactionprocessor.transactionprocessor.config.AsyncConfig;
import com.transactionprocessor.transactionprocessor.config.AppConfig;
import com.transactionprocessor.transactionprocessor.parser.StatementFileDescriptor;
import com.transactionprocessor.transactionprocessor.util.MaskingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridges upload completion and the processing pipeline — asynchronously.
 *
 * <p>Flow: {@code upload HTTP → files on disk → event → this listener (background
 * thread) → parse/normalize/dedup/batch-insert → log summary}. The upload response
 * never waits for processing. Failures are logged only: there is no processing
 * state to persist.
 */
@Component
public class StatementUploadListener {
    private static final Logger log = LoggerFactory.getLogger(StatementUploadListener.class);

    private final AppConfig config;
    private final StatementProcessingService processing;

    public StatementUploadListener(AppConfig config, StatementProcessingService processing) {
        this.config = config;
        this.processing = processing;
    }

    // NOTE: plain @EventListener, not @TransactionalEventListener — the upload path
    // runs no database transaction, so there is no commit phase to bind delivery to.
    @Async(AsyncConfig.PROCESSING_EXECUTOR)
    @EventListener
    public void onUploadCompleted(StatementUploadedEvent event) {
        List<StatementFileDescriptor> files = resolve(event);
        if (files.isEmpty()) {
            log.info("Async processing: no resolvable files for bucket {}", event.getBucket());
            return;
        }
        try {
            ImportSummary summary = processing.processFiles(files);
            log.info("Async processing done for bucket {}: {} files, {} parsed, {} inserted, {} skipped",
                    event.getBucket(), summary.getFilesProcessed(), summary.getTransactionsParsed(),
                    summary.getTransactionsInserted(), summary.getTransactionsSkipped());
        } catch (Exception e) {
            log.warn("Async processing failed for bucket {}: {}",
                    event.getBucket(), e.getMessage());
        }
    }

    /**
     * Resolve exactly the uploaded files to descriptors. Anything that cannot be
     * resolved safely (missing file, unknown type, path escaping the bucket) is
     * skipped with a log line — never fails the whole batch.
     */
    List<StatementFileDescriptor> resolve(StatementUploadedEvent event) {
        Path root;
        try {
            root = Paths.get(config.getStatementUploadDirectory()).toAbsolutePath().normalize();
        } catch (Exception e) {
            log.warn("Async processing: cannot resolve upload root: {}", e.getMessage());
            return List.of();
        }
        Path bucketDir = root.resolve(event.getBucket().name()).normalize();
        if (!bucketDir.startsWith(root)) {
            log.warn("Async processing: bucket directory escapes upload root");
            return List.of();
        }
        List<StatementFileDescriptor> out = new ArrayList<>();
        for (StatementUploadedEvent.UploadedFile f : event.getFiles()) {
            String stored = f.storedFilename();
            if (stored == null || stored.isBlank()) {
                continue;
            }
            try {
                Path file = bucketDir.resolve(stored).normalize();
                if (!file.startsWith(bucketDir) || !Files.isRegularFile(file)) {
                    log.warn("Async processing: skipping unresolvable file {}",
                            MaskingUtil.mask(stored));
                    continue;
                }
                String type = typeOf(stored);
                if (type == null) {
                    log.warn("Async processing: skipping unsupported file type {}",
                            MaskingUtil.mask(stored));
                    continue;
                }
                out.add(new StatementFileDescriptor(file, stored, Files.size(file), type));
            } catch (Exception e) {
                log.warn("Async processing: skipping file {}: {}",
                        MaskingUtil.mask(stored), e.getMessage());
            }
        }
        return out;
    }

    private static String typeOf(String filename) {
        String l = filename.toLowerCase();
        if (l.endsWith(".pdf")) return "PDF";
        if (l.endsWith(".csv")) return "CSV";
        if (l.endsWith(".xlsx")) return "XLSX";
        if (l.endsWith(".xls")) return "XLS";
        if (l.endsWith(".zip")) return "ZIP";
        return null;
    }
}
