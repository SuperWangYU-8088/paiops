package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.ConnectorCredentialService;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class KubernetesQueryNodeExecutor implements NodeExecutor {

    private static final Set<String> ALLOWED_RESOURCES = Set.of(
            "pods", "deployments", "statefulsets", "daemonsets",
            "events", "services", "endpoints", "configmaps", "replicasets"
    );

    private final OutboundHttpPolicy httpPolicy;
    private final ConnectorCredentialService credentialService;

    public KubernetesQueryNodeExecutor(OutboundHttpPolicy httpPolicy,
                                       ConnectorCredentialService credentialService) {
        this.httpPolicy = httpPolicy;
        this.credentialService = credentialService;
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
        Map<String, String> credential = credential(node);
        String endpoint = OpsHttpSupport.requiredText(
                firstText(OpsHttpSupport.text(node, input, "endpoint", null), credential.get("endpoint")),
                "Kubernetes 查询节点缺少 API 地址"
        );
        String namespace = firstText(OpsHttpSupport.text(node, input, "namespace", null),
                credential.get("namespace"), "default");
        String resource = firstText(OpsHttpSupport.text(node, input, "resource", null), "pods").toLowerCase();
        if (!ALLOWED_RESOURCES.contains(resource)) {
            throw new SecurityException("Kubernetes 资源类型不在只读白名单中: " + resource);
        }
        String name = OpsHttpSupport.text(node, input, "resourceName", null);
        String labelSelector = OpsHttpSupport.text(node, input, "labelSelector", null);
        int timeoutSeconds = OpsHttpSupport.integer(node, "timeoutSeconds", 10, 1, 30);
        String url = buildUrl(endpoint, namespace, resource, name, labelSelector);
        httpPolicy.validate(url);

        HttpRequest.Builder builder = OpsHttpSupport.requestBuilder(url, timeoutSeconds)
                .GET()
                .header("Accept", "application/json");
        // Kubernetes 令牌只允许从加密凭证读取，不能由 Runbook 配置或上游节点输入覆盖。
        // 否则令牌会随着流程 JSON、快照或调试日志扩散，破坏最小暴露原则。
        OpsHttpSupport.applyCredentialAuth(builder, credential);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("namespace", namespace);
        output.put("resource", resource);
        try {
            HttpResponse<String> response = OpsHttpSupport.createClient(timeoutSeconds)
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString());
            output.put("statusCode", response.statusCode());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                output.put("success", false);
                output.put("error", "Kubernetes API 返回 HTTP " + response.statusCode());
                output.put("body", OpsHttpSupport.truncate(response.body()));
                return output;
            }
            JSONObject payload = JSON.parseObject(response.body());
            JSONArray items = payload.getJSONArray("items");
            if (items == null) {
                items = new JSONArray();
                items.add(payload);
            }
            output.put("success", true);
            output.put("count", items.size());
            output.put("items", items);
            output.put("resourceVersion", payload.getJSONObject("metadata") == null
                    ? null
                    : payload.getJSONObject("metadata").getString("resourceVersion"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            output.put("success", false);
            output.put("error", "Kubernetes 查询被中断");
        } catch (Exception exception) {
            output.put("success", false);
            output.put("error", exception.getMessage());
        }
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        return "kubernetes_query";
    }

    private String buildUrl(String endpoint, String namespace, String resource,
                            String name, String labelSelector) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String apiPrefix = Set.of("deployments", "statefulsets", "daemonsets", "replicasets").contains(resource)
                ? "/apis/apps/v1"
                : "/api/v1";
        StringBuilder url = new StringBuilder(base)
                .append(apiPrefix)
                .append("/namespaces/")
                .append(encodePath(namespace))
                .append("/")
                .append(resource);
        if (name != null) {
            url.append("/").append(encodePath(name));
        } else if (labelSelector != null) {
            url.append("?labelSelector=")
                    .append(URLEncoder.encode(labelSelector, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Map<String, String> credential(WorkflowNode node) {
        Long id = OpsHttpSupport.longValue(node, "credentialId");
        return id == null ? Map.of() : credentialService.getSecretData(id);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
