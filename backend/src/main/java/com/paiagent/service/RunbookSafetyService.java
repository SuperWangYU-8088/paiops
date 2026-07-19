package com.paiagent.service;

import com.paiagent.engine.WorkflowConfigParser;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.safety.NodeRiskLevel;
import com.paiagent.engine.safety.NodeSafetyPolicy;
import com.paiagent.entity.Workflow;
import org.springframework.stereotype.Service;

/**
 * Runbook 静态风险评估。
 *
 * <p>告警自动触发目前严格限定为全只读 DAG。通知、写入、审批以及未知节点
 * 都需要人工从事件中心启动，符合“先只读、后低风险、最后闭环”的渐进原则。</p>
 */
@Service
public class RunbookSafetyService {

    private final WorkflowConfigParser workflowConfigParser;
    private final NodeSafetyPolicy nodeSafetyPolicy;

    public RunbookSafetyService(WorkflowConfigParser workflowConfigParser,
                                NodeSafetyPolicy nodeSafetyPolicy) {
        this.workflowConfigParser = workflowConfigParser;
        this.nodeSafetyPolicy = nodeSafetyPolicy;
    }

    public boolean isReadOnly(Workflow workflow) {
        if (workflow == null || workflow.getFlowData() == null
                || (workflow.getEngineType() != null && !"dag".equalsIgnoreCase(workflow.getEngineType()))) {
            return false;
        }
        WorkflowConfig config = workflowConfigParser.parse(workflow.getFlowData());
        return config.getNodes() != null
                && !config.getNodes().isEmpty()
                && config.getNodes().stream().allMatch(node ->
                nodeSafetyPolicy.riskLevel(node.getType()) == NodeRiskLevel.READ_ONLY);
    }
}
