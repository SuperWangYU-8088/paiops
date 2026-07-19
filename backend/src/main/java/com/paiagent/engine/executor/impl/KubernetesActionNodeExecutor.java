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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Kubernetes 高风险动作执行器。
 *
 * <p>仅开放扩缩容、滚动重启和显式镜像回滚三种结构化动作，不接受任意 URL、
 * 任意 JSON Patch 或 shell。执行前由 {@code NodeSafetyPolicy} 回查人工审批，
 * 本执行器再负责参数白名单、默认 dry-run、前后快照和幂等变更标识。</p>
 */
@Component
public class KubernetesActionNodeExecutor implements NodeExecutor {

    private static final Pattern K8S_NAME = Pattern.compile("[a-z0-9]([-a-z0-9.]*[a-z0-9])?");
    private static final Pattern CONTAINER_NAME = Pattern.compile("[a-z0-9]([-a-z0-9.]*[a-z0-9])?");

    private final OutboundHttpPolicy httpPolicy;
    private final ConnectorCredentialService credentialService;

    public KubernetesActionNodeExecutor(OutboundHttpPolicy httpPolicy,
                                        ConnectorCredentialService credentialService) {
        this.httpPolicy = httpPolicy;
        this.credentialService = credentialService;
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        Map<String, String> credential = credential(node);
        String endpoint = required(firstText(
                OpsHttpSupport.text(node, input, "endpoint", null), credential.get("endpoint")),
                "Kubernetes 动作节点缺少 API 地址");
        String namespace = validateName(firstText(
                OpsHttpSupport.text(node, input, "namespace", null),
                credential.get("namespace"), "default"), "namespace");
        String deployment = validateName(
                OpsHttpSupport.text(node, input, "deployment", null), "deployment");
        boolean dryRun = OpsHttpSupport.bool(node, "dryRun", true);
        int timeoutSeconds = OpsHttpSupport.integer(node, "timeoutSeconds", 20, 1, 60);

        String deploymentUrl = deploymentUrl(endpoint, namespace, deployment);
        httpPolicy.validate(deploymentUrl);
        JSONObject before = sendJson("GET", deploymentUrl, null, credential, timeoutSeconds);
        JSONObject patch = buildPatch(node, input, before);
        String actionUrl = actionUrl(node.getType(), deploymentUrl, dryRun);
        httpPolicy.validate(actionUrl);

        JSONObject apiResponse = sendJson("PATCH", actionUrl, patch, credential, timeoutSeconds);
        JSONObject after = dryRun
                ? apiResponse
                : sendJson("GET", deploymentUrl, null, credential, timeoutSeconds);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("action", node.getType());
        output.put("dryRun", dryRun);
        output.put("namespace", namespace);
        output.put("deployment", deployment);
        output.put("changeId", firstText(
                OpsHttpSupport.text(node, input, "changeId", null),
                "execution-" + input.get("__executionId__")));
        output.put("before", snapshot(before));
        output.put("after", snapshot(after));
        output.put("executedAt", Instant.now().toString());
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        // 三个节点共用同一个实现时，工厂仍需要独立注册；由轻量子类完成注册。
        return "kubernetes_scale";
    }

    protected JSONObject buildPatch(WorkflowNode node, Map<String, Object> input, JSONObject before) {
        return switch (node.getType()) {
            case "kubernetes_scale" -> scalePatch(node, input);
            case "kubernetes_restart" -> restartPatch(node, input);
            case "kubernetes_rollback" -> rollbackPatch(node, input, before);
            default -> throw new IllegalArgumentException("不支持的 Kubernetes 动作: " + node.getType());
        };
    }

    private JSONObject scalePatch(WorkflowNode node, Map<String, Object> input) {
        String raw = OpsHttpSupport.text(node, input, "replicas", null);
        if (raw == null) {
            throw new IllegalArgumentException("扩缩容节点缺少 replicas");
        }
        int replicas;
        try {
            replicas = Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("replicas 必须是整数", exception);
        }
        if (replicas < 0 || replicas > 200) {
            throw new IllegalArgumentException("replicas 必须在 0 到 200 之间");
        }
        return JSON.parseObject("{\"spec\":{\"replicas\":" + replicas + "}}");
    }

    private JSONObject restartPatch(WorkflowNode node, Map<String, Object> input) {
        String changeId = required(firstText(
                OpsHttpSupport.text(node, input, "changeId", null),
                input.get("__executionId__") == null ? null : "execution-" + input.get("__executionId__")),
                "滚动重启必须提供稳定的 changeId");
        if (changeId.length() > 120) {
            throw new IllegalArgumentException("changeId 长度不能超过 120");
        }
        JSONObject annotations = new JSONObject();
        annotations.put("kubectl.kubernetes.io/restartedAt", Instant.now().toString());
        annotations.put("paiops.io/restart-change-id", changeId);
        JSONObject metadata = new JSONObject();
        metadata.put("annotations", annotations);
        JSONObject template = new JSONObject();
        template.put("metadata", metadata);
        JSONObject spec = new JSONObject();
        spec.put("template", template);
        JSONObject patch = new JSONObject();
        patch.put("spec", spec);
        return patch;
    }

