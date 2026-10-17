package com.transactionprocessor.transactionprocessor.service;

import com.transactionprocessor.transactionprocessor.upload.StatementBucket;

import java.util.List;

/**
 * Published when an upload request has successfully stored files on disk.
 *
 * <p>Carries only identities (bucket + storage filenames), never file content or
 * processing state. The upload layer publishes this and forgets it — it has no
 * knowledge of who listens or what happens next. The filesystem remains the only
 * persistent output of the upload operation.
 */
public class StatementUploadedEvent {

    /** One successfully stored file. */
    public record UploadedFile(String originalFilename, String storedFilename) {}

    private final StatementBucket bucket;
    private final List<UploadedFile> files;

    public StatementUploadedEvent(StatementBucket bucket, List<UploadedFile> files) {
        this.bucket = bucket;
        this.files = List.copyOf(files);
    }

    public StatementBucket getBucket() {
        return bucket;
    }

    public List<UploadedFile> getFiles() {
        return files;
    }
}
