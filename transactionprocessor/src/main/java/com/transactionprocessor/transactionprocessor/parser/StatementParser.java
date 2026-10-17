package com.transactionprocessor.transactionprocessor.parser;

import java.util.List;

public interface StatementParser {
    /** Unique parser version, e.g. "alipay-v1". Stored on statement_file for re-import tracking. */
    String version();

    /** Lightweight check: filename + fileType. Content sniffing allowed but must not throw. */
    boolean supports(StatementFileDescriptor file);

    List<RawTransactionData> parse(StatementFileDescriptor file) throws StatementParseException;
}
