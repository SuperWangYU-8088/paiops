package com.paiagent.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 敏感管理接口的最小 RBAC 边界。
 * 当前个人部署账户签发 ADMIN 角色；后续接入企业身份源时可直接扩展角色映射，
 * 不会再让普通运维会话默认获得凭证、模型密钥、MCP 进程和审批管理权限。
 */
@Component
public class AdminAuthorizationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        if ("ADMIN".equals(request.getAttribute("role"))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"当前账户没有敏感管理权限\"}");
        return false;
    }
}
