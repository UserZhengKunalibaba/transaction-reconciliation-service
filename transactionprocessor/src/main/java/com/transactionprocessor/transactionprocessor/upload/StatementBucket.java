package com.transactionprocessor.transactionprocessor.upload;

import com.transactionprocessor.transactionprocessor.domain.SourceType;

/**
 * The six explicit statement-file upload buckets.
 *
 * <p>This is a closed set: arbitrary bucket names are rejected by the upload API.
 * Each bucket maps 1:1 to a directory directly under the configured upload root,
 * e.g. {@code <upload-root>/MAYBANK_SA}, and declares the source its files are
 * assigned during processing (see {@link #toSourceType()}).
 * A file existing in a bucket directory means only "a user put this file into
 * this bucket" — it says nothing about whether the file was or will be processed.
 * Processing state belongs to the processing layer.
 */
public enum StatementBucket {
    MAYBANK_SA,
    MAYBANK_CC,
    GRAB,
    TNG,
    ALIPAY,
    WECHAT;

    /**
     * Parse a client-supplied bucket name. Matching is case-insensitive and
     * trims surrounding whitespace. Anything else throws {@link IllegalArgumentException},
     * which the global exception handler translates to HTTP 400.
     */
    public static StatementBucket fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "bucket is required (one of MAYBANK_SA, MAYBANK_CC, GRAB, TNG, ALIPAY, WECHAT)");
        }
        try {
            return StatementBucket.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "invalid bucket '" + value + "' (one of MAYBANK_SA, MAYBANK_CC, GRAB, TNG, ALIPAY, WECHAT)");
        }
    }

    /**
     * The source assigned to unified transactions processed from this bucket.
     * Each bucket declares a different enum; the bucket — not parser guessing —
     * determines the source label.
     */
    public SourceType toSourceType() {
        return switch (this) {
            case MAYBANK_SA -> SourceType.MAYBANK_SA;
            case MAYBANK_CC -> SourceType.MAYBANK_CC;
            case GRAB -> SourceType.GRAB;
            case TNG -> SourceType.TNG;
            case ALIPAY -> SourceType.ALIPAY;
            case WECHAT -> SourceType.WECHATPAY;
        };
    }
}
