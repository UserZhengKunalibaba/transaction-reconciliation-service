package com.transactionprocessor.transactionprocessor.service;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import com.transactionprocessor.transactionprocessor.upload.FileUploadResult;
import com.transactionprocessor.transactionprocessor.upload.StatementBucket;
import com.transactionprocessor.transactionprocessor.upload.StorageFilenameGenerator;
import com.transactionprocessor.transactionprocessor.upload.UploadFileValidator;
import com.transactionprocessor.transactionprocessor.upload.UploadResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless file-ingestion service.
 *
 * <p>Responsibility boundary: validate request/file → determine bucket → write file
 * to the persistent mounted volume → return result. That is the entire responsibility.
 *
 * <p>This service has NO database dependency, retains no application state after the
 * call, and never invokes parsers, normalizers, reconciliation, discovery, or import.
 * The filesystem is the only persistent output. After storing files it publishes a
 * {@link StatementUploadedEvent} (identities only) so background processing can pick
 * the files up — publishing an event is not a processing dependency: this service
 * still has no knowledge of any listener.
 */
@Service
public class StatementUploadService {

    private final AppConfig config;
    private final ApplicationEventPublisher events;

    public StatementUploadService(AppConfig config, ApplicationEventPublisher events) {
        this.config = config;
        this.events = events;
    }

    /**
     * Store {@code files} into {@code bucket}'s directory. Each file is handled
     * independently: one file's failure never deletes or affects the others.
     * Successfully stored files are announced via {@link StatementUploadedEvent}
     * for asynchronous downstream processing; the HTTP caller does not wait for it.
     */
    public UploadResponse upload(StatementBucket bucket, List<MultipartFile> files) throws IOException {
        if (bucket == null) {
            throw new IllegalArgumentException(
                    "bucket is required (one of MAYBANK_SA, MAYBANK_CC, GRAB, TNG, ALIPAY, WECHAT)");
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("at least one file is required");
        }
        Path bucketDir = bucketDirectory(bucket);
        Files.createDirectories(bucketDir);

        List<FileUploadResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(storeOne(bucket, bucketDir, file));
        }
        List<StatementUploadedEvent.UploadedFile> stored = results.stream()
                .filter(r -> "SUCCESS".equals(r.getStatus()))
                .map(r -> new StatementUploadedEvent.UploadedFile(
                        r.getOriginalFilename(), r.getStoredFilename()))
                .toList();
        if (!stored.isEmpty()) {
            events.publishEvent(new StatementUploadedEvent(bucket, stored));
        }
        return new UploadResponse(bucket.name(), results);
    }

    private FileUploadResult storeOne(StatementBucket bucket, Path bucketDir, MultipartFile file) throws IOException {
        String original = file.getOriginalFilename();
        String displayName = original == null || original.isBlank() ? "(unnamed)" : original;
        String rejection = UploadFileValidator.validate(original, file.getSize(), config.getUploadMaxFileSize());
        if (rejection != null) {
            return FileUploadResult.error(displayName, bucket, rejection);
        }
        String extension = UploadFileValidator.extensionOf(original);
        String storedName = StorageFilenameGenerator.generate(extension);
        Path target = resolveSafely(bucketDir, storedName);

        // Safe write: stream to a temp file in the bucket directory, then atomically
        // move into place. A failed write leaves no partially written file behind.
        Path temp = Files.createTempFile(bucketDir, ".upload-", ".tmp");
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort: the bucket must not keep a partially written file
            }
            return FileUploadResult.error(displayName, bucket, "failed to store file: " + e.getMessage());
        }
        return FileUploadResult.success(displayName, storedName, bucket);
    }

    /** Resolve the bucket directory and guarantee it stays under the upload root. */
    Path bucketDirectory(StatementBucket bucket) throws IOException {
        Path root = Paths.get(config.getStatementUploadDirectory()).toAbsolutePath().normalize();
        Path bucketDir = root.resolve(bucket.name()).normalize();
        if (!bucketDir.startsWith(root)) {
            throw new IOException("bucket directory escapes upload root");
        }
        return bucketDir;
    }

    /**
     * Resolve a generated storage name inside the bucket directory and verify the
     * final path cannot escape it, even under adversarial input.
     */
    static Path resolveSafely(Path bucketDir, String storedName) throws IOException {
        Path resolved = bucketDir.resolve(storedName).normalize();
        if (!resolved.startsWith(bucketDir)) {
            throw new IOException("rejected unsafe filename");
        }
        return resolved;
    }
}
