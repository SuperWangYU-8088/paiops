package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionJob;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.engine.WorkflowEngine;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class ExecutionWorkerService {

    private final ExecutionRecordMapper executionRecordMapper;
    private final WorkflowService workflowService;
    private final WorkflowEngine workflowEngine;
    private final TaskEventBroker eventBroker;
    private final AuditLogService auditLogService;
    private final AlertIncidentService alertIncidentService;

    public ExecutionWorkerService(ExecutionRecordMapper executionRecordMapper,
                                  WorkflowService workflowService,
                                  WorkflowEngine workflowEngine,
                                  TaskEventBroker eventBroker,
                                  AuditLogService auditLogService,
                                  AlertIncidentService alertIncidentService) {
        this.executionRecordMapper = executionRecordMapper;
        this.workflowService = workflowService;
        this.workflowEngine = workflowEngine;
        this.eventBroker = eventBroker;
        this.auditLogService = auditLogService;
        this.alertIncidentService = alertIncidentService;
    }

    public ExecutionResponse run(ExecutionJob job, String workerId) {
        // Go Worker 只负责领取、心跳和故障接管，真正的状态流转仍由 Java 控制面统一落库。
        // 这样即使 Worker 进程被替换，也不会出现两套状态定义相互冲突的问题。
        ExecutionRecord record = executionRecordMapper.selectById(job.getExecutionId());
        if (record == null) {
            throw new IllegalArgumentException("执行任务不存在");
        }
        if (Integer.valueOf(1).equals(record.getCancelRequested())
                || "CANCELED".equals(record.getStatus())) {
            record.setStatus("CANCELED");
            record.setCompletedAt(LocalDateTime.now());
            executionRecordMapper.updateById(record);
            throw new IllegalStateException("执行任务已取消");
        }
        if (!"QUEUED".equals(record.getStatus())
                && !("RESUME".equals(job.getMode()) && "WAITING_APPROVAL".equals(record.getStatus()))) {
            throw new IllegalStateException("执行任务当前状态不可领取: " + record.getStatus());
        }

        Workflow workflow = workflowService.getById(record.getFlowId());
        if (workflow == null) {
            throw new IllegalArgumentException("Runbook 不存在");
        }
        if (workflow.getEngineType() != null && !"dag".equalsIgnoreCase(workflow.getEngineType())) {
            throw new IllegalStateException("异步运维任务只允许使用确定性的 DAG 引擎");
        }

        record.setStatus("RUNNING");
        record.setWorkerId(workerId);
        record.setHeartbeatAt(LocalDateTime.now());
        record.setStartedAt(record.getStartedAt() == null ? LocalDateTime.now() : record.getStartedAt());
        record.setAttempt(record.getAttempt() == null ? 1 : record.getAttempt() + 1);
        String mode = job.getMode() == null ? "START" : job.getMode().toUpperCase();
        record.setExecutionMode(mode);
        executionRecordMapper.updateById(record);
        auditLogService.record("worker:" + workerId, "EXECUTION_START", "EXECUTION",
                record.getId(), "SUCCESS", Map.of("mode", mode, "flowId", record.getFlowId()), null);

        try {
            ExecutionResponse response;
            if ("RESUME".equals(mode)) {
                ResumeExecutionRequest request = new ResumeExecutionRequest();
                request.setStartNodeId(job.getStartNodeId());
                request.setSkipSuccessNodes(true);
                request.setUseSnapshotVariables(true);
                response = workflowEngine.resumeExecution(
                        workflow,
                        record.getId(),
                        request,
                        event -> eventBroker.publish(record.getId(), event)
                );
            } else {
                response = workflowEngine.executeExisting(
                        workflow,
                        extractInput(record.getInputData()),
                        record,
                        event -> eventBroker.publish(record.getId(), event)
                );
            }
            finalizeRecord(record.getId(), response);
            alertIncidentService.onExecutionCompleted(record.getId(), response);
            auditLogService.record("worker:" + workerId, "EXECUTION_COMPLETE", "EXECUTION",
                    record.getId(), response.getStatus(),
                    Map.of(
                            "duration", response.getDuration() == null ? 0 : response.getDuration(),
                            "status", String.valueOf(response.getStatus())
                    ), null);
            return response;
        } catch (Exception exception) {
            ExecutionRecord latest = executionRecordMapper.selectById(record.getId());
            String finalStatus = latest == null ? "FAILED" : latest.getStatus();
            if (latest != null && !isPausedOrTerminal(latest.getStatus())) {
                latest.setStatus("FAILED");
                latest.setErrorMessage(exception.getMessage());
                latest.setCompletedAt(LocalDateTime.now());
                executionRecordMapper.updateById(latest);
                finalStatus = "FAILED";
            }
            auditLogService.record("worker:" + workerId, "EXECUTION_COMPLETE", "EXECUTION",
                    record.getId(), finalStatus,
                    Map.of("error", String.valueOf(exception.getMessage())), null);

            // WorkflowEngine 的节点接口允许抛出受检异常，但控制器边界只暴露运行时异常。
            // 保留已有运行时异常类型便于统一异常处理；其余异常补充任务上下文后再上抛。
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("执行任务失败: " + record.getId(), exception);
        }
    }

    public void heartbeat(Long executionId, String workerId) {
        ExecutionRecord record = executionRecordMapper.selectById(executionId);
        if (record == null || !workerId.equals(record.getWorkerId())) {
            return;
        }
        if ("RUNNING".equals(record.getStatus()) || "CANCEL_REQUESTED".equals(record.getStatus())) {
            record.setHeartbeatAt(LocalDateTime.now());
            executionRecordMapper.updateById(record);
        }
    }

    private void finalizeRecord(Long executionId, ExecutionResponse response) {
        ExecutionRecord latest = executionRecordMapper.selectById(executionId);
        if (latest == null) {
            return;
        }
        latest.setHeartbeatAt(LocalDateTime.now());
        if (!"WAITING_APPROVAL".equals(response.getStatus())) {
            latest.setCompletedAt(LocalDateTime.now());
        }
        executionRecordMapper.updateById(latest);
    }

    /**
     * 等待审批与终态都已经由执行引擎明确落库，Worker 的兜底异常处理不能覆盖这些状态。
     * 否则一个已取消或已拒绝的任务会被误报为 FAILED，审计链也会失真。
     */
    private boolean isPausedOrTerminal(String status) {
        return "WAITING_APPROVAL".equals(status)
                || "SUCCESS".equals(status)
                || "FAILED".equals(status)
                || "CANCELED".equals(status)
                || "REJECTED".equals(status);
    }

    private String extractInput(String inputData) {
        if (inputData == null || inputData.isBlank()) {
            return "";
        }
        try {
            Object value = JSON.parseObject(inputData).get("input");
            return value == null ? "" : String.valueOf(value);
        } catch (Exception exception) {
            return inputData;
        }
    }
}
