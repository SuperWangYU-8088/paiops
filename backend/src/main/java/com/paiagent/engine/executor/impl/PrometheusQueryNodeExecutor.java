package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.ConnectorCredentialService;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PrometheusQueryNodeExecutor implements NodeExecutor {

    private final OutboundHttpPolicy httpPolicy;
    private final ConnectorCredentialService credentialService;

    public PrometheusQueryNodeExecutor(OutboundHttpPolicy httpPolicy,
                                       ConnectorCredentialService credentialService) {
        this.httpPolicy = httpPolicy;
        this.credentialService = credentialService;
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
        Long credentialId = OpsHttpSupport.longValue(node, "credentialId");
        Map<String, String> credential = credentialId == null
                ? Map.of()
                : credentialService.getSecretData(credentialId);
        String endpoint = OpsHttpSupport.requiredText(
                firstText(OpsHttpSupport.text(node, input, "endpoint", null), credential.get("endpoint")),
                "Prometheus 查询节点缺少 endpoint"
        );
        String query = OpsHttpSupport.requiredText(
                OpsHttpSupport.text(node, input, "query", "input"),
                "Prometheus 查询节点缺少 PromQL"
        );
        int timeoutSeconds = OpsHttpSupport.integer(node, "timeoutSeconds", 10, 1, 30);
        String requestUrl = buildQueryUrl(endpoint, query);
        httpPolicy.validate(requestUrl);

        HttpRequest.Builder requestBuilder = OpsHttpSupport.requestBuilder(requestUrl, timeoutSeconds)
                .GET()
                .header("Accept", "application/json");
        OpsHttpSupport.applyHeaders(requestBuilder, OpsHttpSupport.headers(node));
        OpsHttpSupport.applyCredentialAuth(requestBuilder, credential);

        long startedAt = System.nanoTime();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("endpoint", endpoint);
        output.put("query", query);
        try {
            HttpResponse<String> response = OpsHttpSupport.createClient(timeoutSeconds)
                    .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            output.put("statusCode", response.statusCode());
            output.put("responseTimeMs", elapsedMillis(startedAt));
            parseResponse(output, response);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            output.put("success", false);
            output.put("responseTimeMs", elapsedMillis(startedAt));
            output.put("error", "Prometheus 查询被中断");
        } catch (Exception exception) {
            output.put("success", false);
            output.put("responseTimeMs", elapsedMillis(startedAt));
            output.put("error", exception.getMessage());
        }
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        return "prometheus_query";
    }

    private String buildQueryUrl(String endpoint, String query) {
        URI baseUri = URI.create(endpoint.trim());
        String path = baseUri.getPath() == null ? "" : baseUri.getPath();
        String suffix = path.endsWith("/api/v1/query") ? "" : "/api/v1/query";
        String base = endpoint.endsWith("/") && !suffix.isEmpty()
                ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        return base + suffix + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    private void parseResponse(Map<String, Object> output, HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            output.put("success", false);
            output.put("error", "Prometheus 返回 HTTP " + response.statusCode());
            output.put("body", OpsHttpSupport.truncate(response.body()));
            return;
        }

        JSONObject payload = JSON.parseObject(response.body());
        boolean success = "success".equalsIgnoreCase(payload.getString("status"));
        output.put("success", success);
        if (!success) {
            output.put("error", payload.getString("error"));
            output.put("errorType", payload.getString("errorType"));
            return;
        }

        JSONObject data = payload.getJSONObject("data");
        JSONArray result = data == null ? null : data.getJSONArray("result");
        output.put("resultType", data == null ? null : data.getString("resultType"));
        output.put("resultCount", result == null ? 0 : result.size());
        output.put("result", result == null ? new JSONArray() : result);
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
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
