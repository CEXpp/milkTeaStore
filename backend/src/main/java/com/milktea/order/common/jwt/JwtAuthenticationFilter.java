package com.milktea.order.common.jwt;

import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.JwtUtil;
import com.milktea.order.common.jwt.AuthContext.Principal;
import com.milktea.order.common.result.R;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * JWT 过滤器链：按 HLD 5.1「双体系路径矩阵」分派鉴权。
 * <p>
 * 矩阵（与 LLD 9.1 一致）：
 * <pre>
 *   /api/admin/**         → 商家 JWT（role=admin）；其中 /api/admin/login 为白名单
 *   /api/customer/**      → 顾客 JWT（role=customer）；login / dev-login / menu / shop-status 为白名单
 *   /api/customer/ai/**   → 顾客 JWT（会话与草稿绑定顾客身份，随 /api/customer/** 规则）
 *   /api/files/**         → 公开（图片代理流）
 *   /actuator/**、OPTIONS → 放行
 * </pre>
 * 失败处理（HLD 5.2：401/403 以真实 HTTP 状态码承载，同时返回统一 R 体）：
 * <ul>
 *   <li>token 缺失 / 签名失效 / 过期 → 401（UNAUTHORIZED）</li>
 *   <li>角色与路径体系不符（如顾客令牌访问 /api/admin/**）→ 403（FORBIDDEN）</li>
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@ConditionalOnWebApplication
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    /** 白名单：完全放行，无需 token。 */
    private static final List<String> EXACT_WHITELIST = List.of(
            "/api/admin/login",
            "/api/customer/login",
            "/api/customer/dev-login",
            "/api/customer/menu",
            "/api/customer/shop-status",
            "/error"
    );

    /** 前缀白名单：放行整段公开资源。 */
    private static final List<String> PREFIX_WHITELIST = List.of(
            "/api/files/",
            "/actuator/"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        // 预检请求直接放行，交由 CORS 配置处理
        if ("OPTIONS".equalsIgnoreCase(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 白名单放行
        if (isWhitelisted(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 解析该路径所需的角色体系（null 表示不强制鉴权）
        RequiredRole required = resolveRequiredRole(path);
        if (required == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = extractToken(request);
        if (token == null) {
            writeUnauthorized(response, "未登录或令牌缺失");
            return;
        }

        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[T06] JWT 解析失败 path={} cause={}", path, e.getMessage());
            writeUnauthorized(response, ErrorCode.UNAUTHORIZED.getMessage());
            return;
        }

        String role = jwtUtil.getRole(claims);
        if (role == null) {
            writeUnauthorized(response, "令牌缺少角色声明");
            return;
        }

        if (required == RequiredRole.ADMIN && !JwtUtil.ROLE_ADMIN.equals(role)) {
            writeForbidden(response, "无权限访问商家端");
            return;
        }
        if (required == RequiredRole.CUSTOMER && !JwtUtil.ROLE_CUSTOMER.equals(role)) {
            writeForbidden(response, "无权限访问顾客端");
            return;
        }

        // 鉴权通过：写入身份上下文，并在请求属性中透传，供下游 Controller 使用
        Principal principal = buildPrincipal(role, claims);
        AuthContext.set(principal);
        request.setAttribute("authPrincipal", principal);

        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private boolean isWhitelisted(String path) {
        if (EXACT_WHITELIST.contains(path)) {
            return true;
        }
        for (String prefix : PREFIX_WHITELIST) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 依据路径返回所需角色体系；返回 null 表示该路径不强制鉴权。 */
    private RequiredRole resolveRequiredRole(String path) {
        if (path.startsWith("/api/admin/")) {
            return RequiredRole.ADMIN;
        }
        if (path.startsWith("/api/customer/")) {
            return RequiredRole.CUSTOMER;
        }
        return null;
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        header = header.trim();
        if (header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            return token.isEmpty() ? null : token;
        }
        // 兼容无 Bearer 前缀的裸 token
        return header;
    }

    private Principal buildPrincipal(String role, Claims claims) {
        if (JwtUtil.ROLE_ADMIN.equals(role)) {
            Long adminId = claims.get(JwtUtil.CLAIM_ADMIN_ID, Long.class);
            String username = claims.get(JwtUtil.CLAIM_USERNAME, String.class);
            return Principal.admin(adminId, username);
        }
        Long customerId = claims.get(JwtUtil.CLAIM_CUSTOMER_ID, Long.class);
        String openid = claims.get(JwtUtil.CLAIM_OPENID, String.class);
        return Principal.customer(customerId, openid);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        writeError(response, HttpStatus.UNAUTHORIZED.value(), ErrorCode.UNAUTHORIZED.getCode(), message);
    }

    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        writeError(response, HttpStatus.FORBIDDEN.value(), ErrorCode.FORBIDDEN.getCode(), message);
    }

    private void writeError(HttpServletResponse response, int httpStatus, int code, String message) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(code, message)));
    }

    private enum RequiredRole {
        ADMIN, CUSTOMER
    }
}