    private JSONObject rollbackPatch(WorkflowNode node, Map<String, Object> input, JSONObject before) {
        String container = validateContainerName(
                OpsHttpSupport.text(node, input, "container", null));
        String targetImage = required(
                OpsHttpSupport.text(node, input, "targetImage", null),
                "镜像回滚节点缺少 targetImage");
        if (targetImage.length() > 512 || targetImage.contains("\n") || targetImage.contains("\r")) {
            throw new IllegalArgumentException("targetImage 格式无效");
        }

        // 先确认目标容器确实存在，避免拼错名称后创建意外容器或产生无效变更。
        JSONArray containers = before.getJSONObject("spec")
                .getJSONObject("template")
                .getJSONObject("spec")
                .getJSONArray("containers");
        boolean exists = containers != null && containers.stream()
                .filter(JSONObject.class::isInstance)
                .map(JSONObject.class::cast)
                .anyMatch(item -> container.equals(item.getString("name")));
        if (!exists) {
            throw new IllegalArgumentException("Deployment 中不存在目标容器: " + container);
        }

        JSONObject containerPatch = new JSONObject();
        containerPatch.put("name", container);
        containerPatch.put("image", targetImage);
        JSONObject podSpec = new JSONObject();
        podSpec.put("containers", new JSONArray(java.util.List.of(containerPatch)));
        JSONObject template = new JSONObject();
        template.put("spec", podSpec);
        JSONObject spec = new JSONObject();
        spec.put("template", template);
        JSONObject patch = new JSONObject();
        patch.put("spec", spec);
        return patch;
    }

    private JSONObject sendJson(String method,
                                String url,
                                JSONObject body,
                                Map<String, String> credential,
                                int timeoutSeconds) throws Exception {
        HttpRequest.Builder request = OpsHttpSupport.requestBuilder(url, timeoutSeconds)
                .header("Accept", "application/json");
        OpsHttpSupport.applyCredentialAuth(request, credential);
        if ("PATCH".equals(method)) {
            request.header("Content-Type", "application/merge-patch+json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toJSONString()));
        } else {
            request.GET();
        }

        HttpResponse<String> response;
        try {
            response = OpsHttpSupport.createClient(timeoutSeconds)
                    .send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kubernetes 动作被取消", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Kubernetes API 返回 HTTP " + response.statusCode()
                    + ": " + OpsHttpSupport.truncate(response.body()));
        }
        return JSON.parseObject(response.body());
    }

    private Map<String, Object> snapshot(JSONObject deployment) {
        Map<String, Object> result = new LinkedHashMap<>();
        JSONObject metadata = deployment.getJSONObject("metadata");
        JSONObject spec = deployment.getJSONObject("spec");
        JSONObject status = deployment.getJSONObject("status");
        result.put("resourceVersion", metadata == null ? null : metadata.getString("resourceVersion"));
        result.put("generation", metadata == null ? null : metadata.getLong("generation"));
        result.put("replicas", spec == null ? null : spec.getInteger("replicas"));
        result.put("readyReplicas", status == null ? null : status.getInteger("readyReplicas"));
        result.put("availableReplicas", status == null ? null : status.getInteger("availableReplicas"));

        Map<String, String> images = new LinkedHashMap<>();
        try {
            JSONArray containers = spec.getJSONObject("template")
                    .getJSONObject("spec")
                    .getJSONArray("containers");
            if (containers != null) {
                containers.stream().filter(JSONObject.class::isInstance).map(JSONObject.class::cast)
                        .forEach(container -> images.put(
                                container.getString("name"), container.getString("image")));
            }
        } catch (Exception ignored) {
            // 异常/裁剪响应没有容器信息时仍保留其余快照。
        }
        result.put("images", images);
        return result;
    }

    private String actionUrl(String nodeType, String deploymentUrl, boolean dryRun) {
        String base = "kubernetes_scale".equals(nodeType)
                ? deploymentUrl + "/scale"
                : deploymentUrl;
        return dryRun ? base + "?dryRun=All" : base;
    }

    private String deploymentUrl(String endpoint, String namespace, String deployment) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return base + "/apis/apps/v1/namespaces/" + encode(namespace)
                + "/deployments/" + encode(deployment);
    }

    private Map<String, String> credential(WorkflowNode node) {
        Long id = OpsHttpSupport.longValue(node, "credentialId");
        if (id == null) {
            throw new IllegalArgumentException("Kubernetes 动作必须选择加密凭证");
        }
        return credentialService.getSecretData(id);
    }

    private String validateName(String value, String field) {
        String resolved = required(value, field + " 不能为空");
        if (resolved.length() > 253 || !K8S_NAME.matcher(resolved).matches()) {
            throw new IllegalArgumentException(field + " 不是有效的 Kubernetes 名称");
        }
        return resolved;
    }

    private String validateContainerName(String value) {
        String resolved = required(value, "container 不能为空");
        if (resolved.length() > 63 || !CONTAINER_NAME.matcher(resolved).matches()) {
            throw new IllegalArgumentException("container 不是有效的容器名称");
        }
        return resolved;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String required(String value, String message) {
        return OpsHttpSupport.requiredText(value, message);
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
