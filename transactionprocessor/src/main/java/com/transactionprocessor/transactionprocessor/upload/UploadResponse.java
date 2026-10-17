package com.transactionprocessor.transactionprocessor.upload;

import java.util.List;

/**
 * Typed response for a multi-file bucket upload. Each file has an independent
 * per-file result; successful files are never rolled back because another file
 * in the same request failed.
 */
public class UploadResponse {
    private String bucket;
    private List<FileUploadResult> files;

    public UploadResponse() {}

    public UploadResponse(String bucket, List<FileUploadResult> files) {
        this.bucket = bucket;
        this.files = files;
    }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public List<FileUploadResult> getFiles() { return files; }
    public void setFiles(List<FileUploadResult> files) { this.files = files; }
}
