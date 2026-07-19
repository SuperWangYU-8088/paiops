package com.paiagent.engine.executor.impl;

import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.ConnectorCredentialService;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DatabaseHealthCheckNodeExecutor implements NodeExecutor {

    private final ConnectorCredentialService credentialService;

    public DatabaseHealthCheckNodeExecutor(ConnectorCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
        Long credentialId = OpsHttpSupport.longValue(node, "credentialId");
        if (credentialId == null) {
            throw new IllegalArgumentException("数据库检查必须选择加密凭证");
        }
        Map<String, String> credential = credentialService.getSecretData(credentialId);
        String jdbcUrl = OpsHttpSupport.requiredText(credential.get("jdbcUrl"), "数据库凭证缺少 jdbcUrl");
        if (!jdbcUrl.startsWith("jdbc:mysql://")) {
            throw new SecurityException("当前数据库健康检查仅允许 MySQL JDBC 地址");
        }
        String username = OpsHttpSupport.requiredText(credential.get("username"), "数据库凭证缺少 username");
        String password = credential.getOrDefault("password", "");
        int timeoutSeconds = OpsHttpSupport.integer(node, "timeoutSeconds", 10, 1, 30);

        long startedAt = System.nanoTime();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("credentialId", credentialId);
        try {
            DriverManager.setLoginTimeout(timeoutSeconds);
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
                connection.setReadOnly(true);
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(timeoutSeconds);
                    try (ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                        output.put("healthy", resultSet.next() && resultSet.getInt(1) == 1);
                    }
                }
                output.put("databaseProduct", connection.getMetaData().getDatabaseProductName());
                output.put("databaseVersion", connection.getMetaData().getDatabaseProductVersion());
            }
        } catch (Exception exception) {
            output.put("healthy", false);
            output.put("error", exception.getMessage());
        }
        output.put("responseTimeMs", Math.max(0, (System.nanoTime() - startedAt) / 1_000_000));
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        return "database_health_check";
    }
}
