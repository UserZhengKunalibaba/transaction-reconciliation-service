package com.transactionprocessor.transactionprocessor.web;

import com.transactionprocessor.transactionprocessor.service.StatementUploadService;
import com.transactionprocessor.transactionprocessor.upload.StatementBucket;
import com.transactionprocessor.transactionprocessor.upload.UploadResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * File-ingestion endpoint. Finishes after files are safely persisted to the
 * bucket directory — it never triggers discovery, parsing, import, or any
 * database write.
 */
@RestController
@RequestMapping("/api/statements")
public class StatementUploadController {

    private final StatementUploadService uploadService;

    public StatementUploadController(StatementUploadService uploadService) {
        this.uploadService = uploadService;
    }

    /**
     * Upload one or more statement files into an explicit bucket.
     *
     * <p>Example: {@code POST /api/statements/upload} with
     * {@code multipart/form-data}, fields {@code bucket=MAYBANK} and
     * {@code files} (multiple). Buckets other than
     * MAYBANK_SA/MAYBANK_CC/GRAB/TNG/ALIPAY/WECHAT are rejected with HTTP 400.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestParam("bucket") String bucket,
                                 @RequestParam("files") List<MultipartFile> files) throws Exception {
        StatementBucket b = StatementBucket.fromString(bucket);
        return uploadService.upload(b, files);
    }
}
