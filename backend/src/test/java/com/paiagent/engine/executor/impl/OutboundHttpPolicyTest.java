package com.paiagent.engine.executor.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboundHttpPolicyTest {

    @Test
    void allowsExactHostAndSubdomainPattern() {
        OutboundHttpPolicy policy = new OutboundHttpPolicy(
                "localhost,127.0.0.1,*.ops.example.com"
        );

        assertDoesNotThrow(() -> policy.validate("http://localhost:9090/api/v1/query"));
        assertDoesNotThrow(() -> policy.validate("https://prometheus.ops.example.com/api"));
    }

    @Test
    void rejectsUnlistedHostAndSuffixConfusion() {
        OutboundHttpPolicy policy = new OutboundHttpPolicy("*.ops.example.com");

        assertThrows(SecurityException.class,
                () -> policy.validate("https://attacker.example.com"));
        // 字符串后缀必须包含前导点，example.com.evil 不能伪装成受信子域名。
        assertThrows(SecurityException.class,
                () -> policy.validate("https://ops.example.com.evil"));
    }

    @Test
    void rejectsUrlWithoutHost() {
        OutboundHttpPolicy policy = new OutboundHttpPolicy("localhost");

        assertThrows(IllegalArgumentException.class, () -> policy.validate("file:///etc/passwd"));
    }
}
