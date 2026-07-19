package com.paiagent.engine.safety;

import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.ApprovalService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NodeSafetyPolicyTest {

    private final ApprovalService approvalService = mock(ApprovalService.class);
    private final NodeSafetyPolicy policy = new NodeSafetyPolicy(approvalService);

    @Test
    void classifiesKnownNodeTypes() {
        assertEquals(NodeRiskLevel.READ_ONLY, policy.riskLevel("prometheus_query"));
        assertEquals(NodeRiskLevel.LOW_RISK, policy.riskLevel("webhook_notify"));
        assertEquals(NodeRiskLevel.GOVERNANCE, policy.riskLevel("manual_approval"));
        assertEquals(NodeRiskLevel.HIGH_RISK, policy.riskLevel("kubernetes_restart"));
        assertEquals(NodeRiskLevel.HIGH_RISK, policy.riskLevel("unknown_action"));
    }

    @Test
    void highRiskNodeRequiresPersistedApprovalEvidence() {
        WorkflowNode node = new WorkflowNode();
        node.setType("kubernetes_scale");

        assertThrows(SecurityException.class,
                () -> policy.assertExecutionAllowed(node, Map.of()));
        // 单纯伪造 approved=true 不再能够绕过高风险审批。
        assertThrows(SecurityException.class,
                () -> policy.assertExecutionAllowed(node, Map.of("approved", true)));

        assertDoesNotThrow(() -> policy.assertExecutionAllowed(node, Map.of(
                "__executionId__", 42L,
                "approvalId", 9L
        )));
        verify(approvalService).assertValidApproval(9L, 42L);
    }

    @Test
    void readOnlyNodeNeverRequiresApproval() {
        WorkflowNode node = new WorkflowNode();
        node.setType("loki_query");

        assertDoesNotThrow(() -> policy.assertExecutionAllowed(node, Map.of()));
    }
}
