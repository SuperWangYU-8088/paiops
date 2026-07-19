package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.dto.AlertWebhookRequest;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ExecutionTaskRequest;
import com.paiagent.dto.ExecutionTaskResponse;
import com.paiagent.dto.IncidentExecutionRequest;
import com.paiagent.entity.OpsAlert;
import com.paiagent.entity.OpsIncident;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.OpsAlertMapper;
import com.paiagent.mapper.OpsIncidentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class AlertIncidentService {

    private final OpsAlertMapper alertMapper;
    private final OpsIncidentMapper incidentMapper;
    private final ExecutionTaskService executionTaskService;
    private final WorkflowService workflowService;
    private final RunbookSafetyService runbookSafetyService;

    public AlertIncidentService(OpsAlertMapper alertMapper,
                                OpsIncidentMapper incidentMapper,
                                ExecutionTaskService executionTaskService,
                                WorkflowService workflowService,
                                RunbookSafetyService runbookSafetyService) {
        this.alertMapper = alertMapper;
        this.incidentMapper = incidentMapper;
        this.executionTaskService = executionTaskService;
        this.workflowService = workflowService;
        this.runbookSafetyService = runbookSafetyService;
    }

    @Transactional
    public int ingest(AlertWebhookRequest request) {
        if (request == null || request.getAlerts() == null) {
            return 0;
        }
        int processed = 0;
        for (AlertWebhookRequest.AlertItem item : request.getAlerts()) {
            upsertAlert(request, item);
            processed++;
        }
        return processed;
    }

    public List<OpsAlert> listAlerts(String status, int limit) {
        LambdaQueryWrapper<OpsAlert> query = new LambdaQueryWrapper<OpsAlert>()
                .orderByDesc(OpsAlert::getId)
                .last("LIMIT " + clampLimit(limit));
        if (status != null && !status.isBlank()) {
            query.eq(OpsAlert::getStatus, status.toUpperCase());
        }
        return alertMapper.selectList(query);
    }

    public List<OpsIncident> listIncidents(String status, int limit) {
        LambdaQueryWrapper<OpsIncident> query = new LambdaQueryWrapper<OpsIncident>()
                .orderByDesc(OpsIncident::getId)
                .last("LIMIT " + clampLimit(limit));
        if (status != null && !status.isBlank()) {
            query.eq(OpsIncident::getStatus, status.toUpperCase());
        }
        return incidentMapper.selectList(query);
    }

    public OpsIncident updateIncident(Long id, Map<String, String> changes) {
        OpsIncident incident = incidentMapper.selectById(id);
        if (incident == null) {
            throw new IllegalArgumentException("事件不存在");
        }
        String status = changes.get("status");
        String normalized = status == null ? incident.getStatus() : status.trim().toUpperCase();
        if (!List.of("OPEN", "ACKNOWLEDGED", "MITIGATING", "VERIFYING", "RESOLVED", "CLOSED")
                .contains(normalized)) {
            throw new IllegalArgumentException("不支持的事件状态");
        }
        incident.setStatus(normalized);
        String assignee = changes.get("assignee");
        if (assignee != null) {
            incident.setAssignee(assignee.trim());
        }
        if (changes.containsKey("rootCause")) {
            incident.setRootCause(trimToNull(changes.get("rootCause")));
        }
        if (changes.containsKey("resolution")) {
            incident.setResolution(trimToNull(changes.get("resolution")));
        }
        if (changes.containsKey("postmortem")) {
            incident.setPostmortem(trimToNull(changes.get("postmortem")));
        }
        if (List.of("RESOLVED", "CLOSED").contains(normalized)) {
            incident.setResolvedAt(LocalDateTime.now());
        }
        incidentMapper.updateById(incident);
        return incident;
    }

    /** 从事件上下文启动处置任务，并把事件、Runbook、执行记录三者稳定关联。 */
    @Transactional
    public ExecutionTaskResponse executeIncident(Long incidentId,
                                                  IncidentExecutionRequest request,
                                                  String actor,
                                                  String ipAddress) {
        OpsIncident incident = incidentMapper.selectById(incidentId);
        if (incident == null) {
            throw new IllegalArgumentException("事件不存在");
        }
        if (incident.getExecutionId() != null) {
            return executionTaskService.get(incident.getExecutionId());
        }
        return startIncidentExecution(incident, request.getRunbookId(), request.getInputData(),
                request.getIdempotencyKey(), actor, ipAddress);
    }

    /**
     * 执行结束后推进事件状态：有健康检查且全部通过才自动恢复；
     * 没有健康证据时进入待验证，绝不因为“脚本没报错”就直接关闭事件。
     */
    @Transactional
    public void onExecutionCompleted(Long executionId, ExecutionResponse response) {
        OpsIncident incident = incidentMapper.selectOne(new LambdaQueryWrapper<OpsIncident>()
                .eq(OpsIncident::getExecutionId, executionId)
                .last("LIMIT 1"));
        if (incident == null || response == null) {
            return;
        }
        if (!"SUCCESS".equals(response.getStatus())) {
            if (!"WAITING_APPROVAL".equals(response.getStatus())) {
                incident.setStatus("OPEN");
            }
            incidentMapper.updateById(incident);
            return;
        }

        HealthEvidence evidence = inspectHealthEvidence(response);
        if (evidence.observed() && !evidence.unhealthy()) {
            incident.setStatus("RESOLVED");
            incident.setResolvedAt(LocalDateTime.now());
            if (incident.getResolution() == null) {
                incident.setResolution("Runbook 执行成功，且自动健康验证全部通过");
            }
        } else if (evidence.unhealthy()) {
            incident.setStatus("OPEN");
        } else {
            incident.setStatus("VERIFYING");
        }
        incidentMapper.updateById(incident);
    }

    private void upsertAlert(AlertWebhookRequest request, AlertWebhookRequest.AlertItem item) {
        Map<String, String> labels = item.getLabels() == null ? Map.of() : item.getLabels();
        Map<String, String> annotations = item.getAnnotations() == null ? Map.of() : item.getAnnotations();
        String alertName = firstNonBlank(labels.get("alertname"), labels.get("alert_name"), "未命名告警");
        String fingerprint = firstNonBlank(item.getFingerprint(), hash(alertName + JSON.toJSONString(labels)));
        String status = firstNonBlank(item.getStatus(), request.getStatus(), "firing").toUpperCase();
        if ("RESOLVED".equals(status)) {
            status = "RESOLVED";
        } else {
            status = "FIRING";
        }

        OpsAlert alert = alertMapper.selectOne(new LambdaQueryWrapper<OpsAlert>()
                .eq(OpsAlert::getFingerprint, fingerprint)
                .last("LIMIT 1"));
        boolean isNew = alert == null;
        String previousStatus = isNew ? null : alert.getStatus();
        if (isNew) {
            alert = new OpsAlert();
            alert.setFingerprint(fingerprint);
        }
        alert.setAlertName(alertName);
        alert.setSeverity(firstNonBlank(labels.get("severity"), "warning").toLowerCase());
        alert.setStatus(status);
        alert.setSource(firstNonBlank(request.getSource(), "alertmanager"));
        alert.setSummary(firstNonBlank(annotations.get("summary"), annotations.get("description"), alertName));
        alert.setLabels(JSON.toJSONString(labels));
        alert.setAnnotations(JSON.toJSONString(annotations));
        alert.setStartsAt(parseDateTime(item.getStartsAt()));
        alert.setEndsAt(parseDateTime(item.getEndsAt()));

        if ("FIRING".equals(status)) {
            OpsIncident incident = alert.getIncidentId() == null
                    ? null
                    : incidentMapper.selectById(alert.getIncidentId());
            boolean newOccurrence = incident == null
                    || "RESOLVED".equals(previousStatus)
                    || List.of("RESOLVED", "CLOSED").contains(incident.getStatus());
            if (newOccurrence) {
                incident = createIncident(alert);
                alert.setIncidentId(incident.getId());
            } else if (!isNew) {
                incident.setAlertCount((incident.getAlertCount() == null ? 1 : incident.getAlertCount()) + 1);
                incidentMapper.updateById(incident);
            }
            applyRunbookRouting(incident, labels, annotations, alert);
        } else if ("RESOLVED".equals(status) && alert.getIncidentId() != null) {
            resolveIncident(alert.getIncidentId());
        }

        if (isNew) {
            alertMapper.insert(alert);
        } else {
            alertMapper.updateById(alert);
        }
    }

    private void applyRunbookRouting(OpsIncident incident,
                                     Map<String, String> labels,
                                     Map<String, String> annotations,
                                     OpsAlert alert) {
        Long runbookId = parseLong(firstNonBlank(
                annotations.get("paiops_runbook_id"), labels.get("paiops_runbook_id")));
        if (runbookId == null) {
            return;
        }
        incident.setRunbookId(runbookId);
        incidentMapper.updateById(incident);

        boolean autoExecute = Boolean.parseBoolean(firstNonBlank(
                annotations.get("paiops_auto_execute"), labels.get("paiops_auto_execute"), "false"));
        if (!autoExecute || incident.getExecutionId() != null) {
            return;
        }
        Workflow workflow = workflowService.getById(runbookId);
        if (!runbookSafetyService.isReadOnly(workflow)) {
            // 告警入口只允许全只读 Runbook 自动运行，其他处置必须由事件中心人工启动。
            return;
        }
        String input = JSON.toJSONString(Map.of(
                "source", "alert-webhook",
                "alertId", alert.getId() == null ? 0L : alert.getId(),
                "incidentId", incident.getId(),
                "fingerprint", alert.getFingerprint(),
                "labels", labels,
                "annotations", annotations
        ));
        startIncidentExecution(incident, runbookId, input,
                "alert:" + alert.getFingerprint() + ":incident:" + incident.getId(),
                "alert-webhook", null);
    }

    private ExecutionTaskResponse startIncidentExecution(OpsIncident incident,
                                                          Long runbookId,
                                                          String supplementalInput,
                                                          String idempotencyKey,
                                                          String actor,
                                                          String ipAddress) {
        Workflow workflow = workflowService.getById(runbookId);
        if (workflow == null) {
            throw new IllegalArgumentException("关联 Runbook 不存在");
        }
        ExecutionTaskRequest taskRequest = new ExecutionTaskRequest();
        taskRequest.setInputData(JSON.toJSONString(Map.of(
                "incidentId", incident.getId(),
                "title", incident.getTitle(),
                "severity", incident.getSeverity(),
                "summary", incident.getSummary() == null ? "" : incident.getSummary(),
                "supplementalInput", supplementalInput == null ? "" : supplementalInput
        )));
        taskRequest.setIdempotencyKey(firstNonBlank(
                idempotencyKey,
                "incident:" + incident.getId() + ":runbook:" + runbookId
        ));
        ExecutionTaskResponse task = executionTaskService.submit(
                runbookId, taskRequest, firstNonBlank(actor, "system"), ipAddress);
        incident.setRunbookId(runbookId);
        incident.setExecutionId(task.getExecutionId());
        incident.setStatus("MITIGATING");
        incidentMapper.updateById(incident);
        return task;
    }

    private HealthEvidence inspectHealthEvidence(ExecutionResponse response) {
        boolean observed = false;
        boolean unhealthy = false;
        if (response.getNodeResults() != null) {
            for (ExecutionResponse.NodeResult nodeResult : response.getNodeResults()) {
                if (nodeResult.getOutput() == null || nodeResult.getOutput().isBlank()) {
                    continue;
                }
                try {
                    Object healthy = JSON.parseObject(nodeResult.getOutput()).get("healthy");
                    if (healthy instanceof Boolean value) {
                        observed = true;
                        unhealthy = unhealthy || !value;
                    }
                } catch (Exception ignored) {
                    // 非 JSON 或不含健康字段的普通节点不参与验证结论。
                }
            }
        }
        return new HealthEvidence(observed, unhealthy);
    }

    private record HealthEvidence(boolean observed, boolean unhealthy) {
    }

    private OpsIncident createIncident(OpsAlert alert) {
        OpsIncident incident = new OpsIncident();
        incident.setTitle(alert.getAlertName());
        incident.setSeverity(alert.getSeverity());
        incident.setStatus("OPEN");
        incident.setSummary(alert.getSummary());
        incident.setAlertCount(1);
        incident.setStartedAt(alert.getStartsAt() == null ? LocalDateTime.now() : alert.getStartsAt());
        incidentMapper.insert(incident);
        return incident;
    }

    private void resolveIncident(Long incidentId) {
        OpsIncident incident = incidentMapper.selectById(incidentId);
        if (incident == null || List.of("RESOLVED", "CLOSED").contains(incident.getStatus())) {
            return;
        }
        incident.setStatus("RESOLVED");
        incident.setResolvedAt(LocalDateTime.now());
        incidentMapper.updateById(incident);
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank() || value.startsWith("0001-")) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(value), ZoneId.systemDefault());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成告警指纹", exception);
        }
    }

    private int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 500));
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
