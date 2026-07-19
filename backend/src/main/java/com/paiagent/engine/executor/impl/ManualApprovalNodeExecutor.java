package com.paiagent.engine.executor.impl;

import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.ApprovalRequest;
import com.paiagent.service.ApprovalService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ManualApprovalNodeExecutor implements NodeExecutor {

    private final ApprovalService approvalService;

    public ManualApprovalNodeExecutor(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
        Long executionId = asLong(input.get("__executionId__"));
        Long flowId = asLong(input.get("__flowId__"));
        if (executionId == null || flowId == null) {
            throw new IllegalStateException("人工审批节点只能在持久化执行任务中运行");
        }
        String reason = OpsHttpSupport.text(node, input, "approvalReason", null);
        int expiresMinutes = OpsHttpSupport.integer(node, "expiresMinutes", 60, 1, 1440);
        ApprovalRequest request = approvalService.requireApproval(
                executionId,
                flowId,
                node,
                OpsHttpSupport.asText(input.get("__actor__")),
                reason,
                expiresMinutes
        );
        Map<String, Object> output = new LinkedHashMap<>(input);
        output.put("approved", true);
        output.put("approvalId", request.getId());
        output.put("approvedBy", request.getReviewedBy());
        output.put("approvedAt", request.getReviewedAt());
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        return "manual_approval";
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = OpsHttpSupport.asText(value);
        return text == null ? null : Long.parseLong(text);
    }
}
