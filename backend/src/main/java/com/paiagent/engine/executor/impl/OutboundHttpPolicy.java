package com.paiagent.engine.executor.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Component
public class OutboundHttpPolicy {

    private final List<String> allowedHostPatterns;

    public OutboundHttpPolicy(
            @Value("${paiops.http.allowed-host-patterns:localhost,127.0.0.1,prometheus,loki,kubernetes.default.svc}")
            String configuredPatterns) {
        this.allowedHostPatterns = Arrays.stream(configuredPatterns.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    public void validate(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("请求地址缺少有效主机名");
        }
        if (allowedHostPatterns.stream().noneMatch(pattern -> matches(pattern, host))) {
            throw new SecurityException("目标主机不在 PAIOPS_HTTP_ALLOWED_HOSTS 白名单中: " + host);
        }
    }

    private boolean matches(String pattern, String host) {
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1).toLowerCase();
            return host.toLowerCase().endsWith(suffix);
        }
        return pattern.equalsIgnoreCase(host);
    }
}
