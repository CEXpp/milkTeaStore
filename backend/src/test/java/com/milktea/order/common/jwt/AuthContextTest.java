package com.milktea.order.common.jwt;

import com.milktea.order.common.jwt.AuthContext.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuthContext 身份作用域回归测试（T06 引入，T12 修复）。
 *
 * <p>背景：原实现 {@code CURRENT.orElse(null)} 在 JDK 25 上恒抛 NPE（ScopedValue.orElse 对入参
 * requireNonNull），导致所有读取身份的接口 500；T12 pay 端点是首个消费者，修复为
 * {@code isBound() ? get() : null} 并在此钉住语义。</p>
 */
@DisplayName("AuthContext 身份作用域（T06/T12）")
class AuthContextTest {

    @Test
    @DisplayName("未绑定作用域：读取返回 null（不抛异常）")
    void returnsNullWhenUnbound() {
        assertNull(AuthContext.get());
    }

    @Test
    @DisplayName("顾客作用域内：读取到顾客身份；作用域结束自动失效")
    void readsCustomerPrincipalInsideScope() {
        AuthContext.runWith(Principal.customer(101L, "openid-101"), () -> {
            Principal principal = AuthContext.get();
            assertNotNull(principal, "作用域内应能读到身份");
            assertEquals(101L, principal.getCustomerId());
            assertEquals("openid-101", principal.getOpenid());
            assertTrue(principal.isCustomer());
        });

        assertNull(AuthContext.get(), "作用域结束后应自动失效");
    }

    @Test
    @DisplayName("商家作用域内：读取到商家身份")
    void readsAdminPrincipalInsideScope() {
        AuthContext.runWith(Principal.admin(1L, "admin"), () -> {
            Principal principal = AuthContext.get();
            assertNotNull(principal);
            assertEquals(1L, principal.getAdminId());
            assertEquals("admin", principal.getUsername());
            assertTrue(principal.isAdmin());
        });
    }
}
