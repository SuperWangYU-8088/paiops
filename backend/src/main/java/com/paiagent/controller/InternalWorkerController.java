package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.dto.ExecutionJob;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.service.ExecutionTaskService;
import com.paiagent.service.ExecutionWorkerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/internal/executions")
public class InternalWorkerController {

    private final ExecutionWorkerService executionWorkerService;
    private final ExecutionTaskService executionTaskService;
    private final String workerToken;

    public InternalWorkerController(ExecutionWorkerService executionWorkerService,
                                    ExecutionTaskService executionTaskService,
                                    @Value("${paiops.worker.token:}") String workerToken) {
        this.executionWorkerService = executionWorkerService;
        this.executionTaskService = executionTaskService;
        this.workerToken = workerToken == null ? "" : workerToken;
    }

    @PostMapping("/{id}/run")
    public Result<ExecutionResponse> run(
            @PathVariable Long id,
            @RequestBody ExecutionJob job,
            @RequestHeader(value = "X-PaiOps-Worker-Token", required = false) String token,
            @RequestHeader("X-PaiOps-Worker-ID") String workerId) {
        authorize(token);
        if (!id.equals(job.getExecutionId())) {
            throw new IllegalArgumentException("执行任务 ID 不匹配");
        }
        return Result.success(executionWorkerService.run(job, workerId));
    }

    @PostMapping("/{id}/heartbeat")
    public Result<Void> heartbeat(
            @PathVariable Long id,
            @RequestHeader(value = "X-PaiOps-Worker-Token", required = false) String token,
            @RequestHeader("X-PaiOps-Worker-ID") String workerId) {
        authorize(token);
        executionWorkerService.heartbeat(id, workerId);
        return Result.success();
    }

    @GetMapping("/stale")
    public Result<List<Long>> stale(
            @RequestHeader(value = "X-PaiOps-Worker-Token", required = false) String token,
            @RequestParam(defaultValue = "90") int staleAfterSeconds) {
        authorize(token);
        return Result.success(executionTaskService.findStaleExecutionIds(staleAfterSeconds));
    }

    @PostMapping("/{id}/requeue")
    public Result<Map<String, Boolean>> requeue(
            @PathVariable Long id,
            @RequestHeader(value = "X-PaiOps-Worker-Token", required = false) String token,
            @RequestParam(defaultValue = "90") int staleAfterSeconds) {
        authorize(token);
        return Result.success(Map.of(
                "requeued", executionTaskService.requeueStale(id, staleAfterSeconds)
        ));
    }

    private void authorize(String token) {
        // 内部接口虽然不走用户 JWT，但必须使用独立 Worker 令牌。
        // MessageDigest.isEqual 采用常量时间比较，减少令牌逐字节猜测的时序侧信道。
        if (workerToken.isBlank() || token == null || !MessageDigest.isEqual(
                workerToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Worker 内部令牌无效或未配置"
            );
        }
    }
}
