package com.transactionprocessor.transactionprocessor.domain;

public enum SourceType {
    MAYBANK_CC,
    MAYBANK_SA,
    MAYBANK,      // legacy generic fallback (pre-bucket-split data / unresolvable bucket)
    TNG,
    GRAB,
    ALIPAY,
    WECHATPAY
}
