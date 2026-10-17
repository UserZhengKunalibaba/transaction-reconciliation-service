package com.transactionprocessor.transactionprocessor.parser;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import org.springframework.stereotype.Component;

import java.util.List;

/** Picks the right parser by supports(). Order matters: specific before generic. */
@Component
public class ParserRegistry {

    private final AlipayParser alipay;
    private final WechatPayParser wechat;
    private final MaybankCreditCardParser maybankCc;
    private final MaybankSavingsParser maybankSa;
    private final GrabParser grab;
    private final TngParser tng;

    public ParserRegistry(AlipayParser alipay, WechatPayParser wechat,
                          MaybankCreditCardParser maybankCc, MaybankSavingsParser maybankSa,
                          GrabParser grab, TngParser tng) {
        this.alipay = alipay;
        this.wechat = wechat;
        this.maybankCc = maybankCc;
        this.maybankSa = maybankSa;
        this.grab = grab;
        this.tng = tng;
    }

    public StatementParser find(StatementFileDescriptor file) {
        List<StatementParser> all = List.of(alipay, wechat, maybankCc, maybankSa, grab, tng);
        for (StatementParser p : all) {
            try {
                if (p.supports(file)) return p;
            } catch (Exception ignored) {}
        }
        // Fallback by extension/path hints
        String n = file.getFilename().toLowerCase();
        String parent = file.getPath().toString().toLowerCase();
        if (n.endsWith(".csv") && (parent.contains("alipay") || n.contains("支付宝"))) return alipay;
        if ((n.endsWith(".xlsx") || n.endsWith(".xls")) && (parent.contains("wechat") || parent.contains("微信"))) return wechat;
        if (n.endsWith(".pdf") && parent.contains("maybank")) {
            if (n.contains("bill")) return maybankCc;
            return maybankSa;
        }
        if (n.endsWith(".pdf") && parent.contains("grab")) return grab;
        if (n.endsWith(".pdf") && (parent.contains("tng") || parent.contains("touch"))) return tng;
        return null;
    }

    public SourceType sourceOf(StatementParser parser, StatementFileDescriptor file) {
        if (parser instanceof AlipayParser) return SourceType.ALIPAY;
        if (parser instanceof WechatPayParser) return SourceType.WECHATPAY;
        if (parser instanceof MaybankCreditCardParser) return SourceType.MAYBANK_CC;
        if (parser instanceof MaybankSavingsParser) return SourceType.MAYBANK_SA;
        if (parser instanceof GrabParser) return SourceType.GRAB;
        if (parser instanceof TngParser) return SourceType.TNG;
        return SourceType.MAYBANK;
    }
}
