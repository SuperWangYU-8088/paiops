package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.dto.ConnectorCredentialRequest;
import com.paiagent.dto.ConnectorCredentialResponse;
import com.paiagent.service.AuditLogService;
import com.paiagent.service.ConnectorCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/credentials")
public class ConnectorCredentialController {

    private final ConnectorCredentialService credentialService;
    private final AuditLogService auditLogService;

    public ConnectorCredentialController(ConnectorCredentialService credentialService,
                                         AuditLogService auditLogService) {
        this.credentialService = credentialService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public Result<List<ConnectorCredentialResponse>> list() {
        return Result.success(credentialService.list());
    }

    @PostMapping
    public Result<ConnectorCredentialResponse> create(@Valid @RequestBody ConnectorCredentialRequest request,
                                                      HttpServletRequest servletRequest) {
        ConnectorCredentialResponse response = credentialService.create(request);
        audit(servletRequest, "CREDENTIAL_CREATE", response.getId(), Map.of(
                "name", response.getName(),
                "connectorType", response.getConnectorType(),
                "secretFields", response.getSecretFields()
        ));
        return Result.success(response);
    }

    @PutMapping("/{id}")
    public Result<ConnectorCredentialResponse> update(@PathVariable Long id,
                                                      @Valid @RequestBody ConnectorCredentialRequest request,
                                                      HttpServletRequest servletRequest) {
        ConnectorCredentialResponse response = credentialService.update(id, request);
        audit(servletRequest, "CREDENTIAL_UPDATE", id, Map.of(
                "name", response.getName(),
                "connectorType", response.getConnectorType(),
                "secretFields", response.getSecretFields()
        ));
        return Result.success(response);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest servletRequest) {
        credentialService.delete(id);
        audit(servletRequest, "CREDENTIAL_DELETE", id, Map.of());
        return Result.success();
    }

    private void audit(HttpServletRequest request, String action, Long id, Object detail) {
        auditLogService.record(
                (String) request.getAttribute("username"),
                action,
                "CREDENTIAL",
                id,
                "SUCCESS",
                detail,
                request.getRemoteAddr()
        );
    }
}
