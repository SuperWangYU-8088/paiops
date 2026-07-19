package com.paiagent.common;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 将服务层异常稳定地转换为 HTTP 语义。
 *
 * <p>控制器不应把安全策略、参数错误和状态冲突都暴露成 500。明确的状态码既方便
 * 前端展示，也能让 Go Worker 判断“令牌无效”和“服务临时故障”是否应该重试。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> handleResponseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return build(status, exception.getReason());
    }

    /**
     * SecurityException 表示请求已被运维安全策略明确拒绝，而不是服务内部故障。
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Result<Void>> handleForbidden(SecurityException exception) {
        return build(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            ConstraintViolationException.class,
            MethodArgumentNotValidException.class
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception exception) {
        return build(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * 状态冲突通常来自重复审批、重复领取或对终态任务再次操作，返回 409 便于调用方
     * 停止无意义重试，同时保留原任务状态。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> handleConflict(IllegalStateException exception) {
        return build(HttpStatus.CONFLICT, exception.getMessage());
    }

    private ResponseEntity<Result<Void>> build(HttpStatus status, String message) {
        String safeMessage = message == null || message.isBlank() ? status.getReasonPhrase() : message;
        return ResponseEntity.status(status).body(Result.error(status.value(), safeMessage));
    }
}
