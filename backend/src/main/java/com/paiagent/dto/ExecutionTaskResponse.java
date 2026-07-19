package com.paiagent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ExecutionTaskResponse {
    private Long executionId;
    private Long flowId;
    private String workflowName;
    private String status;
    private String executionMode;
    private String workerId;
    private Integer attempt;
    private Integer duration;
    private String errorMessage;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime heartbeatAt;
    private LocalDateTime executedAt;
}
