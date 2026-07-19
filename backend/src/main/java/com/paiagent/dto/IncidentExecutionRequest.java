package com.paiagent.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 从事件中心启动处置 Runbook 的请求。 */
@Data
public class IncidentExecutionRequest {

    @NotNull(message = "Runbook ID 不能为空")
    private Long runbookId;

    /** 可选的补充上下文；告警和事件基础信息由服务端自动注入。 */
    private String inputData;

    private String idempotencyKey;
}
