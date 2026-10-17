package com.transactionprocessor.transactionprocessor.upload;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Generates safe server-side storage filenames.
 *
 * <p>The client-supplied original filename is never used as a filesystem path.
 * Generated names are unique (UUID), safe (restricted character set), retain the
 * validated extension for debugging, and are prefixed with the current date for
 * rough ordering, e.g. {@code 20260910-3f9a1c2ed44b4a1f.pdf}.
 */
public final class StorageFilenameGenerator {

    private StorageFilenameGenerator() {}

    public static String generate(String extension) {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        return ext.isEmpty() ? date + "-" + uuid : date + "-" + uuid + "." + ext;
    }
}
