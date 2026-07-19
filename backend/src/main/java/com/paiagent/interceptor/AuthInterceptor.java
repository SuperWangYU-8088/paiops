package com.paiagent.interceptor;

import com.paiagent.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    
    @Autowired
    private AuthService authService;
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        
        if (token == null && isSseRequest(request)) {
            String username = authService.consumeSseTicket(request.getParameter("ticket"));
            if (username != null) {
                request.setAttribute("username", username);
                return true;
            }
        }
        
        if (token == null || !authService.validateToken(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未认证或认证已过期\"}");
            return false;
        }
        
        String username = authService.getUsernameByToken(token);
        request.setAttribute("username", username);
        request.setAttribute("role", authService.getRoleByToken(token));
        
        return true;
    }

    private boolean isSseRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "GET".equals(request.getMethod())
                && (path.matches("/api/executions/\\d+/stream")
                || path.matches("/api/workflows/\\d+/execute/stream"));
    }
}
