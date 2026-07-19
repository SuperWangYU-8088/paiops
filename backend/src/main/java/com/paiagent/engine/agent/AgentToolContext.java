package com.paiagent.engine.agent;

import com.paiagent.engine.model.WorkflowNode;

import java.util.Map;

/**
 * Execution context exposed to ReAct runtime tools.
 */
public record AgentToolContext(
        WorkflowNode node,
        Map<String, Object> currentInput
) {
}
