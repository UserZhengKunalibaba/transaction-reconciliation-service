package com.transactionprocessor.transactionprocessor.parser;

public class StatementParseException extends Exception {
    private final String code; // e.g. NEED_OCR, ENCRYPTED, UNSUPPORTED

    public StatementParseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public StatementParseException(String message, Throwable cause) {
        super(message, cause);
        this.code = "PARSE_ERROR";
    }

    public String getCode() { return code; }
}
