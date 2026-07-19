package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.dto.ExecutionTaskRequest;
import com.paiagent.dto.ExecutionTaskResponse;
import com.paiagent.service.ExecutionTaskService;
import com.paiagent.service.TaskEventBroker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ExecutionTaskController {

    private final ExecutionTaskService executionTaskService;
    private final TaskEventBroker eventBroker;

    public ExecutionTaskController(ExecutionTaskService executionTaskService,
                                   TaskEventBroker eventBroker) {
        this.executionTaskService = executionTaskService;
        this.eventBroker = eventBroker;
    }

    @PostMapping("/workflows/{id}/tasks")
    public Result<ExecutionTaskResponse> submit(@PathVariable Long id,
                                                @Valid @RequestBody ExecutionTaskRequest request,
                                                HttpServletRequest servletRequest) {
        return Result.success(executionTaskService.submit(
                id,
                request,
                (String) servletRequest.getAttribute("username"),
                servletRequest.getRemoteAddr()
        ));
    }

    @GetMapping("/executions")
    public Result<List<ExecutionTaskResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(executionTaskService.list(status, limit));
    }

    @GetMapping("/executions/{id}")
    public Result<ExecutionTaskResponse> get(@PathVariable Long id) {
        return Result.success(executionTaskService.get(id));
    }

    @PostMapping("/executions/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id, HttpServletRequest servletRequest) {
        executionTaskService.requestCancel(
                id,
                (String) servletRequest.getAttribute("username"),
                servletRequest.getRemoteAddr()
        );
        return Result.success();
    }

    @GetMapping(value = "/executions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id) {
        executionTaskService.requireExecution(id);
        return eventBroker.subscribe(id);
    }
}
