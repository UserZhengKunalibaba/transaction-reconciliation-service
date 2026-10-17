package com.transactionprocessor.transactionprocessor.upload;

/**
 * Immediate result of a single file's filesystem write.
 *
 * <p>{@code status} is either {@code SUCCESS} or {@code ERROR} and describes only
 * the HTTP filesystem operation — it is NOT application/processing state.
 * No absolute server paths are exposed.
 */
public class FileUploadResult {
    private String originalFilename;
    private String storedFilename;
    private String bucket;
    private String status;
    private String message;

    public FileUploadResult() {}

    public FileUploadResult(String originalFilename, String storedFilename,
                            String bucket, String status, String message) {
        this.originalFilename = originalFilename;
        this.storedFilename = storedFilename;
        this.bucket = bucket;
        this.status = status;
        this.message = message;
    }

    public static FileUploadResult success(String originalFilename, String storedFilename, StatementBucket bucket) {
        return new FileUploadResult(originalFilename, storedFilename, bucket.name(), "SUCCESS", "stored");
    }

    public static FileUploadResult error(String originalFilename, StatementBucket bucket, String message) {
        return new FileUploadResult(originalFilename, null, bucket == null ? null : bucket.name(), "ERROR", message);
    }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getStoredFilename() { return storedFilename; }
    public void setStoredFilename(String storedFilename) { this.storedFilename = storedFilename; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
