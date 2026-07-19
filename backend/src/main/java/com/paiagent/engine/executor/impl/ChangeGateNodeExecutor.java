package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ChangeGateNodeExecutor implements NodeExecutor {

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
        String requiredPhrase = OpsHttpSupport.text(node, input, "requiredPhrase", null);
        requiredPhrase = requiredPhrase == null ? "APPROVE" : requiredPhrase;
        String approvalToken = resolveApprovalToken(input);
        if (!requiredPhrase.equals(approvalToken)) {
            throw new IllegalStateException("变更门禁未通过：执行输入必须包含正确的 approvalToken");
        }

        Map<String, Object> output = new LinkedHashMap<>(input);
        // 变更门禁只能表示口令/窗口检查通过，绝不能替代数据库中的人工审批事实。
        output.put("changeGatePassed", true);
        output.put("changeGatePassedAt", Instant.now().toString());
        output.put("gateNodeId", node.getId());
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        return "change_gate";
    }

    private String resolveApprovalToken(Map<String, Object> input) {
        String directToken = OpsHttpSupport.asText(input.get("approvalToken"));
        if (directToken != null) {
            return directToken;
        }

        String rawInput = OpsHttpSupport.asText(input.get("input"));
        if (rawInput == null || !rawInput.startsWith("{")) {
            return null;
        }
        try {
            JSONObject parsed = JSON.parseObject(rawInput);
            return parsed == null ? null : OpsHttpSupport.asText(parsed.get("approvalToken"));
        } catch (Exception ignored) {
            return null;
        }
    }
}
