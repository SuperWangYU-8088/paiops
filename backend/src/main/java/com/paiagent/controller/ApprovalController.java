package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.dto.ApprovalReviewRequest;
import com.paiagent.entity.ApprovalRequest;
import com.paiagent.service.ApprovalService;
import com.paiagent.service.ApprovalWorkflowService;
import com.paiagent.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final AuditLogService auditLogService;

    public ApprovalController(ApprovalService approvalService,
                              ApprovalWorkflowService approvalWorkflowService,
                              AuditLogService auditLogService) {
        this.approvalService = approvalService;
        this.approvalWorkflowService = approvalWorkflowService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public Result<List<ApprovalRequest>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(approvalService.list(status, limit));
    }

    @PostMapping("/{id}/approve")
    public Result<ApprovalRequest> approve(@PathVariable Long id,
                                           @RequestBody(required = false) ApprovalReviewRequest review,
                                           HttpServletRequest request) {
        String reviewer = (String) request.getAttribute("username");
        ApprovalRequest approval = approvalWorkflowService.approve(
                id, reviewer, review == null ? null : review.getComment());
        audit(request, approval, "APPROVAL_APPROVE");
        return Result.success(approval);
    }

    @PostMapping("/{id}/reject")
    public Result<ApprovalRequest> reject(@PathVariable Long id,
                                          @RequestBody(required = false) ApprovalReviewRequest review,
                                          HttpServletRequest request) {
        String reviewer = (String) request.getAttribute("username");
        ApprovalRequest approval = approvalWorkflowService.reject(
                id, reviewer, review == null ? null : review.getComment());
        audit(request, approval, "APPROVAL_REJECT");
        return Result.success(approval);
    }

    private void audit(HttpServletRequest request, ApprovalRequest approval, String action) {
        auditLogService.record(
                (String) request.getAttribute("username"),
                action,
                "APPROVAL",
                approval.getId(),
                "SUCCESS",
                Map.of(
                        "executionId", approval.getExecutionId(),
                        "nodeId", approval.getNodeId(),
                        "status", approval.getStatus()
                ),
                request.getRemoteAddr()
        );
    }
}
