package com.transactionprocessor.transactionprocessor.normalizer;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import com.transactionprocessor.transactionprocessor.parser.RawTransactionData;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RawTransactionData → UnifiedTransaction.
 *
 * <p>The per-source rules below run unchanged on parser output via a transient,
 * never-persisted adapter: the intermediate objects are volatile by construction
 * and no repository ever sees them. Only the returned UnifiedTransaction is
 * eligible for persistence, and only through the pipeline's batch insert.
 *
 * <p>Account mapping (strict): Alipay/WeChat/TNG → Cash, Grab → Grab,
 * Maybank (SA + Bill/CC) → Maybank. No source leaves account empty.
 */
@Service
public class NormalizerService {

    private final CategoryInferenceService categories;
    private final ForeignCurrencyFormatter fx;

    public NormalizerService(CategoryInferenceService categories, ForeignCurrencyFormatter fx) {
        this.categories = categories;
        this.fx = fx;
    }

    public UnifiedTransaction normalizeToUnified(RawTransactionData data, SourceType source) {
        // Volatile intermediate: converted to UnifiedTransaction immediately below,
        // never persisted.
        RawTransactionData raw = data;
        Norm n = new Norm();
        n.setSource(source);
        Map<String, Object> p = data.getPayload() == null ? Map.of() : data.getPayload();
        switch (source) {
            case ALIPAY -> normalizeAlipay(n, raw, p);
            case WECHATPAY -> normalizeWechat(n, raw, p);
            case GRAB -> normalizeGrab(n, raw, p);
            case TNG -> normalizeTng(n, raw, p);
            case MAYBANK_CC -> normalizeMaybankCc(n, raw, p);
            case MAYBANK_SA, MAYBANK -> normalizeMaybankSa(n, raw, p);
        }
        if (n.getCategory() == null) {
            n.setCategory(categories.infer(n.getMerchant(), n.getDescription(),
                    source.name(), n.getType()));
        }
        if (n.getType() == null) n.setType("EXPENSE");

        UnifiedTransaction u = new UnifiedTransaction();
        u.setSource(source);
        u.setPayee(canonicalPayee(n.getMerchant()));
        if (n.getTransactionDate() != null) {
            if (n.getTransactionTime() != null) {
                u.setTimestamp(LocalDateTime.of(n.getTransactionDate(), n.getTransactionTime()));
                u.setDateOnly(false);
            } else {
                // Date-only source row: midnight placeholder + marker. The pipeline
                // assigns the deterministic synthetic time centrally, in file order.
                u.setTimestamp(n.getTransactionDate().atStartOfDay());
                u.setDateOnly(true);
            }
        }
        if (n.getAmountMyr() != null && n.getAmountMyr().signum() != 0) {
            u.setAmount(n.getAmountMyr());
            u.setAmountCurrency("MYR");
        } else if (n.getAmountMyr() != null) {
            // Zero is currency-agnostic (e.g. zeroed-out Alipay transfers/refunds):
            // keep the zero but label the transaction's own currency, never MYR
            // merely because the MYR field carried it.
            u.setAmount(BigDecimal.ZERO);
            u.setAmountCurrency(n.getOriginalCurrency() != null ? n.getOriginalCurrency() : "MYR");
        } else if (n.getCanonicalAmount() != null) {
            // Source-declared canonical (e.g. foreign Grab spend): denominated in
            // the original currency, matching the MYR-resolved branch's sign.
            u.setAmount(n.getCanonicalAmount());
            u.setAmountCurrency(n.getOriginalCurrency() != null ? n.getOriginalCurrency() : "MYR");
        } else {
            // Unresolved (e.g. Alipay/WeChat expense pending reconciliation):
            // canonical amount is the original amount. Reprocessing identical input
            // still yields identical keys, so idempotency holds.
            u.setAmount(n.getOriginalAmount() != null ? n.getOriginalAmount() : BigDecimal.ZERO);
            u.setAmountCurrency(n.getOriginalCurrency() != null ? n.getOriginalCurrency() : "MYR");
        }
        u.setReference(n.getReference());
        u.setType(n.getType());
        u.setAccount(n.getAccount());
        u.setCategory(n.getCategory());
        u.setDescription(n.getDescription());
        u.setOriginalAmount(n.getOriginalAmount());
        u.setOriginalCurrency(n.getOriginalCurrency());
        return u;
    }

