package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.dto.ExecutionJob;
import com.paiagent.dto.ExecutionTaskRequest;
import com.paiagent.dto.ExecutionTaskResponse;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ExecutionTaskService {

    private final ExecutionRecordMapper executionRecordMapper;
    private final WorkflowService workflowService;
    private final ExecutionOutboxService executionOutboxService;
    private final AuditLogService auditLogService;
    private final String queueKey;

    public ExecutionTaskService(ExecutionRecordMapper executionRecordMapper,
                                WorkflowService workflowService,
                                ExecutionOutboxService executionOutboxService,
                                AuditLogService auditLogService,
                                @Value("${paiops.worker.queue-key:paiops:execution:queue}") String queueKey) {
        this.executionRecordMapper = executionRecordMapper;
        this.workflowService = workflowService;
        this.executionOutboxService = executionOutboxService;
        this.auditLogService = auditLogService;
        this.queueKey = queueKey;
    }

    @Transactional
    public ExecutionTaskResponse submit(Long flowId, ExecutionTaskRequest request,
                                        String actor, String ipAddress) {
        Workflow workflow = requireWorkflow(flowId);
        // 相同 Runbook + 幂等键只产生一个任务，防止告警重试造成重复处置。
        ExecutionRecord existing = findByIdempotencyKey(flowId, request.getIdempotencyKey());
        if (existing != null) {
            return toResponse(existing, workflow.getName());
        }

        ExecutionRecord record = new ExecutionRecord();
        record.setFlowId(flowId);
        record.setInputData(JSON.toJSONString(java.util.Map.of("input", request.getInputData())));
        record.setStatus("QUEUED");
        record.setNodeResults("[]");
        record.setDuration(0);
        record.setCancelRequested(0);
        record.setQueuedAt(LocalDateTime.now());
        record.setAttempt(0);
        record.setIdempotencyKey(blankToNull(request.getIdempotencyKey()));
        record.setExecutionMode("ASYNC");
        try {
            executionRecordMapper.insert(record);
        } catch (DuplicateKeyException duplicate) {
            // 数据库唯一键是最终幂等屏障；两个并发告警即使同时通过前置查询，
            // 也只有一个能插入，另一方返回已存在的任务。
            ExecutionRecord concurrent = findByIdempotencyKey(flowId, request.getIdempotencyKey());
            if (concurrent != null) {
                return toResponse(concurrent, workflow.getName());
            }
            throw duplicate;
        }
        persistQueueMessage(new ExecutionJob(record.getId(), flowId, "START", null));
        auditLogService.record(actor, "EXECUTION_QUEUED", "EXECUTION", record.getId(),
                "SUCCESS", java.util.Map.of("flowId", flowId, "workflowName", workflow.getName()), ipAddress);
        return toResponse(record, workflow.getName());
    }

    public List<ExecutionTaskResponse> list(String status, int limit) {
        LambdaQueryWrapper<ExecutionRecord> query = new LambdaQueryWrapper<ExecutionRecord>()
                .orderByDesc(ExecutionRecord::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 300)));
        if (status != null && !status.isBlank()) {
            query.eq(ExecutionRecord::getStatus, status.toUpperCase());
        }
        return executionRecordMapper.selectList(query).stream()
                .map(record -> {
                    Workflow workflow = workflowService.getById(record.getFlowId());
                    return toResponse(record, workflow == null ? null : workflow.getName());
                })
                .toList();
    }

    public ExecutionTaskResponse get(Long executionId) {
        ExecutionRecord record = requireExecution(executionId);
        Workflow workflow = workflowService.getById(record.getFlowId());
        return toResponse(record, workflow == null ? null : workflow.getName());
    }

    public ExecutionRecord requireExecution(Long executionId) {
        ExecutionRecord record = executionRecordMapper.selectById(executionId);
        if (record == null) {
            throw new IllegalArgumentException("执行任务不存在");
        }
        return record;
    }

    public void requestCancel(Long executionId, String actor, String ipAddress) {
        ExecutionRecord record = requireExecution(executionId);
        if (isTerminal(record.getStatus())) {
            return;
        }
        record.setCancelRequested(1);
        if ("QUEUED".equals(record.getStatus()) || "WAITING_APPROVAL".equals(record.getStatus())) {
            record.setStatus("CANCELED");
            record.setCompletedAt(LocalDateTime.now());
        } else {
            record.setStatus("CANCEL_REQUESTED");
        }
        executionRecordMapper.updateById(record);
        auditLogService.record(actor, "EXECUTION_CANCEL", "EXECUTION", executionId,
                "SUCCESS", java.util.Map.of("status", record.getStatus()), ipAddress);
    }

    @Transactional
    public void enqueueResume(Long executionId, String startNodeId) {
        ExecutionRecord record = requireExecution(executionId);
        if (Integer.valueOf(1).equals(record.getCancelRequested())) {
            throw new IllegalStateException("已取消的任务不能继续");
        }
        record.setStatus("QUEUED");
        record.setExecutionMode("RESUME");
        record.setQueuedAt(LocalDateTime.now());
        record.setWorkerId(null);
        record.setHeartbeatAt(null);
        executionRecordMapper.updateById(record);
        // 审批通过后不创建新执行记录，而是在原执行 ID 上从快照断点继续，
        // 这样节点历史、审批记录和审计链可以完整关联。
        persistQueueMessage(new ExecutionJob(record.getId(), record.getFlowId(), "RESUME", startNodeId));
    }

    public List<Long> findStaleExecutionIds(int staleAfterSeconds) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(Math.max(30, staleAfterSeconds));
        return executionRecordMapper.selectList(new LambdaQueryWrapper<ExecutionRecord>()
                        .eq(ExecutionRecord::getStatus, "RUNNING")
                        // 明确拆成两个互斥分支，避免 SQL 的 AND/OR 优先级把正常任务误判为失联：
                        // 1. 有心跳但最后心跳已过期；2. 从未上报心跳且启动时间已过期。
                        .and(stale -> stale
                                .lt(ExecutionRecord::getHeartbeatAt, threshold)
                                .or(noHeartbeat -> noHeartbeat
                                        .isNull(ExecutionRecord::getHeartbeatAt)
                                        .lt(ExecutionRecord::getStartedAt, threshold)))
                        .orderByAsc(ExecutionRecord::getId)
                        .last("LIMIT 100"))
                .stream()
                .map(ExecutionRecord::getId)
                .toList();
    }

    @Transactional
    public boolean requeueStale(Long executionId, int staleAfterSeconds) {
        ExecutionRecord record = requireExecution(executionId);
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(Math.max(30, staleAfterSeconds));
        boolean stale = "RUNNING".equals(record.getStatus())
                && ((record.getHeartbeatAt() != null && record.getHeartbeatAt().isBefore(threshold))
                || (record.getHeartbeatAt() == null && record.getStartedAt() != null
                && record.getStartedAt().isBefore(threshold)));
        if (!stale) {
            return false;
        }
        // 失联任务使用 RESUME 模式重新入队，已成功节点由快照恢复并跳过。
        record.setStatus("QUEUED");
        record.setExecutionMode("RESUME");
        record.setWorkerId(null);
        record.setHeartbeatAt(null);
        record.setQueuedAt(LocalDateTime.now());
        executionRecordMapper.updateById(record);
        persistQueueMessage(new ExecutionJob(record.getId(), record.getFlowId(), "RESUME", null));
        return true;
    }

    @Transactional
    public void markRejected(Long executionId) {
        ExecutionRecord record = requireExecution(executionId);
        record.setStatus("REJECTED");
        record.setCompletedAt(LocalDateTime.now());
        executionRecordMapper.updateById(record);
    }

    private void persistQueueMessage(ExecutionJob job) {
        executionOutboxService.persistAndDispatchAfterCommit(
                job.getExecutionId(), queueKey, JSON.toJSONString(job));
    }

    private Workflow requireWorkflow(Long flowId) {
        Workflow workflow = workflowService.getById(flowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Runbook 不存在");
        }
        return workflow;
    }

    private ExecutionRecord findByIdempotencyKey(Long flowId, String idempotencyKey) {
        String key = blankToNull(idempotencyKey);
        if (key == null) {
            return null;
        }
        return executionRecordMapper.selectOne(new LambdaQueryWrapper<ExecutionRecord>()
                .eq(ExecutionRecord::getFlowId, flowId)
                .eq(ExecutionRecord::getIdempotencyKey, key)
                .orderByDesc(ExecutionRecord::getId)
                .last("LIMIT 1"));
    }

    private ExecutionTaskResponse toResponse(ExecutionRecord record, String workflowName) {
        ExecutionTaskResponse response = new ExecutionTaskResponse();
        response.setExecutionId(record.getId());
        response.setFlowId(record.getFlowId());
        response.setWorkflowName(workflowName);
        response.setStatus(record.getStatus());
        response.setExecutionMode(record.getExecutionMode());
        response.setWorkerId(record.getWorkerId());
        response.setAttempt(record.getAttempt());
        response.setDuration(record.getDuration());
        response.setErrorMessage(record.getErrorMessage());
        response.setQueuedAt(record.getQueuedAt());
        response.setStartedAt(record.getStartedAt());
        response.setCompletedAt(record.getCompletedAt());
        response.setHeartbeatAt(record.getHeartbeatAt());
        response.setExecutedAt(record.getExecutedAt());
        return response;
    }

    private boolean isTerminal(String status) {
        return List.of("SUCCESS", "FAILED", "CANCELED", "REJECTED").contains(status);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
