package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.engine.model.WorkflowNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OpsHttpSupport {

    static final int MAX_RESPONSE_LENGTH = 64 * 1024;
    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "secret", "apikey", "token", "authorization",
            "bearertoken", "kubeconfig", "privatekey"
    );

    private OpsHttpSupport() {
    }

    static HttpClient createClient(int timeoutSeconds) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                // 禁止自动重定向：初始 URL 虽已通过白名单，但跳转目标尚未重新校验，
                // 自动跟随会形成访问云元数据或内网管理面的 SSRF 绕过。
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    static HttpRequest.Builder requestBuilder(String url, int timeoutSeconds) {
        URI uri = URI.create(requiredText(url, "请求地址不能为空"));
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("仅支持 HTTP 或 HTTPS 地址");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("请求地址缺少有效主机名");
        }
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", "PaiOps/1.0");
    }

    static String text(WorkflowNode node, Map<String, Object> input, String field, String fallbackField) {
        String inputValue = asText(input.get(field));
        if (inputValue != null) {
            return inputValue;
        }

        Map<String, Object> data = node.getData();
        String configuredValue = data == null ? null : asText(data.get(field));
        if (configuredValue != null) {
            return configuredValue;
        }

        return fallbackField == null ? null : asText(input.get(fallbackField));
    }

    static int integer(WorkflowNode node, String field, int defaultValue, int minimum, int maximum) {
        Map<String, Object> data = node.getData();
        Object value = data == null ? null : data.get(field);
        int resolved = defaultValue;
        if (value instanceof Number number) {
            resolved = number.intValue();
        } else if (asText(value) != null) {
            resolved = Integer.parseInt(asText(value));
        }
        return Math.max(minimum, Math.min(maximum, resolved));
    }

    static boolean bool(WorkflowNode node, String field, boolean defaultValue) {
        Map<String, Object> data = node.getData();
        Object value = data == null ? null : data.get(field);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String text = asText(value);
        return text == null ? defaultValue : Boolean.parseBoolean(text);
    }

    static Long longValue(WorkflowNode node, String field) {
        Map<String, Object> data = node.getData();
        Object value = data == null ? null : data.get(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = asText(value);
        return text == null ? null : Long.parseLong(text);
    }

    static Map<String, String> headers(WorkflowNode node) {
        Map<String, Object> data = node.getData();
        Object value = data == null ? null : data.get("headers");
        if (value == null) {
            return Map.of();
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, headerValue) -> putHeader(headers, key, headerValue));
            return headers;
        }

        String json = asText(value);
        if (json == null) {
            return Map.of();
        }
        JSONObject object = JSON.parseObject(json);
        object.forEach((key, headerValue) -> putHeader(headers, key, headerValue));
        return headers;
    }

    static void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        headers.forEach((name, value) -> {
            if (!"host".equalsIgnoreCase(name) && !"content-length".equalsIgnoreCase(name)) {
                builder.header(name, value);
            }
        });
    }

    /**
     * 从 AES-GCM 加密凭证中应用认证信息。
     * 优先级为 Bearer Token、完整 Authorization、用户名密码；敏感值不进入节点 JSON。
     */
    static void applyCredentialAuth(HttpRequest.Builder builder, Map<String, String> credential) {
        if (credential == null || credential.isEmpty()) {
            return;
        }
        String bearer = firstCredentialText(credential, "bearerToken", "token");
        String authorization = firstCredentialText(credential, "authorization");
        String username = firstCredentialText(credential, "username");
        String password = firstCredentialText(credential, "password");
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + safeHeaderValue(bearer));
        } else if (authorization != null) {
            builder.header("Authorization", safeHeaderValue(authorization));
        } else if (username != null && password != null) {
            String basic = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        }

        String tenantId = firstCredentialText(credential, "tenantId", "orgId");
        if (tenantId != null) {
            builder.header("X-Scope-OrgID", safeHeaderValue(tenantId));
        }
    }

    static String truncate(String value) {
        if (value == null || value.length() <= MAX_RESPONSE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_RESPONSE_LENGTH);
    }

    static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    static Object safeContext(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                String normalized = name.replace("_", "").replace("-", "").toLowerCase();
                boolean secret = SECRET_KEYS.stream().anyMatch(normalized::contains);
                sanitized.put(name, secret ? "[REDACTED]" : safeContext(item));
            });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(OpsHttpSupport::safeContext).toList();
        }
        return value;
    }

    private static void putHeader(Map<String, String> headers, Object key, Object value) {
        String name = asText(key);
        String headerValue = asText(value);
        if (name != null && headerValue != null) {
            if ("authorization".equalsIgnoreCase(name)
                    || "cookie".equalsIgnoreCase(name)
                    || "x-api-key".equalsIgnoreCase(name)) {
                throw new SecurityException("敏感请求头必须通过加密凭证配置，不能保存在 Runbook 中");
            }
            headers.put(name, headerValue);
        }
    }

    private static String firstCredentialText(Map<String, String> credential, String... names) {
        for (String name : names) {
            String value = asText(credential.get(name));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String safeHeaderValue(String value) {
        if (value.length() > 8192 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new SecurityException("凭证包含非法请求头字符");
        }
        return value;
    }
}
