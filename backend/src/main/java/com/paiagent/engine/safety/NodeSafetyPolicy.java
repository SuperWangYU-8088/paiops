package com.paiagent.engine.safety;

import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.ApprovalService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class NodeSafetyPolicy {

    private final ApprovalService approvalService;

    public NodeSafetyPolicy(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    private static final Set<String> READ_ONLY_NODES = Set.of(
            "input", "output", "condition", "llm", "react_agent",
            "openai", "deepseek", "qwen", "step", "zhipu", "ai_ping",
            "web_search", "web_fetch", "memory_retrieve", "knowledge_retrieve",
            "http_health_check", "prometheus_query", "loki_query",
            "kubernetes_query", "host_resource_check", "database_health_check"
    );
    private static final Set<String> LOW_RISK_NODES = Set.of(
            "webhook_notify", "memory_write", "knowledge_upsert",
            "tts", "image_generate", "video_generate", "vision_analyze"
    );
    private static final Set<String> GOVERNANCE_NODES = Set.of(
            "change_gate", "manual_approval"
    );

    public NodeRiskLevel riskLevel(String nodeType) {
        if (READ_ONLY_NODES.contains(nodeType)) {
            return NodeRiskLevel.READ_ONLY;
        }
        if (LOW_RISK_NODES.contains(nodeType)) {
            return NodeRiskLevel.LOW_RISK;
        }
        if (GOVERNANCE_NODES.contains(nodeType)) {
            return NodeRiskLevel.GOVERNANCE;
        }
        return NodeRiskLevel.HIGH_RISK;
    }

    public void assertExecutionAllowed(WorkflowNode node, Map<String, Object> input) {
        if (riskLevel(node.getType()) != NodeRiskLevel.HIGH_RISK) {
            return;
        }

        Long executionId = asLong(input.get("__executionId__"));
        Long approvalId = asLong(input.get("approvalId"));
        if (executionId == null || approvalId == null) {
            throw new SecurityException("高风险节点必须连接已通过的人工审批节点");
        }
        approvalService.assertValidApproval(approvalId, executionId);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
