package com.paiagent.engine.executor.impl;

import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.ConnectorCredentialService;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HttpHealthCheckNodeExecutor implements NodeExecutor {

    private final OutboundHttpPolicy httpPolicy;
    private final ConnectorCredentialService credentialService;

    public HttpHealthCheckNodeExecutor(OutboundHttpPolicy httpPolicy,
                                       ConnectorCredentialService credentialService) {
        this.httpPolicy = httpPolicy;
        this.credentialService = credentialService;
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
        String url = OpsHttpSupport.requiredText(
                OpsHttpSupport.text(node, input, "url", null),
                "HTTP 健康检查节点缺少 URL"
        );
        String method = OpsHttpSupport.text(node, input, "method", null);
        method = method == null ? "GET" : method.toUpperCase();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            throw new IllegalArgumentException("HTTP 健康检查仅支持 GET 或 HEAD");
        }

        int timeoutSeconds = OpsHttpSupport.integer(node, "timeoutSeconds", 10, 1, 30);
        int expectedStatusMin = OpsHttpSupport.integer(node, "expectedStatusMin", 200, 100, 599);
        int expectedStatusMax = OpsHttpSupport.integer(node, "expectedStatusMax", 399, expectedStatusMin, 599);
        boolean includeBody = OpsHttpSupport.bool(node, "includeBody", false);
        httpPolicy.validate(url);

        HttpRequest.Builder requestBuilder = OpsHttpSupport.requestBuilder(url, timeoutSeconds);
        OpsHttpSupport.applyHeaders(requestBuilder, OpsHttpSupport.headers(node));
        applyCredential(requestBuilder, node);
        if ("HEAD".equals(method)) {
            requestBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        } else {
            requestBuilder.GET();
        }

        long startedAt = System.nanoTime();
        Map<String, Object> output = baseOutput(url, method);
        try {
            HttpResponse<String> response = OpsHttpSupport.createClient(timeoutSeconds)
                    .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            output.put("healthy", statusCode >= expectedStatusMin && statusCode <= expectedStatusMax);
            output.put("statusCode", statusCode);
            output.put("responseTimeMs", elapsedMillis(startedAt));
            if (includeBody && !"HEAD".equals(method)) {
                output.put("body", OpsHttpSupport.truncate(response.body()));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            output.put("healthy", false);
            output.put("responseTimeMs", elapsedMillis(startedAt));
            output.put("error", "健康检查被中断");
        } catch (Exception exception) {
            output.put("healthy", false);
            output.put("responseTimeMs", elapsedMillis(startedAt));
            output.put("error", exception.getMessage());
        }
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        return "http_health_check";
    }

    private Map<String, Object> baseOutput(String url, String method) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("url", url);
        output.put("method", method);
        output.put("checkedAt", Instant.now().toString());
        return output;
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private void applyCredential(HttpRequest.Builder builder, WorkflowNode node) {
        Long credentialId = OpsHttpSupport.longValue(node, "credentialId");
        if (credentialId == null) {
            return;
        }
        Map<String, String> credential = credentialService.getSecretData(credentialId);
        String authorization = credential.get("authorization");
        if (authorization != null && !authorization.isBlank()) {
            builder.header("Authorization", authorization.trim());
        }
    }
}
