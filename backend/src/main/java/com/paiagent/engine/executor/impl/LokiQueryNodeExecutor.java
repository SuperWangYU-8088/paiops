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

@Component
public class LokiQueryNodeExecutor implements NodeExecutor {

    private final OutboundHttpPolicy httpPolicy;
    private final ConnectorCredentialService credentialService;

    public LokiQueryNodeExecutor(OutboundHttpPolicy httpPolicy,
                                 ConnectorCredentialService credentialService) {
        this.httpPolicy = httpPolicy;
        this.credentialService = credentialService;
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
        Map<String, String> credential = credential(node);
        String endpoint = OpsHttpSupport.requiredText(
                firstText(OpsHttpSupport.text(node, input, "endpoint", null), credential.get("endpoint")),
                "Loki 查询节点缺少 endpoint"
        );
        String query = OpsHttpSupport.requiredText(
                OpsHttpSupport.text(node, input, "query", "input"),
                "Loki 查询节点缺少 LogQL"
        );
        int timeoutSeconds = OpsHttpSupport.integer(node, "timeoutSeconds", 10, 1, 30);
        int limit = OpsHttpSupport.integer(node, "limit", 100, 1, 1000);
        String direction = OpsHttpSupport.text(node, input, "direction", null);
        direction = "forward".equalsIgnoreCase(direction) ? "forward" : "backward";

        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String requestUrl = base + "/loki/api/v1/query_range?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=" + limit
                + "&direction=" + direction;
        httpPolicy.validate(requestUrl);

        HttpRequest.Builder requestBuilder = OpsHttpSupport.requestBuilder(requestUrl, timeoutSeconds)
                .GET()
                .header("Accept", "application/json");
        OpsHttpSupport.applyHeaders(requestBuilder, OpsHttpSupport.headers(node));
        // 鉴权信息只能来自应用层加密凭证，不能进入可导出、可审计的 Runbook 数据。
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
            output.put("error", "Loki 查询被中断");
        } catch (Exception exception) {
            output.put("success", false);
            output.put("error", exception.getMessage());
        }
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        return "loki_query";
    }

    private void parseResponse(Map<String, Object> output, HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            output.put("success", false);
            output.put("error", "Loki 返回 HTTP " + response.statusCode());
            output.put("body", OpsHttpSupport.truncate(response.body()));
            return;
        }
        JSONObject payload = JSON.parseObject(response.body());
        boolean success = "success".equalsIgnoreCase(payload.getString("status"));
        JSONObject data = payload.getJSONObject("data");
        JSONArray result = data == null ? null : data.getJSONArray("result");
        output.put("success", success);
        output.put("resultType", data == null ? null : data.getString("resultType"));
        output.put("streamCount", result == null ? 0 : result.size());
        output.put("result", result == null ? new JSONArray() : result);
        if (!success) {
            output.put("error", payload.getString("error"));
        }
    }

    private Map<String, String> credential(WorkflowNode node) {
        Long credentialId = OpsHttpSupport.longValue(node, "credentialId");
        return credentialId == null ? Map.of() : credentialService.getSecretData(credentialId);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }
}
