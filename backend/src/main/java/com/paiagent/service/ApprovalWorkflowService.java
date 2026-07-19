package com.paiagent.service;

import com.paiagent.entity.ApprovalRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审批与执行状态编排服务。
 *
 * <p>审批记录和任务状态必须在同一数据库事务中更新；Redis 续执行消息由
 * {@link ExecutionTaskService} 在事务提交后投递，避免出现“已批准但任务没恢复”
 * 或“任务先恢复、审批尚未提交”的半完成状态。</p>
 */
@Service
public class ApprovalWorkflowService {

    private final ApprovalService approvalService;
    private final ExecutionTaskService executionTaskService;

    public ApprovalWorkflowService(ApprovalService approvalService,
                                   ExecutionTaskService executionTaskService) {
        this.approvalService = approvalService;
        this.executionTaskService = executionTaskService;
    }

    @Transactional
    public ApprovalRequest approve(Long id, String reviewer, String comment) {
        ApprovalRequest approval = approvalService.review(id, true, reviewer, comment);
        executionTaskService.enqueueResume(approval.getExecutionId(), approval.getNodeId());
        return approval;
    }

    @Transactional
    public ApprovalRequest reject(Long id, String reviewer, String comment) {
        ApprovalRequest approval = approvalService.review(id, false, reviewer, comment);
        executionTaskService.markRejected(approval.getExecutionId());
        return approval;
    }
}
