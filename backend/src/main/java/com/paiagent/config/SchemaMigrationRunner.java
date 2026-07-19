package com.paiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 对已有 MySQL 数据库执行小型、幂等的兼容升级。
 *
 * <p>测试环境使用独立的 H2 最小结构，不应执行这些 MySQL 专用 DDL，
 * 因此提供开关用于测试和特殊维护场景关闭自动迁移。</p>
 */
@Slf4j
@Component
@Order(0)
@ConditionalOnProperty(prefix = "paiops.schema-migration", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchemaMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        createTableIfMissing("app_user", """
                CREATE TABLE app_user (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户主键 ID',
                    username VARCHAR(100) NOT NULL COMMENT '登录名',
                    password_hash VARCHAR(512) NOT NULL COMMENT 'PBKDF2 不可逆口令哈希',
                    role VARCHAR(50) NOT NULL DEFAULT 'ADMIN' COMMENT '角色',
                    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
                    token_version INT NOT NULL DEFAULT 0 COMMENT '令牌版本，修改密码后递增',
                    password_changed_at TIMESTAMP NULL COMMENT '最近修改密码时间',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    UNIQUE KEY uk_app_user_username (username),
                    INDEX idx_app_user_enabled (enabled)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应用用户'
                """);
        createTableIfMissing("knowledge_base", """
                CREATE TABLE knowledge_base (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识库 ID',
                    name VARCHAR(100) NOT NULL COMMENT '知识库名称',
                    description VARCHAR(500) DEFAULT NULL COMMENT '知识库描述',
                    config_id BIGINT DEFAULT NULL COMMENT 'Agent Plan 全局配置 ID',
                    embedding_model VARCHAR(100) DEFAULT NULL COMMENT '向量模型',
                    chunk_size INT DEFAULT 800 COMMENT '分片长度',
                    chunk_overlap INT DEFAULT 100 COMMENT '分片重叠长度',
                    status VARCHAR(30) DEFAULT 'DRAFT' COMMENT '状态',
                    document_count INT DEFAULT 0 COMMENT '文档数',
                    chunk_count INT DEFAULT 0 COMMENT '分片数',
                    char_count BIGINT DEFAULT 0 COMMENT '字符数',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_knowledge_base_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库表'
                """);
        createTableIfMissing("knowledge_document", """
                CREATE TABLE knowledge_document (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识文档 ID',
                    knowledge_base_id BIGINT NOT NULL COMMENT '知识库 ID',
                    title VARCHAR(255) DEFAULT NULL COMMENT '文档标题',
                    source_type VARCHAR(30) DEFAULT 'TEXT' COMMENT '来源类型',
                    source_url VARCHAR(1024) DEFAULT NULL COMMENT '来源 URL',
                    file_name VARCHAR(255) DEFAULT NULL COMMENT '文件名',
                    raw_text MEDIUMTEXT COMMENT '原始文本',
                    tags VARCHAR(500) DEFAULT NULL COMMENT '标签',
                    status VARCHAR(30) DEFAULT 'IMPORTED' COMMENT '状态',
                    char_count BIGINT DEFAULT 0 COMMENT '字符数',
                    error_message TEXT COMMENT '错误信息',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_knowledge_document_base (knowledge_base_id),
                    INDEX idx_knowledge_document_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档表'
                """);
        createTableIfMissing("knowledge_index_task", """
                CREATE TABLE knowledge_index_task (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '索引任务 ID',
                    knowledge_base_id BIGINT NOT NULL COMMENT '知识库 ID',
                    document_id BIGINT NOT NULL COMMENT '文档 ID',
                    status VARCHAR(30) DEFAULT 'RUNNING' COMMENT '状态',
                    progress INT DEFAULT 0 COMMENT '进度',
                    total_chunks INT DEFAULT 0 COMMENT '总分片数',
                    finished_chunks INT DEFAULT 0 COMMENT '完成分片数',
                    error_message TEXT COMMENT '错误信息',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_knowledge_index_task_base (knowledge_base_id),
                    INDEX idx_knowledge_index_task_document (document_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库索引任务表'
                """);
        createTableIfMissing("knowledge_chunk", """
                CREATE TABLE knowledge_chunk (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识片段主键 ID',
                    knowledge_base_id BIGINT NOT NULL COMMENT '知识库 ID',
                    document_id BIGINT DEFAULT NULL COMMENT '知识文档 ID',
                    chunk_index INT DEFAULT 0 COMMENT '分片序号',
                    title VARCHAR(255) DEFAULT NULL COMMENT '标题',
                    content TEXT NOT NULL COMMENT '片段内容',
                    source_url VARCHAR(1024) DEFAULT NULL COMMENT '来源 URL',
                    tags JSON COMMENT '标签',
                    embedding_model VARCHAR(100) DEFAULT NULL COMMENT '向量模型',
                    embedding JSON COMMENT '向量数据',
                    status VARCHAR(30) DEFAULT 'READY' COMMENT '状态',
                    char_count INT DEFAULT 0 COMMENT '字符数',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_knowledge_base_id (knowledge_base_id),
                    INDEX idx_knowledge_document_id (document_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库片段表'
                """);
        createTableIfMissing("mcp_tool_config", """
                CREATE TABLE mcp_tool_config (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'MCP 工具配置 ID',
                    name VARCHAR(100) NOT NULL COMMENT '名称',
                    description VARCHAR(500) DEFAULT NULL COMMENT '描述',
                    tool_type VARCHAR(50) DEFAULT 'custom' COMMENT '工具类型',
                    tool_name VARCHAR(100) NOT NULL COMMENT '暴露给 Agent 的工具名',
                    transport VARCHAR(30) DEFAULT 'stdio' COMMENT '传输方式',
                    command VARCHAR(500) NOT NULL COMMENT '启动命令',
                    args JSON COMMENT '启动参数',
                    env JSON COMMENT '环境变量',
                    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
                    preset TINYINT DEFAULT 0 COMMENT '是否预设',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_mcp_tool_name (tool_name),
                    INDEX idx_mcp_tool_type (tool_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP工具配置表'
                """);
        createTableIfMissing("execution_snapshot", """
                CREATE TABLE execution_snapshot (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '快照主键 ID',
                    execution_id BIGINT NOT NULL COMMENT '执行记录 ID',
                    flow_id BIGINT NOT NULL COMMENT '工作流 ID',
                    node_id VARCHAR(100) NOT NULL COMMENT '节点 ID',
                    node_type VARCHAR(50) NOT NULL COMMENT '节点类型',
                    node_name VARCHAR(255) DEFAULT NULL COMMENT '节点名称',
                    status VARCHAR(50) NOT NULL COMMENT '节点状态',
                    input_data JSON COMMENT '节点输入数据',
                    output_data JSON COMMENT '节点输出数据',
                    error_message TEXT COMMENT '错误信息',
                    started_at TIMESTAMP NULL COMMENT '开始执行时间',
                    completed_at TIMESTAMP NULL COMMENT '完成时间',
                    duration INT COMMENT '执行耗时(毫秒)',
                    retry_count INT DEFAULT 0 COMMENT '重试次数',
                    execution_order INT DEFAULT 0 COMMENT '执行顺序',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    INDEX idx_execution_id (execution_id),
                    INDEX idx_flow_id (flow_id),
                    INDEX idx_node_id (node_id),
                    INDEX idx_status (status),
                    INDEX idx_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行快照表'
                """);
        createTableIfMissing("execution_variable", """
                CREATE TABLE execution_variable (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '变量主键 ID',
                    execution_id BIGINT NOT NULL COMMENT '执行记录 ID',
                    variable_name VARCHAR(100) NOT NULL COMMENT '变量名',
                    variable_type VARCHAR(50) DEFAULT 'STRING' COMMENT '变量类型',
                    variable_value TEXT COMMENT '变量值',
                    is_modified TINYINT DEFAULT 0 COMMENT '是否被修改',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    UNIQUE KEY uk_execution_variable (execution_id, variable_name),
                    INDEX idx_execution_id (execution_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行变量表'
                """);
        createTableIfMissing("audit_log", """
                CREATE TABLE audit_log (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    actor VARCHAR(100) NOT NULL DEFAULT 'system',
                    action VARCHAR(100) NOT NULL,
                    resource_type VARCHAR(60) NOT NULL,
                    resource_id VARCHAR(100) DEFAULT NULL,
                    result VARCHAR(30) NOT NULL,
                    detail JSON COMMENT '脱敏后的审计详情',
                    ip_address VARCHAR(64) DEFAULT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_audit_created_at (created_at),
                    INDEX idx_audit_resource (resource_type, resource_id),
                    INDEX idx_audit_action (action)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运维审计日志'
                """);
        createTableIfMissing("connector_credential", """
                CREATE TABLE connector_credential (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    connector_type VARCHAR(60) NOT NULL,
                    encrypted_payload MEDIUMTEXT NOT NULL COMMENT 'AES-GCM 加密凭证',
                    key_version VARCHAR(30) NOT NULL DEFAULT 'v1',
                    description VARCHAR(500) DEFAULT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    deleted TINYINT DEFAULT 0,
                    INDEX idx_credential_type (connector_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='连接器加密凭证'
                """);
        createTableIfMissing("approval_request", """
                CREATE TABLE approval_request (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    execution_id BIGINT NOT NULL,
                    flow_id BIGINT NOT NULL,
                    node_id VARCHAR(100) NOT NULL,
                    node_name VARCHAR(255) DEFAULT NULL,
                    risk_level VARCHAR(30) NOT NULL DEFAULT 'HIGH_RISK',
                    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
                    requested_by VARCHAR(100) DEFAULT 'system',
                    reviewed_by VARCHAR(100) DEFAULT NULL,
                    request_reason VARCHAR(1000) DEFAULT NULL,
                    review_comment VARCHAR(1000) DEFAULT NULL,
                    requested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    reviewed_at TIMESTAMP NULL,
                    expires_at TIMESTAMP NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    deleted TINYINT DEFAULT 0,
                    UNIQUE KEY uk_approval_execution_node (execution_id, node_id),
                    INDEX idx_approval_status (status),
                    INDEX idx_approval_flow (flow_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人工审批请求'
                """);
        createTableIfMissing("ops_incident", """
                CREATE TABLE ops_incident (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    title VARCHAR(255) NOT NULL,
                    severity VARCHAR(30) NOT NULL DEFAULT 'warning',
                    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
                    summary TEXT,
                    assignee VARCHAR(100) DEFAULT NULL,
                    alert_count INT DEFAULT 1,
                    runbook_id BIGINT DEFAULT NULL,
                    execution_id BIGINT DEFAULT NULL,
                    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    resolved_at TIMESTAMP NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    deleted TINYINT DEFAULT 0,
                    INDEX idx_incident_status (status),
                    INDEX idx_incident_severity (severity),
                    INDEX idx_incident_updated (updated_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运维事件'
                """);
        createTableIfMissing("ops_alert", """
                CREATE TABLE ops_alert (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    fingerprint VARCHAR(160) NOT NULL,
                    alert_name VARCHAR(255) NOT NULL,
                    severity VARCHAR(30) NOT NULL DEFAULT 'warning',
                    status VARCHAR(30) NOT NULL DEFAULT 'FIRING',
                    source VARCHAR(60) NOT NULL DEFAULT 'webhook',
                    summary TEXT,
                    labels JSON,
                    annotations JSON,
                    starts_at TIMESTAMP NULL,
                    ends_at TIMESTAMP NULL,
                    incident_id BIGINT DEFAULT NULL,
                    received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    deleted TINYINT DEFAULT 0,
                    UNIQUE KEY uk_alert_fingerprint (fingerprint),
                    INDEX idx_alert_status (status),
                    INDEX idx_alert_severity (severity),
                    INDEX idx_alert_received (received_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='监控告警'
                """);
        createTableIfMissing("execution_outbox", """
                CREATE TABLE execution_outbox (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '发件箱主键 ID',
                    execution_id BIGINT NOT NULL COMMENT '执行记录 ID',
                    queue_key VARCHAR(200) NOT NULL COMMENT '目标 Redis 队列键',
                    payload JSON NOT NULL COMMENT '待投递消息',
                    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SENT',
                    attempts INT NOT NULL DEFAULT 0 COMMENT '投递次数',
                    next_attempt_at TIMESTAMP NULL COMMENT '下次投递时间',
                    last_error VARCHAR(1000) DEFAULT NULL COMMENT '最近一次错误',
                    sent_at TIMESTAMP NULL COMMENT '投递成功时间',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    INDEX idx_outbox_dispatch (status, next_attempt_at),
                    INDEX idx_outbox_execution (execution_id),
                    INDEX idx_outbox_updated (updated_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行任务可靠发件箱'
                """);
        addColumnIfMissing("llm_global_config", "embedding_model",
                "ALTER TABLE llm_global_config ADD COLUMN embedding_model VARCHAR(100) DEFAULT NULL COMMENT '默认向量模型' AFTER tts_model");
        addColumnIfMissing("llm_global_config", "image_model",
                "ALTER TABLE llm_global_config ADD COLUMN image_model VARCHAR(100) DEFAULT NULL COMMENT '默认图片生成模型' AFTER embedding_model");
        addColumnIfMissing("llm_global_config", "video_model",
                "ALTER TABLE llm_global_config ADD COLUMN video_model VARCHAR(100) DEFAULT NULL COMMENT '默认视频生成模型' AFTER image_model");
        addColumnIfMissing("llm_global_config", "memory_enabled",
                "ALTER TABLE llm_global_config ADD COLUMN memory_enabled TINYINT DEFAULT 0 COMMENT '是否启用Agent Plan记忆能力' AFTER video_model");
        addColumnIfMissing("knowledge_chunk", "document_id",
                "ALTER TABLE knowledge_chunk ADD COLUMN document_id BIGINT DEFAULT NULL COMMENT '知识文档 ID' AFTER knowledge_base_id");
        addColumnIfMissing("knowledge_chunk", "chunk_index",
                "ALTER TABLE knowledge_chunk ADD COLUMN chunk_index INT DEFAULT 0 COMMENT '分片序号' AFTER document_id");
        addColumnIfMissing("knowledge_chunk", "status",
                "ALTER TABLE knowledge_chunk ADD COLUMN status VARCHAR(30) DEFAULT 'READY' COMMENT '状态' AFTER embedding");
        addColumnIfMissing("knowledge_chunk", "char_count",
                "ALTER TABLE knowledge_chunk ADD COLUMN char_count INT DEFAULT 0 COMMENT '字符数' AFTER status");
        addColumnIfMissing("execution_record", "worker_id",
                "ALTER TABLE execution_record ADD COLUMN worker_id VARCHAR(100) DEFAULT NULL AFTER duration");
        addColumnIfMissing("execution_record", "heartbeat_at",
                "ALTER TABLE execution_record ADD COLUMN heartbeat_at TIMESTAMP NULL AFTER worker_id");
        addColumnIfMissing("execution_record", "cancel_requested",
                "ALTER TABLE execution_record ADD COLUMN cancel_requested TINYINT DEFAULT 0 AFTER heartbeat_at");
        addColumnIfMissing("execution_record", "queued_at",
                "ALTER TABLE execution_record ADD COLUMN queued_at TIMESTAMP NULL AFTER cancel_requested");
        addColumnIfMissing("execution_record", "started_at",
                "ALTER TABLE execution_record ADD COLUMN started_at TIMESTAMP NULL AFTER queued_at");
        addColumnIfMissing("execution_record", "completed_at",
                "ALTER TABLE execution_record ADD COLUMN completed_at TIMESTAMP NULL AFTER started_at");
        addColumnIfMissing("execution_record", "attempt",
                "ALTER TABLE execution_record ADD COLUMN attempt INT DEFAULT 0 AFTER completed_at");
        addColumnIfMissing("execution_record", "idempotency_key",
                "ALTER TABLE execution_record ADD COLUMN idempotency_key VARCHAR(100) DEFAULT NULL AFTER attempt");
        addColumnIfMissing("execution_record", "execution_mode",
                "ALTER TABLE execution_record ADD COLUMN execution_mode VARCHAR(30) DEFAULT 'ASYNC' AFTER idempotency_key");
        addColumnIfMissing("ops_incident", "root_cause",
                "ALTER TABLE ops_incident ADD COLUMN root_cause TEXT DEFAULT NULL COMMENT '根因分析' AFTER execution_id");
        addColumnIfMissing("ops_incident", "resolution",
                "ALTER TABLE ops_incident ADD COLUMN resolution TEXT DEFAULT NULL COMMENT '处置方案和执行结果' AFTER root_cause");
        addColumnIfMissing("ops_incident", "postmortem",
                "ALTER TABLE ops_incident ADD COLUMN postmortem TEXT DEFAULT NULL COMMENT '复盘记录' AFTER resolution");
        addUniqueIndexIfPossible(
                "execution_record",
                "uk_execution_idempotency",
                "flow_id, idempotency_key, deleted",
                "ALTER TABLE execution_record ADD UNIQUE KEY uk_execution_idempotency (flow_id, idempotency_key, deleted)"
        );
        repairNodeDefinitionPresentation();
        repairKnownConfigurationLabels();
    }

    /** 修复旧部署中因客户端字符集错误写入的预置节点名称和图标。 */
    private void repairNodeDefinitionPresentation() {
        repairNodeDefinitionPresentation("input", "输入", "IN");
        repairNodeDefinitionPresentation("output", "输出", "OUT");
        repairNodeDefinitionPresentation("openai", "OpenAI", "OA");
        repairNodeDefinitionPresentation("deepseek", "DeepSeek", "DS");
        repairNodeDefinitionPresentation("qwen", "通义千问", "QW");
        repairNodeDefinitionPresentation("step", "Step", "ST");
        repairNodeDefinitionPresentation("react_agent", "ReAct Agent", "RA");
        repairNodeDefinitionPresentation("tts", "超拟人音频合成", "TTS");
        repairNodeDefinitionPresentation("condition", "条件分支", "IF");
    }

    private void repairNodeDefinitionPresentation(String nodeType, String displayName, String icon) {
        int updated = jdbcTemplate.update("""
                UPDATE node_definition
                SET display_name = ?, icon = ?
                WHERE node_type = ?
                  AND (display_name <> ? OR icon <> ?)
                """, displayName, icon, nodeType, displayName, icon);
        if (updated > 0) {
            log.info("Schema migrated: repaired node definition presentation {}", nodeType);
        }
    }

    /** 修复本次部署验收中已确认损坏的配置显示名称，不改动模型参数或密钥。 */
    private void repairKnownConfigurationLabels() {
        int updated = jdbcTemplate.update("""
                UPDATE llm_global_config
                SET config_name = 'DeepSeek V4 Flash'
                WHERE provider = 'deepseek'
                  AND config_name = 'DeepSeek V4 Flash(??)'
                """);
        if (updated > 0) {
            log.info("Schema migrated: repaired DeepSeek configuration display name");
        }
    }

    private void createTableIfMissing(String tableName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """, Integer.class, tableName);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("Schema migrated: created {}", tableName);
    }

    private void addColumnIfMissing(String tableName, String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, Integer.class, tableName, columnName);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("Schema migrated: added {}.{}", tableName, columnName);
    }

    /**
     * 在没有历史重复数据时补充唯一索引。
     *
     * <p>旧版本可能已经产生重复幂等键。自动删除或改写历史记录风险较高，
     * 因此检测到重复时只记录明确告警，由运维人员按升级手册完成清理。</p>
     */
    private void addUniqueIndexIfPossible(String tableName,
                                          String indexName,
                                          String groupedColumns,
                                          String ddl) {
        Integer indexCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                """, Integer.class, tableName, indexName);
        if (indexCount != null && indexCount > 0) {
            return;
        }

        String duplicateSql = "SELECT COUNT(*) FROM (SELECT 1 FROM " + tableName
                + " WHERE idempotency_key IS NOT NULL GROUP BY " + groupedColumns
                + " HAVING COUNT(*) > 1) duplicate_groups";
        Integer duplicateGroups = jdbcTemplate.queryForObject(duplicateSql, Integer.class);
        if (duplicateGroups != null && duplicateGroups > 0) {
            log.error("Schema migration skipped unique index {}.{}: found {} duplicate group(s); "
                            + "follow the upgrade manual to resolve them",
                    tableName, indexName, duplicateGroups);
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("Schema migrated: added unique index {}.{}", tableName, indexName);
    }
}
