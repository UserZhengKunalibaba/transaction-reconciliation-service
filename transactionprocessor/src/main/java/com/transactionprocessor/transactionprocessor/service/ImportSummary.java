package com.transactionprocessor.transactionprocessor.service;

import java.util.List;

/**
 * Request-time summary of an import operation. Aggregates per-file results.
 * Never persisted — there is no import history.
 */
public class ImportSummary {
    private int filesProcessed;
    private int transactionsParsed;
    private int transactionsInserted;
    private int transactionsSkipped;
    private List<FileImportResult> files;

    public ImportSummary() {}

    public ImportSummary(int filesProcessed, int transactionsParsed,
                         int transactionsInserted, int transactionsSkipped,
                         List<FileImportResult> files) {
        this.filesProcessed = filesProcessed;
        this.transactionsParsed = transactionsParsed;
        this.transactionsInserted = transactionsInserted;
        this.transactionsSkipped = transactionsSkipped;
        this.files = files;
    }

    public int getFilesProcessed() { return filesProcessed; }
    public void setFilesProcessed(int filesProcessed) { this.filesProcessed = filesProcessed; }
    public int getTransactionsParsed() { return transactionsParsed; }
    public void setTransactionsParsed(int transactionsParsed) { this.transactionsParsed = transactionsParsed; }
    public int getTransactionsInserted() { return transactionsInserted; }
    public void setTransactionsInserted(int transactionsInserted) { this.transactionsInserted = transactionsInserted; }
    public int getTransactionsSkipped() { return transactionsSkipped; }
    public void setTransactionsSkipped(int transactionsSkipped) { this.transactionsSkipped = transactionsSkipped; }
    public List<FileImportResult> getFiles() { return files; }
    public void setFiles(List<FileImportResult> files) { this.files = files; }
}