    /**
     * Canonical payee for deduplication: trimmed, whitespace-collapsed, upper-cased.
     * Exact-match semantics — no fuzzy merchant matching.
     */
    public static String canonicalPayee(String merchant) {
        if (merchant == null) return "";
        return merchant.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /**
     * Canonical amount rendering for deduplication keys: scale-insensitive, so
     * {@code 2}, {@code 2.0} and {@code 2.00} produce the same key.
     */
    public static String dedupAmountPlain(BigDecimal amount) {
        if (amount == null) return "0";
        return amount.stripTrailingZeros().toPlainString();
    }

    /** Strips cell formatting artifacts (¥, $, commas, spaces) leaving a plain decimal. */
    private String cleanAmount(String s) {
        if (s == null) return "0";
        return s.replace(",", "").replace("¥", "").replace("￥", "").replace("$", "").replace("RM", "").strip();
    }

    // ---- Alipay ----
    void normalizeAlipay(Norm n, RawTransactionData raw, Map<String, Object> p) {
        String time = s(p.get("交易时间"), raw.getRawDate());
        n.setTransactionDate(parseDateTime(time));
        n.setTransactionTime(parseTime(time));
        String inout = s(p.get("收/支"), "");
        String amount = cleanAmount(s(p.get("金额"), "0"));
        BigDecimal amt = num(amount);
        String merchant = s(p.get("交易对方"), "");
        String goods = s(p.get("商品说明"), "");
        String cat = s(p.get("交易分类"), "");
        String payMethod = s(p.get("收/付款方式"), "");
        String status = s(p.get("交易状态"), "");
        n.setMerchant(merchant);
        n.setOriginalAmount(amt);
        n.setOriginalCurrency("CNY");
        n.setReference(s(p.get("交易订单号"), raw.getSourceTransactionId()));
        if ("收入".equals(inout)) {
            n.setType("INCOME");
            // Amount stays in original currency (amountMyr deliberately unset):
            // stuffing CNY into the MYR field mislabels the canonical currency.
            n.setAmountMyr(null);
            n.setAccount(WalletAccount.Cash);
            n.setDescription(merchant + " - " + goods);
        } else if ("不计收支".equals(inout) || "交易关闭".equals(status) || "退款成功".equals(status)) {
            n.setType("TRANSFER");
            n.setAmountMyr(BigDecimal.ZERO);
            n.setAccount(WalletAccount.Cash);
            n.setDescription(merchant + " - " + goods + " [" + status + "]");
        } else {
            n.setType("EXPENSE");
            // MYR resolved at reconciliation (match → Maybank final; else Cash -0.01),
            // but the account is always Cash for Alipay. Canonical spend is negative
            // in the original currency, like every other source.
            n.setAmountMyr(null);
            n.setCanonicalAmount(amt.signum() > 0 ? amt.negate() : amt);
            n.setAccount(WalletAccount.Cash);
            String base = (cat.isEmpty() ? "Alipay" : cat) + " - " + merchant;
            if (!goods.isEmpty() && !goods.equals(merchant)) base += " " + goods;
            n.setDescription(fx.wrap(base, "CNY", amount));
        }
        // keep pay method for matching (0000 vs overseas)
        n.setReference(n.getReference() + "|pay:" + payMethod + "|st:" + status);
    }

    // ---- WeChat ----
    void normalizeWechat(Norm n, RawTransactionData raw, Map<String, Object> p) {
        String time = s(p.get("交易时间"), raw.getRawDate());
        n.setTransactionDate(parseDateTime(time));
        n.setTransactionTime(parseTime(time));
        String inout = s(p.get("收/支"), "");
        String amount = cleanAmount(s(p.get("金额(元)"), "0"));
        BigDecimal amt = num(amount);
        String merchant = s(p.get("交易对方"), "");
        String goods = s(p.get("商品"), "");
        String payMethod = s(p.get("支付方式"), "");
        String status = s(p.get("当前状态"), "");
        String type = s(p.get("交易类型"), "");
        n.setMerchant(merchant);
        n.setOriginalAmount(amt);
        n.setOriginalCurrency("CNY");
        n.setReference(s(p.get("交易单号"), raw.getSourceTransactionId()) + "|pay:" + payMethod + "|st:" + status);
        boolean isRedPacket = type.contains("红包");
        if ("收入".equals(inout)) {
            n.setType("INCOME");
            // Amount stays in original currency (see Alipay income above).
            n.setAmountMyr(null);
            n.setAccount(WalletAccount.Cash);
            n.setDescription("WeChat " + type + " - " + merchant);
        } else if (isRedPacket) {
            n.setType("EXPENSE");
            // Red packets are sub-RM amounts paid from 零钱; canonical spend is
            // negative in the original currency (Cash account, no MYR involved).
            n.setAmountMyr(null);
            n.setCanonicalAmount(amt.signum() > 0 ? amt.negate() : amt);
            n.setAccount(WalletAccount.Cash);
            n.setDescription("WeChat " + type + " - " + merchant + " (" + fx.format("CNY", amount) + ")");
        } else {
            n.setType("EXPENSE");
            n.setAmountMyr(null);
            // Canonical spend is negative in the original currency, like Alipay.
            n.setCanonicalAmount(amt.signum() > 0 ? amt.negate() : amt);
            // WeChat Pay is always a Cash account (MYR amount still resolved downstream).
            n.setAccount(WalletAccount.Cash);
            String base = "WeChat Pay - " + merchant;
            if (!goods.isEmpty() && !"/".equals(goods)) base += " " + goods;
            n.setDescription(fx.wrap(base, "CNY", amount));
        }
    }

    // ---- Grab ----
    void normalizeGrab(Norm n, RawTransactionData raw, Map<String, Object> p) {
        String dt = s(p.get("dateTime"), raw.getRawDate());
        n.setTransactionDate(parseGrabDate(dt));
        n.setTransactionTime(parseGrabTime(dt));
        String ccy = s(p.get("currency"), raw.getRawCurrency());
        String amount = s(p.get("amount"), "0").replace(",", "");
        BigDecimal amt = num(amount);
        String route = s(p.get("route"), raw.getRawDescription());
        String booking = s(p.get("bookingCode"), raw.getSourceTransactionId());
        n.setMerchant(firstSegment(route));
        n.setOriginalAmount(amt.abs());
        n.setOriginalCurrency(ccy.isEmpty() ? "MYR" : ccy);
        n.setReference(booking);
        n.setType("EXPENSE");
        n.setAccount(WalletAccount.Grab);
        if ("MYR".equalsIgnoreCase(ccy) || ccy.isEmpty()) {
            n.setAmountMyr(amt.negate());
            n.setDescription(route + " [" + booking + "]");
        } else {
            // Foreign Grab (THB): trips are spends, so the canonical amount is
            // negative in the original currency (matching the MYR branch above).
            // amountMyr stays unset (no MYR value exists); the override carries it.
            n.setAmountMyr(null);
            n.setCanonicalAmount(amt.signum() > 0 ? amt.negate() : amt);
            n.setDescription(fx.wrap(route + " [" + booking + "]", ccy, amount));
        }
    }

    // ---- TNG ----
    void normalizeTng(Norm n, RawTransactionData raw, Map<String, Object> p) {
        n.setTransactionDate(parseDateTime(raw.getRawDate()));
        n.setTransactionTime(parseTime(raw.getRawDate()));
        BigDecimal amt = num(raw.getRawAmount());
        n.setMerchant(extractTngMerchant(raw.getRawDescription()));
        n.setOriginalAmount(amt.abs());
        n.setOriginalCurrency(raw.getRawCurrency() == null ? "MYR" : raw.getRawCurrency());
        n.setReference(raw.getSourceTransactionId());
        n.setType(amt.signum() < 0 ? "EXPENSE" : "INCOME");
        n.setAccount(WalletAccount.Cash);
        n.setAmountMyr(amt);
        String desc = raw.getRawDescription();
        // Foreign TNG (e.g. Japan ¥500 → RM15.23): description must keep (¥500)
        n.setDescription(desc);
    }

    // ---- Maybank CC ----
    void normalizeMaybankCc(Norm n, RawTransactionData raw, Map<String, Object> p) {
        n.setTransactionDate(parseDateTime(raw.getRawDate()));
        n.setTransactionTime(null);
        String amtStr = raw.getRawAmount().replace(",", "");
        BigDecimal amt = num(amtStr);
        Object fAmt = p.get("foreignAmount");
        Object fCcy = p.get("foreignCurrency");
        String desc = s(p.get("description"), raw.getRawDescription());
        n.setMerchant(extractMaybankMerchant(desc));
        n.setReference("CC0000|" + s(p.get("postingDate"), "") + "|" + s(p.get("transactionDate"), ""));
        boolean credit = Boolean.TRUE.equals(p.get("credit")) || amtStr.startsWith("-");
        if (credit) {
            n.setType(amt.signum() < 0 ? "TRANSFER" : "INCOME");
            // Payments/refunds TO the card are money INTO the card account: positive,
            // netting against the negative spends. (TRANSFER, never spend.)
            n.setAmountMyr(amt.abs());
        } else {
            n.setType("EXPENSE");
            n.setAmountMyr(amt.negate().abs().negate()); // spend as negative
            // amount is already final RM (Bills rarely have reversal chains)
        }
        n.setAccount(WalletAccount.Maybank);
        if (fAmt != null && fCcy != null) {
            n.setOriginalAmount(num(String.valueOf(fAmt).replace(",", "")));
            n.setOriginalCurrency(String.valueOf(fCcy));
            n.setDescription(fx.wrap(cleanMaybankDesc(desc), String.valueOf(fCcy), String.valueOf(fAmt)));
        } else {
            n.setOriginalAmount(amt.abs());
            n.setOriginalCurrency("MYR");
            n.setDescription(cleanMaybankDesc(desc));
        }
    }

    // ---- Maybank SA ----
    void normalizeMaybankSa(Norm n, RawTransactionData raw, Map<String, Object> p) {
        n.setTransactionDate(parseDateTime(raw.getRawDate()));
        n.setTransactionTime(null);
        BigDecimal amt = num(raw.getRawAmount().replace(",", ""));
        String type = s(p.get("entryType"), "");
        String desc = raw.getRawDescription();
        n.setMerchant(extractMaybankSaMerchant(desc));
        n.setReference("SA|" + type);
        n.setOriginalAmount(amt.abs());
        n.setOriginalCurrency(detectSaCurrency(desc));
        n.setAccount(WalletAccount.Maybank);
        String upper = type.toUpperCase();
        if (upper.contains("PRE-AUTH REFUND")) {
            n.setType("TRANSFER");
            n.setAmountMyr(amt); // credit
        } else if (upper.contains("PRE-AUTH DEBIT")) {
            n.setType("EXPENSE");
            n.setAmountMyr(amt); // negative already
        } else if (upper.contains("SALE DEBIT") || upper.contains("PAYMENT VIA") || upper.contains("FPX")
                || upper.contains("TRANSFER") || upper.contains("IBK FUND") || upper.contains("PYMT")) {
            if (amt.signum() >= 0) {
                n.setType("INCOME");
                n.setAmountMyr(amt);
            } else {
                n.setType("EXPENSE");
                n.setAmountMyr(amt);
            }
        } else {
            n.setType(amt.signum() < 0 ? "EXPENSE" : "INCOME");
            n.setAmountMyr(amt);
        }
        n.setDescription(cleanMaybankDesc(desc));
    }

    // ---- helpers ----

    private String s(Object o, String def) {
        if (o == null) return def == null ? "" : def;
        String v = String.valueOf(o).replace("\t", "").strip();
        return v.isEmpty() && def != null ? def : v;
    }

    private BigDecimal num(String s) {
        try {
            if (s == null || s.isBlank()) return BigDecimal.ZERO;
            return new BigDecimal(s.strip().replace(",", "").replace("RM", "").replace("CN¥", "").replace("¥", ""));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    LocalDate parseDateTime(String s) {
        if (s == null) return null;
        s = s.strip();
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "yyyy/MM/dd", "dd MMM yyyy", "dd/MM/yy", "dd/MM/yyyy"};
        for (String pat : patterns) {
            try {
                if (pat.contains("H")) {
                    return java.time.LocalDateTime.parse(s.substring(0, Math.min(19, s.length())), DateTimeFormatter.ofPattern(pat)).toLocalDate();
                }
                if (s.length() >= 10 && pat.startsWith("yyyy")) {
                    return LocalDate.parse(s.substring(0, 10), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                }
            } catch (DateTimeParseException | StringIndexOutOfBoundsException ignored) {}
        }
        try {
            // dd/MM/yy
            if (s.matches("\\d{2}/\\d{2}/\\d{2}")) {
                String[] p = s.split("/");
                return LocalDate.of(2000 + Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
            }
            // d/M/yyyy or dd/MM/yyyy (TNG format)
            if (s.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
                String[] p = s.split("/");
                return LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
            }
            if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) return LocalDate.parse(s.substring(0, 10));
        } catch (Exception ignored) {}
        return null;
    }

    LocalTime parseTime(String s) {
        if (s == null || s.length() < 19) return null;
        try {
            return java.time.LocalDateTime.parse(s.substring(0, 19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toLocalTime();
        } catch (Exception e) {
            return null;
        }
    }

    LocalDate parseGrabDate(String s) {
        // "08 Sep 2026, 11:58AM"
        try {
            String datePart = s.split(",")[0].strip();
            return LocalDate.parse(datePart, DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.ENGLISH));
        } catch (Exception e) {
            return parseDateTime(s);
        }
    }

    LocalTime parseGrabTime(String s) {
        try {
            String[] parts = s.split(",");
            if (parts.length < 2) return null;
            String t = parts[1].strip().toUpperCase();
            return LocalTime.parse(t, DateTimeFormatter.ofPattern("hh:mma", java.util.Locale.ENGLISH));
        } catch (Exception e) {
            return null;
        }
    }

    private String firstSegment(String s) {
        if (s == null) return "";
        String[] parts = s.split("[|/]");
        return parts[0].strip();
    }

    /**
     * TNG descriptions are PDFBox-merged reference soup:
     * {@code <TYPE> <11-digit code> <MERCHANT WORDS> <references...>}, e.g.
     * {@code Transfer to Wallet 20260611111 Bean's Factory 202606111011...TNGOW3 ...}.
     * The merchant is the word run after the code up to the first reference token.
     * Rows without any merchant words fall back to the type (e.g. {@code PAYMENT});
     * timestamps still keep their deduplication keys distinct.
     */
    static String extractTngMerchant(String desc) {
        if (desc == null) return "";
        String text = desc.replace("→", " to ").replaceAll("\\[[A-Z]{3} [\\d.,]+\\]", " ");
        String type = "";
        java.util.regex.Matcher head = java.util.regex.Pattern.compile(
                "^(?:Success\\s+)?(Payment|Transfer to Wallet|Receive from Wallet|Refund|DUITNOW_RECEIVEFROM|DUITNOW_RECEI|DuitNow QR|Cashback)\\s*(\\d{8,})?\\s*")
                .matcher(text.strip());
        if (head.find()) {
            type = head.group(1);
            text = text.strip().substring(head.end());
        }
        List<String> kept = new ArrayList<>();
        for (String tok : text.strip().split("\\s+")) {
            if (tok.isEmpty()) continue;
            if (isTngReference(tok)) break;
            if (!tok.chars().anyMatch(c -> Character.isLetterOrDigit(c))) continue; // stray punctuation
            kept.add(tok);
            if (kept.size() >= 10) break;
        }
        if (!kept.isEmpty()) return String.join(" ", kept);
        if (type.startsWith("DUITNOW_RECEI")) return "DUITNOW_RECEIVEFROM";
        return type;
    }

    /** Reference soup: pure/long digit runs and known TNG reference prefixes. */
    private static boolean isTngReference(String tok) {
        if (tok.matches("^\\d+$")) return true;
        if (tok.matches("^\\d{8,}\\S*$")) return true;
        return tok.matches("^(TNGOW|GWP|MY\\d{3,}|21700010\\d*|2128013\\d*|2259023\\d*|VEFROM)\\S*");
    }

    private String extractMaybankMerchant(String desc) {
        if (desc == null) return "";
        // desc like "SALE DEBIT | ALP*GANKAIPIN / Shanghai CN / SALE DEBIT | 14.12-"
        for (String seg : desc.split("[|/]")) {
            String t = seg.strip();
            if (t.isEmpty()) continue;
            String u = t.toUpperCase();
            if (u.matches("^(SALE DEBIT|PRE-AUTH.*|PREAUTH.*|IBK FUND.*|TRANSFER.*|FPX.*|PAYMENT.*|PYMT.*|FUND TRANSFER.*)$")) continue;
            if (t.matches("^[\\d,]+\\.\\d{2}[+-]?$")) continue;
            if (t.matches("^(Shanghai|Beijing|Shenzhen|Guangzhou|Kuala Lumpur|Johor.*|Petaling.*|KULAI|JHB|CN|MY|MYS|Shanghai\\s+CN)$")) continue;
            if (t.matches("^[A-Z]{2,3} \\d+\\.\\d{2}$")) continue; // SGD 21.00
            if (t.matches("^\\d+$") || t.matches("^QR.*") || t.matches("^DUITNOW.*") || t.matches("^MAE.*") || t.matches("^[\\dQ]+$")) continue;
            return t;
        }
        return firstSegment(desc);
    }

    /**
     * SA variant of {@link #extractMaybankMerchant}: the raw description starts
     * with the entry-type segment ({@code "TYPE | merchant / ... | amt"}), which
     * must be dropped before splitting on {@code "/"}. Otherwise the {@code "/C"}
     * in {@code "A/C"} types (IBK FUND TFR FR A/C, TRANSFER/PYMT FROM A/C)
     * survives every filter below and becomes a phantom {@code "C"} merchant.
     * The CC shape ({@code "merchant ... | RM amt"}) is untouched — CC keeps
     * using {@link #extractMaybankMerchant}.
     */
    private String extractMaybankSaMerchant(String desc) {
        if (desc == null) return "";
        String body = desc;
        int bar = desc.indexOf('|');
        if (bar >= 0) body = desc.substring(bar + 1);
        for (String seg : body.split("[|/]")) {
            String t = seg.strip();
            if (t.isEmpty()) continue;
            String u = t.toUpperCase();
            if (u.matches("^(SALE DEBIT|PRE-AUTH.*|PREAUTH.*|IBK FUND.*|TRANSFER.*|FPX.*|PAYMENT.*|PYMT.*|FUND TRANSFER.*)$")) continue;
            if (t.matches("^[\\d,]+\\.\\d{2}[+-]?$")) continue;
            if (t.matches("^(Shanghai|Beijing|Shenzhen|Guangzhou|Kuala Lumpur|Johor.*|Petaling.*|KULAI|JHB|CN|MY|MYS|Shanghai\\s+CN)$")) continue;
            if (t.matches("^[A-Z]{2,3} \\d+\\.\\d{2}$")) continue; // SGD 21.00
            if (t.matches("^\\d+$") || t.matches("^QR.*") || t.matches("^DUITNOW.*") || t.matches("^MAE.*") || t.matches("^[\\dQ]+$")) continue;
            if (u.matches("^(ENDING BALANCE|TOTAL CREDIT|TOTAL DEBIT|BEGINNING BALANCE).*")) continue;
            return t;
        }
        return firstSegment(desc);
    }

    private String cleanMaybankDesc(String desc) {
        if (desc == null) return "";
        return desc.replaceAll("\\s+", " ").strip();
    }

    private String detectSaCurrency(String desc) {
        if (desc == null) return "MYR";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(SGD|USD|THB|CNY|JPY)\\b\\s*([\\d,]+\\.\\d{2})").matcher(desc);
        if (m.find()) return m.group(1);
        return "MYR";
    }

    /** Volatile per-source normalization scratchpad. Never persisted. */
    static class Norm {
        private SourceType source;
        private java.time.LocalDate transactionDate;
        private java.time.LocalTime transactionTime;
        private WalletAccount account;
        private String type;
        private java.math.BigDecimal amountMyr;
        private java.math.BigDecimal originalAmount;
        private String originalCurrency;
        private String description;
        private String merchant;
        private String category;
        private String reference;
        private java.math.BigDecimal canonicalAmount;
        public SourceType getSource() { return source; }
        public void setSource(SourceType v) { this.source = v; }
        public java.time.LocalDate getTransactionDate() { return transactionDate; }
        public void setTransactionDate(java.time.LocalDate v) { this.transactionDate = v; }
        public java.time.LocalTime getTransactionTime() { return transactionTime; }
        public void setTransactionTime(java.time.LocalTime v) { this.transactionTime = v; }
        public WalletAccount getAccount() { return account; }
        public void setAccount(WalletAccount v) { this.account = v; }
        public String getType() { return type; }
        public void setType(String v) { this.type = v; }
        public java.math.BigDecimal getAmountMyr() { return amountMyr; }
        public void setAmountMyr(java.math.BigDecimal v) { this.amountMyr = v; }
        public java.math.BigDecimal getOriginalAmount() { return originalAmount; }
        public void setOriginalAmount(java.math.BigDecimal v) { this.originalAmount = v; }
        public String getOriginalCurrency() { return originalCurrency; }
        public void setOriginalCurrency(String v) { this.originalCurrency = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { this.description = v; }
        public String getMerchant() { return merchant; }
        public void setMerchant(String v) { this.merchant = v; }
        public String getCategory() { return category; }
        public void setCategory(String v) { this.category = v; }
        public String getReference() { return reference; }
        public void setReference(String v) { this.reference = v; }
        public java.math.BigDecimal getCanonicalAmount() { return canonicalAmount; }
        public void setCanonicalAmount(java.math.BigDecimal v) { this.canonicalAmount = v; }
    }
}
