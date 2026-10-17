package com.transactionprocessor.transactionprocessor.upload;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class StatementBucketTest {

    @Test
    void eachBucketDeclaresADifferentSource() {
        assertEquals(SourceType.MAYBANK_SA, StatementBucket.MAYBANK_SA.toSourceType());
        assertEquals(SourceType.MAYBANK_CC, StatementBucket.MAYBANK_CC.toSourceType());
        assertEquals(SourceType.GRAB, StatementBucket.GRAB.toSourceType());
        assertEquals(SourceType.TNG, StatementBucket.TNG.toSourceType());
        assertEquals(SourceType.ALIPAY, StatementBucket.ALIPAY.toSourceType());
        assertEquals(SourceType.WECHATPAY, StatementBucket.WECHAT.toSourceType());
        assertEquals(6, Arrays.stream(StatementBucket.values())
                .map(StatementBucket::toSourceType).distinct().count());
    }

    @Test
    void legacyMaybankBucketNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StatementBucket.fromString("MAYBANK"));
        assertThrows(IllegalArgumentException.class, () -> StatementBucket.fromString("INVALID"));
    }
}
