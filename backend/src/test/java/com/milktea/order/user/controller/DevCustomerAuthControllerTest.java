package com.milktea.order.user.controller;

import com.milktea.order.user.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DevCustomerAuthControllerTest {

    @Test
    void onlyRegistersWhenExplicitlyEnabledInDevProfile() {
        assertFalse(isRegistered("dev", false));
        assertFalse(isRegistered("prod", true));
        assertTrue(isRegistered("dev", true));
    }

    private boolean isRegistered(String profile, boolean enabled) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profile);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", Map.of("app.dev-login-enabled", Boolean.toString(enabled))));
            context.registerBean(CustomerService.class, () -> mock(CustomerService.class));
            context.register(DevCustomerAuthController.class);
            context.refresh();
            return context.getBeansOfType(DevCustomerAuthController.class).size() == 1;
        }
    }
}
