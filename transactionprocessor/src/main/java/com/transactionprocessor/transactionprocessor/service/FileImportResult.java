package com.transactionprocessor.transactionprocessor.service;

import java.util.List;

/**
 * Request-time result for one processed file. Describes the operation only —
 * never persisted.
 */
public class FileImportResult {
    private String filename;
    private String bucket;
    private int parsed;
    private int inserted;
    private int skipped;
    private List<String> errors;

    public FileImportResult() {}

    public FileImportResult(String filename, String bucket, int parsed,
                            int inserted, int skipped, List<String> errors) {
        this.filename = filename;
        this.bucket = bucket;
        this.parsed = parsed;
        this.inserted = inserted;
        this.skipped = skipped;
        this.errors = errors;
    }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public int getParsed() { return parsed; }
    public void setParsed(int parsed) { this.parsed = parsed; }
    public int getInserted() { return inserted; }
    public void setInserted(int inserted) { this.inserted = inserted; }
    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }
    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }
}
