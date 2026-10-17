package com.transactionprocessor.transactionprocessor.web;

import com.transactionprocessor.transactionprocessor.service.StatementProcessingService;
import com.transactionprocessor.transactionprocessor.service.StatementUploadService;
import com.transactionprocessor.transactionprocessor.upload.FileUploadResult;
import com.transactionprocessor.transactionprocessor.upload.StatementBucket;
import com.transactionprocessor.transactionprocessor.upload.UploadResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for {@code POST /api/statements/upload}. The upload service is mocked:
 * these tests verify routing, bucket validation, and response shape — never processing.
 */
@WebMvcTest(StatementUploadController.class)
class StatementUploadControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @MockBean
    StatementUploadService uploadService;

    @Test
    void uploadMultipleFilesReturnsPerFileResults() throws Exception {
        when(uploadService.upload(eq(StatementBucket.MAYBANK_SA), any())).thenReturn(
                new UploadResponse("MAYBANK_SA", List.of(
                        FileUploadResult.success("a.pdf", "20260910-aaa.pdf", StatementBucket.MAYBANK_SA),
                        FileUploadResult.success("b.pdf", "20260910-bbb.pdf", StatementBucket.MAYBANK_SA))));

        mvc.perform(multipart("/api/statements/upload")
                        .file(new MockMultipartFile("files", "a.pdf", "application/pdf", "a".getBytes()))
                        .file(new MockMultipartFile("files", "b.pdf", "application/pdf", "b".getBytes()))
                        .param("bucket", "MAYBANK_SA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket").value("MAYBANK_SA"))
                .andExpect(jsonPath("$.files.length()").value(2))
                .andExpect(jsonPath("$.files[0].originalFilename").value("a.pdf"))
                .andExpect(jsonPath("$.files[0].storedFilename").value("20260910-aaa.pdf"))
                .andExpect(jsonPath("$.files[0].status").value("SUCCESS"));
    }

    @Test
    void invalidBucketReturns400() throws Exception {
        mvc.perform(multipart("/api/statements/upload")
                        .file(new MockMultipartFile("files", "a.pdf", "application/pdf", "a".getBytes()))
                        .param("bucket", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingBucketReturns400() throws Exception {
        mvc.perform(multipart("/api/statements/upload")
                        .file(new MockMultipartFile("files", "a.pdf", "application/pdf", "a".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bucketNameIsCaseInsensitive() throws Exception {
        when(uploadService.upload(eq(StatementBucket.GRAB), any())).thenReturn(
                new UploadResponse("GRAB", List.of()));
        mvc.perform(multipart("/api/statements/upload")
                        .file(new MockMultipartFile("files", "a.pdf", "application/pdf", "a".getBytes()))
                        .param("bucket", "grab"))
                .andExpect(status().isOk());
    }

    @Test
    void uploadSliceHasNoProcessingSideEffects() {
        // The upload web slice must not wire the processing pipeline at all.
        assertEquals(0, context.getBeanNamesForType(StatementProcessingService.class).length);
        assertTrue(context.getBeanNamesForType(
                com.transactionprocessor.transactionprocessor.parser.ParserRegistry.class).length == 0);
    }
}
