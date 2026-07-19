package com.paiagent.engine.executor.impl;

import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpsHttpSupportTest {

    @Test
    void rejectsSensitiveHeadersStoredInRunbook() {
        WorkflowNode node = node(Map.of(
                "headers", "{\"Authorization\":\"Bearer plaintext\"}"
        ));

        assertThrows(SecurityException.class, () -> OpsHttpSupport.headers(node));
    }

    @Test
    void redactsNestedSecretsBeforeContextIsSent() {
        Map<String, Object> source = Map.of(
                "service", "orders",
                "nested", Map.of(
                        "api_key", "secret-value",
                        "result", "healthy"
                )
        );

        Object sanitized = OpsHttpSupport.safeContext(source);

        assertEquals(
                Map.of(
                        "service", "orders",
                        "nested", Map.of(
                                "api_key", "[REDACTED]",
                                "result", "healthy"
                        )
                ),
                sanitized
        );
    }

    @Test
    void clampsNumericConfiguration() {
        WorkflowNode node = node(Map.of("timeoutSeconds", 999));

        assertEquals(30, OpsHttpSupport.integer(node, "timeoutSeconds", 10, 1, 30));
    }

    private WorkflowNode node(Map<String, Object> data) {
        WorkflowNode node = new WorkflowNode();
        node.setId("node-1");
        node.setType("http_health_check");
        node.setData(data);
        return node;
    }
}
