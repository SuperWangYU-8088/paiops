package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.entity.AuditLog;
import com.paiagent.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AuditLogService {

    private static final Set<String> SECRET_FIELDS = Set.of(
            "password", "secret", "secretkey", "apikey", "api_key", "token",
            "bearertoken", "authorization", "kubeconfig", "privatekey", "credential"
    );
    private final AuditLogMapper auditLogMapper;

    public AuditLogService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public void record(String actor, String action, String resourceType, Object resourceId,
                       String result, Object detail, String ipAddress) {
        AuditLog log = new AuditLog();
        log.setActor(actor == null || actor.isBlank() ? "system" : actor);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId == null ? null : String.valueOf(resourceId));
        log.setResult(result);
        log.setDetail(detail == null ? null : JSON.toJSONString(redact(detail)));
        log.setIpAddress(ipAddress);
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    public List<AuditLog> listRecent(int limit) {
        int resolvedLimit = Math.max(1, Math.min(limit, 200));
        return auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                .orderByDesc(AuditLog::getId)
                .last("LIMIT " + resolvedLimit));
    }

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String field = String.valueOf(key);
                result.put(field, isSecret(field) ? "[REDACTED]" : redact(item));
            });
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(item -> result.add(redact(item)));
            return result;
        }
        return value;
    }

    private boolean isSecret(String field) {
        String normalized = field.replace("-", "").replace("_", "").toLowerCase();
        return SECRET_FIELDS.stream()
                .map(name -> name.replace("_", ""))
                .anyMatch(normalized::contains);
    }
}
