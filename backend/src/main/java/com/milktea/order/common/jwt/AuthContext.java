package com.milktea.order.common.jwt;

import com.milktea.order.common.jwt.JwtUtil;

import java.lang.ScopedValue;

/**
 * 当前请求的身份上下文（基于 {@link ScopedValue}，JDK 24 定稿的标准特性；本项目 Java 25 直接可用，无需 --enable-preview）。
 * <p>
 * 由 {@link JwtAuthenticationFilter} 在鉴权成功后通过 {@link #runWith(Principal, Runnable)} 绑定，
 * 作用域覆盖整段请求处理链（Controller / Service），作用域结束自动失效，<b>无需手动清理</b>。
 * 下游 Controller / Service 通过 {@link #get()} 读取身份，无需重复解析令牌。
 * <p>
 * 相较 {@code ThreadLocal} 的优势：不可变、自动清理（无内存泄漏 / 串号风险）、对虚拟线程友好。
 * 注意：作用域内的值仅沿调用栈向下传递，不会自动跨入新开线程 / {@code @Async} / {@code CompletableFuture}，
 * 若异步分支需读取身份，应在异步任务内重新 {@link #runWith} 绑定。
 */
public final class AuthContext {

    private static final ScopedValue<Principal> CURRENT = ScopedValue.newInstance();

    private AuthContext() {
    }

    /**
     * 在作用域内执行 {@code action}；整段调用链（含 Controller / Service）均可经 {@link #get()} 读取身份。
     * 作用域随 {@code action} 执行结束自动解除，无需 {@code clear()}。
     *
     * @param principal 当前请求身份
     * @param action    请求处理逻辑（通常包裹 {@code filterChain.doFilter}）
     */
    public static void runWith(Principal principal, Runnable action) {
        ScopedValue.where(CURRENT, principal).run(action);

    }

    /**
     * 读取当前身份。若当前不在任何请求作用域内（如白名单路径、作用域外）返回 {@code null}，
     * 与旧 {@code ThreadLocal} 未绑定时的语义保持一致。
     */
    public static Principal get() {
        // 注意：不能用 CURRENT.orElse(null)——JDK 的 ScopedValue.orElse 对入参做 requireNonNull，
        // 传 null 无论是否已绑定都会抛 NPE（JDK 25 实测）；isBound/get 组合才是「未绑定返回 null」的等价语义。
        return CURRENT.isBound() ? CURRENT.get() : null;
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
