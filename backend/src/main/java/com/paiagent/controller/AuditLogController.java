package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.entity.AuditLog;
import com.paiagent.service.AuditLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public Result<List<AuditLog>> list(@RequestParam(defaultValue = "100") int limit) {
        return Result.success(auditLogService.listRecent(limit));
    }
}
