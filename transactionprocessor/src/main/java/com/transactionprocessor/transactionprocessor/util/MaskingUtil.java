package com.transactionprocessor.transactionprocessor.util;

/** Mask PII in logs: card numbers, emails. Never log full statement payload at INFO. */
public final class MaskingUtil {
    private MaskingUtil() {}

    public static String maskCard(String s) {
        if (s == null) return null;
        // 5460 1500 3352 0000 -> XXXX-XXXX-XXXX-0000
        return s.replaceAll("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?(\\d{4})\\b", "XXXX-XXXX-XXXX-$1");
    }

    public static String maskEmail(String s) {
        if (s == null) return null;
        return s.replaceAll("([A-Za-z0-9._%+-]{1,2})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+)", "$1***$2");
    }

    public static String mask(String s) {
        return maskEmail(maskCard(s));
    }

    public static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
