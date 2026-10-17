package com.transactionprocessor.transactionprocessor.parser;

import java.nio.file.Path;

public class StatementFileDescriptor {
    private final Path path;
    private final String filename;
    private final long fileSize;
    private final String fileType; // PDF, CSV, XLSX, XLS, ZIP

    public StatementFileDescriptor(Path path, String filename, long fileSize, String fileType) {
        this.path = path;
        this.filename = filename;
        this.fileSize = fileSize;
        this.fileType = fileType;
    }

    public Path getPath() { return path; }
    public String getFilename() { return filename; }
    public long getFileSize() { return fileSize; }
    public String getFileType() { return fileType; }
}
