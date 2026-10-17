package com.transactionprocessor.transactionprocessor.upload;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import com.transactionprocessor.transactionprocessor.service.StatementUploadService;
import com.transactionprocessor.transactionprocessor.service.StatementUploadedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Upload-layer tests. Plain JUnit — no Spring context, no database, no repositories.
 * If these tests ever need a DataSource, the upload layer has grown a DB dependency
 * and the test setup itself is the failure signal.
 */
class StatementUploadServiceTest {

    @TempDir
    Path tempDir;

    private AppConfig config() {
        AppConfig c = new AppConfig();
        c.setStatementUploadDirectory(tempDir.toString());
        c.setUploadMaxFileSize(1024 * 1024);
        return c;
    }

    private final List<StatementUploadedEvent> published = new ArrayList<>();

    private StatementUploadService svc() {
        return new StatementUploadService(config(), e -> published.add((StatementUploadedEvent) e));
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("files", name, "application/octet-stream",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, "application/octet-stream", content);
    }

    @Test
    void allBucketsAreAccepted() throws Exception {
        StatementUploadService svc = svc();
        for (StatementBucket bucket : StatementBucket.values()) {
            UploadResponse r = svc.upload(bucket, List.of(file("stmt.pdf", "pdf-bytes")));
            assertEquals(bucket.name(), r.getBucket());
            assertEquals(1, r.getFiles().size());
            FileUploadResult f = r.getFiles().get(0);
            assertEquals("SUCCESS", f.getStatus());
            assertEquals("stmt.pdf", f.getOriginalFilename());
            assertNotNull(f.getStoredFilename());
            assertTrue(Files.isRegularFile(tempDir.resolve(bucket.name()).resolve(f.getStoredFilename())),
                    "file must exist in its bucket directory");
        }
    }

    @Test
    void invalidBucketIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StatementBucket.fromString("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> StatementBucket.fromString(""));
        assertThrows(IllegalArgumentException.class, () -> StatementBucket.fromString(null));
        assertThrows(IllegalArgumentException.class, () -> StatementBucket.fromString("../GRAB"));
    }

