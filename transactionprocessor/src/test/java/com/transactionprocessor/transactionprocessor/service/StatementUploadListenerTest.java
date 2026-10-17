package com.transactionprocessor.transactionprocessor.service;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import com.transactionprocessor.transactionprocessor.parser.StatementFileDescriptor;
import com.transactionprocessor.transactionprocessor.upload.StatementBucket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for upload→processing handoff. No Spring context, no database;
 * the processing service is mocked — this verifies resolution and delegation only.
 */
class StatementUploadListenerTest {

    @TempDir
    Path tempDir;

    private AppConfig config() {
        AppConfig c = new AppConfig();
        c.setStatementUploadDirectory(tempDir.toString());
        c.setUploadMaxFileSize(1024 * 1024);
        return c;
    }

    private StatementUploadedEvent event(String... storedNames) {
        List<StatementUploadedEvent.UploadedFile> files = new java.util.ArrayList<>();
        for (String s : storedNames) {
            files.add(new StatementUploadedEvent.UploadedFile("orig-" + s, s));
        }
        return new StatementUploadedEvent(StatementBucket.GRAB, files);
    }

    @Test
    void resolvesExactlyTheUploadedFiles() throws Exception {
        Path bucket = Files.createDirectories(tempDir.resolve("GRAB"));
        Files.write(bucket.resolve("20260910-aaa.pdf"), "pdf-bytes".getBytes());
        StatementUploadListener listener =
                new StatementUploadListener(config(), mock(StatementProcessingService.class));

        List<StatementFileDescriptor> resolved = listener.resolve(event("20260910-aaa.pdf"));
        assertEquals(1, resolved.size());
        assertEquals(bucket.resolve("20260910-aaa.pdf"), resolved.get(0).getPath());
        assertEquals("20260910-aaa.pdf", resolved.get(0).getFilename());
        assertEquals("PDF", resolved.get(0).getFileType());
        assertEquals(9, resolved.get(0).getFileSize());
    }

    @Test
    void missingUnsupportedAndEscapingFilesAreSkipped() throws Exception {
        Files.createDirectories(tempDir.resolve("GRAB"));
        StatementUploadListener listener =
                new StatementUploadListener(config(), mock(StatementProcessingService.class));

        List<StatementFileDescriptor> resolved = listener.resolve(event(
                "gone.pdf",            // deleted before processing ran
                "notes.txt",           // unsupported type
                "../evil.pdf",         // escapes the bucket
                "",                    // blank
                null));                // null
        assertTrue(resolved.isEmpty());
    }

    @Test
    void handlerDelegatesResolvedFilesToPipeline() throws Exception {
        Path bucket = Files.createDirectories(tempDir.resolve("GRAB"));
        Files.write(bucket.resolve("20260910-aaa.pdf"), "pdf-bytes".getBytes());
        StatementProcessingService processing = mock(StatementProcessingService.class);
        when(processing.processFiles(any())).thenReturn(new ImportSummary(1, 2, 2, 0, List.of()));
        StatementUploadListener listener = new StatementUploadListener(config(), processing);

        listener.onUploadCompleted(event("20260910-aaa.pdf", "gone.pdf"));

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(processing).processFiles(captor.capture());
        assertEquals(1, captor.getValue().size());
    }

    @Test
    void handlerDoesNothingWhenNothingResolves() {
        StatementProcessingService processing = mock(StatementProcessingService.class);
        StatementUploadListener listener = new StatementUploadListener(config(), processing);

        listener.onUploadCompleted(event("gone.pdf"));

        verify(processing, never()).processFiles(any());
    }

    @Test
    void pipelineFailureIsLoggedNotThrown() throws Exception {
        Path bucket = Files.createDirectories(tempDir.resolve("GRAB"));
        Files.write(bucket.resolve("20260910-aaa.pdf"), "pdf-bytes".getBytes());
        StatementProcessingService processing = mock(StatementProcessingService.class);
        when(processing.processFiles(any())).thenThrow(new RuntimeException("db down"));
        StatementUploadListener listener = new StatementUploadListener(config(), processing);

        assertDoesNotThrow(() -> listener.onUploadCompleted(event("20260910-aaa.pdf")));
    }
}
