package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.ConnectorCredentialService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class KubernetesActionNodeExecutorTest {

    private final KubernetesActionNodeExecutor executor = new KubernetesActionNodeExecutor(
            mock(OutboundHttpPolicy.class), mock(ConnectorCredentialService.class));

    @Test
    void scalePatchClampsToExplicitSafeRange() {
        WorkflowNode node = node("kubernetes_scale", Map.of("replicas", 3));
        JSONObject patch = executor.buildPatch(node, Map.of(), new JSONObject());
        assertEquals(3, patch.getJSONObject("spec").getInteger("replicas"));

        WorkflowNode unsafe = node("kubernetes_scale", Map.of("replicas", 201));
        assertThrows(IllegalArgumentException.class,
                () -> executor.buildPatch(unsafe, Map.of(), new JSONObject()));
    }

    @Test
    void rollbackRequiresExistingContainer() {
        WorkflowNode node = node("kubernetes_rollback", Map.of(
                "container", "api",
                "targetImage", "registry.example/api:v1"
        ));
        JSONObject before = JSON.parseObject("""
                {"spec":{"template":{"spec":{"containers":[{"name":"worker","image":"worker:v2"}]}}}}
                """);

        assertThrows(IllegalArgumentException.class,
                () -> executor.buildPatch(node, Map.of(), before));
    }

    @Test
    void restartUsesStableExecutionChangeId() {
        WorkflowNode node = node("kubernetes_restart", Map.of());
        JSONObject patch = executor.buildPatch(node, Map.of("__executionId__", 42L), new JSONObject());

        String changeId = patch.getJSONObject("spec")
                .getJSONObject("template")
                .getJSONObject("metadata")
                .getJSONObject("annotations")
                .getString("paiops.io/restart-change-id");
        assertEquals("execution-42", changeId);
    }

    private WorkflowNode node(String type, Map<String, Object> data) {
        WorkflowNode node = new WorkflowNode();
        node.setId(type + "-1");
        node.setType(type);
        node.setData(new HashMap<>(data));
        return node;
    }
}