    @Test
    void multipleFilesInOneRequest() throws Exception {
        StatementUploadService svc = svc();
        UploadResponse r = svc.upload(StatementBucket.MAYBANK_SA, List.of(
                file("a.pdf", "a"), file("b.csv", "b"), file("c.xlsx", "c")));
        assertEquals(3, r.getFiles().size());
        assertTrue(r.getFiles().stream().allMatch(f -> "SUCCESS".equals(f.getStatus())));
        try (Stream<Path> s = Files.list(tempDir.resolve("MAYBANK_SA"))) {
            assertEquals(3, s.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void emptyFileListIsRejected() {
        StatementUploadService svc = svc();
        assertThrows(IllegalArgumentException.class,
                () -> svc.upload(StatementBucket.MAYBANK_SA, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> svc.upload(StatementBucket.MAYBANK_SA, null));
    }

    @Test
    void unsupportedExtensionIsRejectedPerFileWithoutAffectingOthers() throws Exception {
        StatementUploadService svc = svc();
        UploadResponse r = svc.upload(StatementBucket.GRAB, List.of(
                file("a.pdf", "a"),
                file("notes.txt", "nope"),
                file("b.pdf", "b")));
        assertEquals("SUCCESS", r.getFiles().get(0).getStatus());
        assertEquals("ERROR", r.getFiles().get(1).getStatus());
        assertEquals("SUCCESS", r.getFiles().get(2).getStatus());
        // Successful files are kept, rejected file leaves nothing behind.
        try (Stream<Path> s = Files.list(tempDir.resolve("GRAB"))) {
            assertEquals(2, s.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void zeroByteFileIsRejected() throws Exception {
        StatementUploadService svc = svc();
        UploadResponse r = svc.upload(StatementBucket.TNG,
                List.of(file("empty.pdf", new byte[0])));
        assertEquals("ERROR", r.getFiles().get(0).getStatus());
        try (Stream<Path> s = Files.list(tempDir.resolve("TNG"))) {
            assertEquals(0, s.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void oversizedFileIsRejected() throws Exception {
        AppConfig c = config();
        c.setUploadMaxFileSize(4);
        StatementUploadService svc = new StatementUploadService(c, e -> published.add((StatementUploadedEvent) e));
        UploadResponse r = svc.upload(StatementBucket.TNG, List.of(file("big.pdf", "12345")));
        assertEquals("ERROR", r.getFiles().get(0).getStatus());
    }

    @Test
    void pathTraversalFilenamesCannotEscapeBucket() throws Exception {
        StatementUploadService svc = svc();
        UploadResponse r = svc.upload(StatementBucket.ALIPAY, List.of(
                file("../../evil.pdf", "evil"),
                file("..\\..\\evil2.pdf", "evil2"),
                file("/abs/path/evil3.pdf", "evil3")));
        for (FileUploadResult f : r.getFiles()) {
            assertEquals("SUCCESS", f.getStatus());
            assertFalse(f.getStoredFilename().contains(".."));
            assertFalse(f.getStoredFilename().contains("/"));
            assertFalse(f.getStoredFilename().contains("\\"));
        }
        // Every file on disk must live under the bucket directory — nothing escaped.
        try (Stream<Path> s = Files.walk(tempDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p)) {
                    assertTrue(p.startsWith(tempDir.resolve("ALIPAY")),
                            "file escaped bucket: " + p);
                }
            }
        }
        assertEquals(3, Files.list(tempDir.resolve("ALIPAY"))
                .filter(Files::isRegularFile).count());
    }

    @Test
    void duplicateFilenamesDoNotOverwrite() throws Exception {
        StatementUploadService svc = svc();
        UploadResponse r1 = svc.upload(StatementBucket.WECHAT, List.of(file("same.pdf", "first")));
        UploadResponse r2 = svc.upload(StatementBucket.WECHAT, List.of(file("same.pdf", "second")));
        String s1 = r1.getFiles().get(0).getStoredFilename();
        String s2 = r2.getFiles().get(0).getStoredFilename();
        assertNotEquals(s1, s2);
        assertEquals("first", Files.readString(tempDir.resolve("WECHAT").resolve(s1)));
        assertEquals("second", Files.readString(tempDir.resolve("WECHAT").resolve(s2)));
    }

    @Test
    void bucketIsolation() throws Exception {
        StatementUploadService svc = svc();
        svc.upload(StatementBucket.MAYBANK_SA, List.of(file("m.pdf", "m")));
        for (StatementBucket b : StatementBucket.values()) {
            Path dir = tempDir.resolve(b.name());
            if (b == StatementBucket.MAYBANK_SA) {
                assertEquals(1, Files.list(dir).filter(Files::isRegularFile).count());
            } else {
                assertTrue(!Files.exists(dir)
                        || Files.list(dir).filter(Files::isRegularFile).count() == 0,
                        "bucket " + b + " must not receive MAYBANK_SA files");
            }
        }
    }

    @Test
    void configuredDirectoryIsUsed() throws Exception {
        Path custom = tempDir.resolve("custom-root");
        AppConfig c = new AppConfig();
        c.setStatementUploadDirectory(custom.toString());
        c.setUploadMaxFileSize(1024 * 1024);
        new StatementUploadService(c, e -> published.add((StatementUploadedEvent) e)).upload(StatementBucket.GRAB, List.of(file("g.pdf", "g")));
        assertTrue(Files.isDirectory(custom.resolve("GRAB")));
        assertEquals(1, Files.list(custom.resolve("GRAB")).filter(Files::isRegularFile).count());
    }

    @Test
    void responseExposesNoAbsolutePaths() throws Exception {
        StatementUploadService svc = svc();
        UploadResponse r = svc.upload(StatementBucket.MAYBANK_SA, List.of(file("a.pdf", "a")));
        FileUploadResult f = r.getFiles().get(0);
        assertFalse(f.getStoredFilename().contains(tempDir.toString()));
        assertFalse(String.valueOf(f.getMessage()).contains(tempDir.toString()));
    }

    @Test
    void uploadServiceHasNoDatabaseOrProcessingDependencies() {
        // Architectural invariant: Controller → UploadService → Filesystem.
        // The service may publish events, but must never hold a repository,
        // parser, or processing-service reference. Fails by design if added.
        for (var field : StatementUploadService.class.getDeclaredFields()) {
            String type = field.getType().getName();
            assertFalse(type.contains("Repository"), "upload service must not depend on " + type);
            assertFalse(type.contains("Parser"), "upload service must not depend on " + type);
            assertFalse(type.contains("Normaliz"), "upload service must not depend on " + type);
            assertFalse(type.contains("Import"), "upload service must not depend on " + type);
            assertFalse(type.contains("Reconcil"), "upload service must not depend on " + type);
            assertFalse(type.contains("Processing"), "upload service must not depend on " + type);
        }
    }

    @Test
    void successfulUploadPublishesEventForStoredFilesOnly() throws Exception {
        StatementUploadService svc = svc();
        UploadResponse r = svc.upload(StatementBucket.GRAB, List.of(
                file("a.pdf", "a"),
                file("notes.txt", "nope"),
                file("b.pdf", "b")));
        assertEquals(1, published.size());
        StatementUploadedEvent event = published.get(0);
        assertEquals(StatementBucket.GRAB, event.getBucket());
        assertEquals(2, event.getFiles().size());
        assertEquals("a.pdf", event.getFiles().get(0).originalFilename());
        assertEquals(r.getFiles().get(0).getStoredFilename(),
                event.getFiles().get(0).storedFilename());
        assertEquals("b.pdf", event.getFiles().get(1).originalFilename());
    }

    @Test
    void noEventWhenNothingWasStored() throws Exception {
        StatementUploadService svc = svc();
        svc.upload(StatementBucket.GRAB, List.of(file("notes.txt", "nope")));
        assertTrue(published.isEmpty());
    }
}
