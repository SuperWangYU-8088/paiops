package com.paiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ExecutionTaskRequest {

    @NotBlank(message = "输入数据不能为空")
    private String inputData;
    private String idempotencyKey;
}
