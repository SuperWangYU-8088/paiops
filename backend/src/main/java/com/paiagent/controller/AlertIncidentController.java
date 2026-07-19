package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.dto.AlertWebhookRequest;
import com.paiagent.dto.ExecutionTaskResponse;
import com.paiagent.dto.IncidentExecutionRequest;
import com.paiagent.entity.OpsAlert;
import com.paiagent.entity.OpsIncident;
import com.paiagent.service.AlertIncidentService;
import com.paiagent.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AlertIncidentController {

    private final AlertIncidentService alertIncidentService;
    private final AuditLogService auditLogService;
    private final String webhookToken;

    public AlertIncidentController(AlertIncidentService alertIncidentService,
                                   AuditLogService auditLogService,
                                   @Value("${paiops.alerts.webhook-token:}") String webhookToken) {
        this.alertIncidentService = alertIncidentService;
        this.auditLogService = auditLogService;
        this.webhookToken = webhookToken == null ? "" : webhookToken;
    }

    @PostMapping("/alerts/webhook")
    public Result<Map<String, Object>> ingest(
            @RequestHeader(value = "X-PaiOps-Webhook-Token", required = false) String token,
            @RequestBody AlertWebhookRequest request,
            HttpServletRequest servletRequest) {
        if (!isValidWebhookToken(token)) {
            return Result.error(401, "告警 Webhook Token 无效或未配置");
        }
        int processed = alertIncidentService.ingest(request);
        auditLogService.record("alert-webhook", "ALERT_INGEST", "ALERT", null,
                "SUCCESS", Map.of("processed", processed, "source", String.valueOf(request.getSource())),
                servletRequest.getRemoteAddr());
        return Result.success(Map.of("processed", processed));
    }

    @GetMapping("/alerts")
    public Result<List<OpsAlert>> listAlerts(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(alertIncidentService.listAlerts(status, limit));
    }

    @GetMapping("/incidents")
    public Result<List<OpsIncident>> listIncidents(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(alertIncidentService.listIncidents(status, limit));
    }

    @PatchMapping("/incidents/{id}")
    public Result<OpsIncident> updateIncident(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            HttpServletRequest servletRequest) {
        OpsIncident incident = alertIncidentService.updateIncident(id, request);
        auditLogService.record((String) servletRequest.getAttribute("username"),
                "INCIDENT_UPDATE", "INCIDENT", id, "SUCCESS",
                Map.of("status", incident.getStatus(), "assignee", String.valueOf(incident.getAssignee())),
                servletRequest.getRemoteAddr());
        return Result.success(incident);
    }

    @PostMapping("/incidents/{id}/execute")
    public Result<ExecutionTaskResponse> executeIncident(
            @PathVariable Long id,
            @Valid @RequestBody IncidentExecutionRequest request,
            HttpServletRequest servletRequest) {
        String actor = (String) servletRequest.getAttribute("username");
        ExecutionTaskResponse task = alertIncidentService.executeIncident(
                id, request, actor, servletRequest.getRemoteAddr());
        auditLogService.record(actor, "INCIDENT_RUNBOOK_EXECUTE", "INCIDENT", id,
                "SUCCESS", Map.of(
                        "runbookId", request.getRunbookId(),
                        "executionId", task.getExecutionId()
                ), servletRequest.getRemoteAddr());
        return Result.success(task);
    }

    private boolean isValidWebhookToken(String token) {
        if (webhookToken.isBlank() || token == null) {
            return false;
        }
        return MessageDigest.isEqual(
                webhookToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        );
    }
}
