package com.paiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionJob {
    private Long executionId;
    private Long flowId;
    private String mode;
    private String startNodeId;
}
