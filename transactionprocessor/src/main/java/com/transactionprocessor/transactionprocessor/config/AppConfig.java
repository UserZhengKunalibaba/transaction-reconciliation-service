package com.transactionprocessor.transactionprocessor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {
    /**
     * Application-managed persistent storage root for uploaded statement files.
     * Populated exclusively by the upload API ({@code POST /api/statements/upload}),
     * never by users manually copying files. Each bucket lives directly underneath,
     * e.g. {@code <upload-root>/MAYBANK_SA}.
     */
    @Value("${app.statement-upload-directory:/app/data/statements}")
    private String statementUploadDirectory;

    /** Maximum accepted size per uploaded statement file, in bytes. */
    @Value("${app.upload-max-file-size:52428800}")
    private long uploadMaxFileSize;

    /**
     * Synthetic-timestamp step in minutes for date-only statement rows. Row {@code i}
     * of a file becomes {@code date at 00:00 + i × step}. Deterministic per input,
     * so reprocessing reproduces identical deduplication keys.
     */
    @Value("${app.synthetic-timestamp-step-minutes:3}")
    private int syntheticTimestampStepMinutes;

    @Value("${app.tng-password:}")
    private String tngPassword;

    @Value("${app.ocr-enabled:false}")
    private boolean ocrEnabled;

    public String getStatementUploadDirectory() { return statementUploadDirectory; }
    public long getUploadMaxFileSize() { return uploadMaxFileSize; }
    public int getSyntheticTimestampStepMinutes() { return syntheticTimestampStepMinutes; }
    // Setters exist for tests, which construct AppConfig without the Spring container.
    public void setStatementUploadDirectory(String statementUploadDirectory) {
        this.statementUploadDirectory = statementUploadDirectory;
    }
    public void setUploadMaxFileSize(long uploadMaxFileSize) { this.uploadMaxFileSize = uploadMaxFileSize; }
    public String getTngPassword() { return tngPassword; }
    public boolean isOcrEnabled() { return ocrEnabled; }
}
