package com.milktea.order.common.jwt;

import com.milktea.order.common.jwt.JwtUtil;

/**
 * 当前请求的身份上下文（ThreadLocal）。
 * <p>
 * 由 {@link JwtAuthenticationFilter} 在鉴权成功后写入，请求处理完毕后由过滤器统一清理。
 * 下游 Controller / Service 通过 {@link #get()} 读取身份，无需重复解析令牌。
 */
public final class AuthContext {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    public static Principal get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 当前登录主体。 */
    public static final class Principal {

        private final String role;
        private final Long customerId;
        private final String openid;
        private final Long adminId;
        private final String username;

        private Principal(String role, Long customerId, String openid, Long adminId, String username) {
            this.role = role;
            this.customerId = customerId;
            this.openid = openid;
            this.adminId = adminId;
            this.username = username;
        }

        public static Principal customer(Long customerId, String openid) {
            return new Principal(JwtUtil.ROLE_CUSTOMER, customerId, openid, null, null);
        }

        public static Principal admin(Long adminId, String username) {
            return new Principal(JwtUtil.ROLE_ADMIN, null, null, adminId, username);
        }

        public String getRole() {
            return role;
        }

        public Long getCustomerId() {
            return customerId;
        }

        public String getOpenid() {
            return openid;
        }

        public Long getAdminId() {
            return adminId;
        }

        public String getUsername() {
            return username;
        }

        public boolean isCustomer() {
            return JwtUtil.ROLE_CUSTOMER.equals(role);
        }

        public boolean isAdmin() {
            return JwtUtil.ROLE_ADMIN.equals(role);
        }
    }
}
