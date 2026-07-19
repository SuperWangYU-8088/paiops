package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.dto.ChangePasswordRequest;
import com.paiagent.dto.LoginRequest;
import com.paiagent.dto.LoginResponse;
import com.paiagent.dto.RefreshTokenRequest;
import com.paiagent.service.AuditLogService;
import com.paiagent.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器
 */
@Tag(name = "认证接口")
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    
    @Autowired
    private AuthService authService;

    @Autowired
    private AuditLogService auditLogService;
    
    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.AuthTokens tokens = authService.login(request.getUsername(), request.getPassword());
        if (tokens != null) {
            LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(request.getUsername());
            LoginResponse response = new LoginResponse(tokens.accessToken(), tokens.refreshToken(), userInfo);
            return Result.success(response);
        }
        return Result.error("用户名或密码错误");
    }
    
    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody(required = false) RefreshTokenRequest request) {
        authService.logout(request != null ? request.getRefreshToken() : null);
        return Result.success();
    }

    @Operation(summary = "刷新访问令牌")
    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@RequestBody RefreshTokenRequest request) {
        if (request == null || request.getRefreshToken() == null || request.getRefreshToken().isBlank()) {
            return Result.unauthorized("Refresh Token 不能为空");
        }

        AuthService.AuthTokens tokens = authService.refresh(request.getRefreshToken());
        if (tokens == null) {
            return Result.unauthorized("Refresh Token 无效或已过期");
        }

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(tokens.username());
        return Result.success(new LoginResponse(tokens.accessToken(), tokens.refreshToken(), userInfo));
    }
    
    @Operation(summary = "获取当前用户信息")
    @GetMapping("/current")
    public Result<LoginResponse.UserInfo> getCurrentUser(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
            String username = authService.validateToken(token)
                    ? authService.getUsernameByToken(token) : null;
            if (username != null) {
                return Result.success(new LoginResponse.UserInfo(username));
            }
        }
        return Result.unauthorized("未认证");
    }

    @Operation(summary = "签发一次性 SSE 连接票据")
    @PostMapping("/sse-ticket")
    public Result<Map<String, String>> issueSseTicket(HttpServletRequest request) {
        String username = (String) request.getAttribute("username");
        return Result.success(Map.of("ticket", authService.issueSseTicket(username)));
    }

    /** 修改成功后旧令牌全部失效，前端会清理本地会话并要求重新登录。 */
    @Operation(summary = "修改当前用户密码")
    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                       HttpServletRequest servletRequest) {
        String username = (String) servletRequest.getAttribute("username");
        try {
            authService.changePassword(username, request.getCurrentPassword(), request.getNewPassword());
            auditLogService.record(username, "PASSWORD_CHANGE", "USER", username,
                    "SUCCESS", Map.of("allSessionsRevoked", true), servletRequest.getRemoteAddr());
            return Result.success();
        } catch (IllegalArgumentException exception) {
            auditLogService.record(username, "PASSWORD_CHANGE", "USER", username,
                    "FAILED", Map.of("reason", exception.getMessage()), servletRequest.getRemoteAddr());
            return Result.error(400, exception.getMessage());
        }
    }
}
