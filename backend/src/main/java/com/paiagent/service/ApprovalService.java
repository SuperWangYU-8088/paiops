package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.paiagent.engine.WorkflowPausedException;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.ApprovalRequest;
import com.paiagent.mapper.ApprovalRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApprovalService {

    private final ApprovalRequestMapper approvalRequestMapper;

    public ApprovalService(ApprovalRequestMapper approvalRequestMapper) {
        this.approvalRequestMapper = approvalRequestMapper;
    }

    public ApprovalRequest requireApproval(Long executionId, Long flowId, WorkflowNode node,
                                           String requestedBy, String reason, int expiresMinutes) {
        ApprovalRequest request = findByExecutionAndNode(executionId, node.getId());
        if (request != null && "APPROVED".equals(request.getStatus())
                && (request.getExpiresAt() == null || request.getExpiresAt().isAfter(LocalDateTime.now()))) {
            return request;
        }
        if (request != null && "REJECTED".equals(request.getStatus())) {
            throw new SecurityException("人工审批已拒绝");
        }
        if (request == null) {
            request = new ApprovalRequest();
            request.setExecutionId(executionId);
            request.setFlowId(flowId);
            request.setNodeId(node.getId());
            request.setNodeName(resolveNodeName(node));
            request.setRiskLevel("HIGH_RISK");
            request.setStatus("PENDING");
            request.setRequestedBy(requestedBy == null ? "system" : requestedBy);
            request.setRequestReason(reason);
            request.setRequestedAt(LocalDateTime.now());
            request.setExpiresAt(LocalDateTime.now().plusMinutes(Math.max(1, Math.min(expiresMinutes, 1440))));
            approvalRequestMapper.insert(request);
        }
        throw new WorkflowPausedException(node.getId(), "等待人工审批");
    }

    public List<ApprovalRequest> list(String status, int limit) {
        LambdaQueryWrapper<ApprovalRequest> query = new LambdaQueryWrapper<ApprovalRequest>()
                .orderByDesc(ApprovalRequest::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        if (status != null && !status.isBlank()) {
            query.eq(ApprovalRequest::getStatus, status.toUpperCase());
        }
        return approvalRequestMapper.selectList(query);
    }

    /**
     * 在高风险节点真正执行前，必须回查数据库中的审批事实。
     * 不能只信任工作流输入里的 approved=true，因为调用者可以自行构造执行参数。
     */
    public void assertValidApproval(Long approvalId, Long executionId) {
        if (approvalId == null || executionId == null) {
            throw new SecurityException("高风险节点必须连接已通过的人工审批节点");
        }
        ApprovalRequest request = approvalRequestMapper.selectById(approvalId);
        if (request == null
                || !executionId.equals(request.getExecutionId())
                || !"APPROVED".equals(request.getStatus())
                || request.getReviewedAt() == null) {
            throw new SecurityException("高风险节点的审批记录无效或不属于当前任务");
        }
        if (request.getExpiresAt() != null && !request.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new SecurityException("高风险节点的审批记录已过期");
        }
    }

    @Transactional
    public ApprovalRequest review(Long id, boolean approved, String reviewer, String comment) {
        ApprovalRequest request = approvalRequestMapper.selectById(id);
        if (request == null) {
            throw new IllegalArgumentException("审批请求不存在");
        }
        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalStateException("审批请求已处理");
        }
        if (request.getExpiresAt() != null && request.getExpiresAt().isBefore(LocalDateTime.now())) {
            approvalRequestMapper.update(null, new LambdaUpdateWrapper<ApprovalRequest>()
                    .eq(ApprovalRequest::getId, id)
                    .eq(ApprovalRequest::getStatus, "PENDING")
                    .set(ApprovalRequest::getStatus, "EXPIRED"));
            throw new IllegalStateException("审批请求已过期");
        }
        LocalDateTime reviewedAt = LocalDateTime.now();
        int updated = approvalRequestMapper.update(null, new LambdaUpdateWrapper<ApprovalRequest>()
                .eq(ApprovalRequest::getId, id)
                .eq(ApprovalRequest::getStatus, "PENDING")
                .set(ApprovalRequest::getStatus, approved ? "APPROVED" : "REJECTED")
                .set(ApprovalRequest::getReviewedBy, reviewer == null ? "system" : reviewer)
                .set(ApprovalRequest::getReviewComment, comment)
                .set(ApprovalRequest::getReviewedAt, reviewedAt));
        if (updated != 1) {
            throw new IllegalStateException("审批请求已被其他用户处理");
        }
        return approvalRequestMapper.selectById(id);
    }

    private ApprovalRequest findByExecutionAndNode(Long executionId, String nodeId) {
        return approvalRequestMapper.selectOne(new LambdaQueryWrapper<ApprovalRequest>()
                .eq(ApprovalRequest::getExecutionId, executionId)
                .eq(ApprovalRequest::getNodeId, nodeId)
                .last("LIMIT 1"));
    }

    private String resolveNodeName(WorkflowNode node) {
        Object label = node.getData() == null ? null : node.getData().get("label");
        return label == null ? node.getType() : String.valueOf(label);
    }
}
