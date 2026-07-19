package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * Runbook 持久化密钥检查。
 *
 * <p>工作流 JSON 会被长期保存、复制、导出并进入审计链路，所以其中不能夹带
 * API Key、Token 或密码。节点应只保存 {@code credentialId}/{@code configId} 引用，
 * 真正的密钥由凭证中心在执行时按需解密。</p>
 */
@Component
public class WorkflowSecretPolicy {

    private static final Set<String> SECRET_KEYS = Set.of(
            "apikey", "api_key", "token", "accesstoken", "access_token",
            "password", "passwd", "secret", "clientsecret", "client_secret",
            "authorization", "privatekey", "private_key"
    );

    public void assertNoInlineSecrets(String flowData) {
        final JSONObject root;
        try {
            root = JSON.parseObject(flowData);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Runbook 配置不是有效 JSON", exception);
        }

        JSONArray nodes = root.getJSONArray("nodes");
        if (nodes == null) {
            return;
        }
        for (Object item : nodes) {
            if (!(item instanceof JSONObject node)) {
                continue;
            }
            inspect(node.get("data"), "节点 " + firstText(node.getString("id"), "unknown"));
        }
    }

    private void inspect(Object value, String location) {
        if (value instanceof JSONObject object) {
            object.forEach((key, child) -> {
                String normalized = key.replace("-", "").toLowerCase(Locale.ROOT);
                if (SECRET_KEYS.contains(normalized) && hasValue(child)) {
                    throw new IllegalArgumentException(
                            location + " 包含明文敏感字段 " + key + "，请改用凭证中心或全局模型配置引用");
                }
                if ("headers".equalsIgnoreCase(key) && child instanceof String text
                        && StringUtils.hasText(text)) {
                    try {
                        inspect(JSON.parseObject(text), location + ".headers");
                    } catch (IllegalArgumentException securityException) {
                        throw securityException;
                    } catch (Exception ignored) {
                        // headers 的格式校验由具体节点完成；这里只处理能解析的 JSON。
                    }
                }
                inspect(child, location + "." + key);
            });
        } else if (value instanceof JSONArray array) {
            for (Object child : array) {
                inspect(child, location);
            }
        }
    }

    private boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        return !(value instanceof String text) || StringUtils.hasText(text);
    }

    private String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
