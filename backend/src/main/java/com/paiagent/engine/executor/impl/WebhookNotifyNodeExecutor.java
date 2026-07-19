package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
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
public class WebhookNotifyNodeExecutor implements NodeExecutor {

    private final OutboundHttpPolicy httpPolicy;
    private final ConnectorCredentialService credentialService;

    public WebhookNotifyNodeExecutor(OutboundHttpPolicy httpPolicy,
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
        String url = OpsHttpSupport.requiredText(
                firstText(OpsHttpSupport.text(node, input, "url", null),
                        credential.get("url"), credential.get("endpoint")),
                "Webhook 通知节点缺少 URL"
        );
        String message = OpsHttpSupport.requiredText(
                OpsHttpSupport.text(node, input, "message", "output"),
                "Webhook 通知节点缺少消息内容"
        );
        String severity = OpsHttpSupport.text(node, input, "severity", null);
        severity = severity == null ? "info" : severity;
        boolean dryRun = OpsHttpSupport.bool(node, "dryRun", true);
        boolean includeContext = OpsHttpSupport.bool(node, "includeContext", false);

        JSONObject payload = new JSONObject();
        payload.put("source", "PaiOps");
        payload.put("severity", severity);
        payload.put("message", message);
        payload.put("sentAt", Instant.now().toString());
        if (includeContext) {
            payload.put("context", OpsHttpSupport.safeContext(input));
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("url", url);
        output.put("severity", severity);
        output.put("dryRun", dryRun);
        output.put("payload", payload);
        if (dryRun) {
            output.put("delivered", false);
            output.put("status", "DRY_RUN");
            return output;
        }

        httpPolicy.validate(url);
        int timeoutSeconds = OpsHttpSupport.integer(node, "timeoutSeconds", 10, 1, 30);
        HttpRequest.Builder requestBuilder = OpsHttpSupport.requestBuilder(url, timeoutSeconds)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(payload)));
        OpsHttpSupport.applyHeaders(requestBuilder, OpsHttpSupport.headers(node));
        OpsHttpSupport.applyCredentialAuth(requestBuilder, credential);

        long startedAt = System.nanoTime();
        try {
            HttpResponse<String> response = OpsHttpSupport.createClient(timeoutSeconds)
                    .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            boolean delivered = response.statusCode() >= 200 && response.statusCode() < 300;
            output.put("delivered", delivered);
            output.put("status", delivered ? "DELIVERED" : "FAILED");
            output.put("statusCode", response.statusCode());
            output.put("responseTimeMs", elapsedMillis(startedAt));
            output.put("responseBody", OpsHttpSupport.truncate(response.body()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            output.put("delivered", false);
            output.put("status", "FAILED");
            output.put("error", "Webhook 通知被中断");
        } catch (Exception exception) {
            output.put("delivered", false);
            output.put("status", "FAILED");
            output.put("error", exception.getMessage());
        }
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        return "webhook_notify";
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
