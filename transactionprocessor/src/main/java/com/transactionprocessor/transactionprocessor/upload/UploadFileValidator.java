package com.transactionprocessor.transactionprocessor.upload;

import java.util.Locale;
import java.util.Set;

/**
 * Stateless validation for uploaded statement files.
 *
 * <p>Only establishes that a file is an allowed statement-file type. No parser-specific
 * validation, no database access, no side effects.
 */
public final class UploadFileValidator {

    /** Supported statement-file extensions (lowercase, without dot). */
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "csv", "xlsx", "xls", "zip");

    private UploadFileValidator() {}

    /** Extract the extension from a client-supplied filename, or "" if none. */
    public static String extensionOf(String originalFilename) {
        if (originalFilename == null) return "";
        // Strip any client-supplied path segments; only the final segment counts.
        String base = originalFilename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) return "";
        return base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Validate a single file's metadata. Returns {@code null} when valid, otherwise
     * a human-readable rejection reason for the per-file response.
     */
    public static String validate(String originalFilename, long size, long maxFileSize) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "missing filename";
        }
        if (size <= 0) {
            return "file is empty";
        }
        if (size > maxFileSize) {
            return "file exceeds maximum size of " + maxFileSize + " bytes";
        }
        String ext = extensionOf(originalFilename);
        if (ext.isEmpty() || !SUPPORTED_EXTENSIONS.contains(ext)) {
            return "unsupported file type '." + ext + "' (allowed: pdf, csv, xlsx, xls, zip)";
        }
        return null;
    }
}
