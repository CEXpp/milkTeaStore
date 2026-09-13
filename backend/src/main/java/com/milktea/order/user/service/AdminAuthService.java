package com.milktea.order.user.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.JwtUtil;
import com.milktea.order.user.entity.AdminUser;
import com.milktea.order.user.mapper.AdminUserMapper;
import com.milktea.order.user.vo.AdminLoginVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 商家登录（单账号 + BCrypt 校验 + 失败锁定）。
 * <p>
 * 依据 HLD 9.1：密码 BCrypt 哈希；连续失败 5 次锁定 10 分钟（内存计数，练手期单实例足够）。
 * 锁定与「用户名或密码错误」统一口径，避免账号枚举。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminUserMapper adminUserMapper;
    private final JwtUtil jwtUtil;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private static final int MAX_FAIL = 5;
    private static final long LOCK_MILLIS = 10L * 60 * 1000;

    private final ConcurrentHashMap<String, FailRecord> failMap = new ConcurrentHashMap<>();

    public AdminLoginVo login(String username, String password) {
        FailRecord rec = failMap.computeIfAbsent(username, k -> new FailRecord());
        synchronized (rec) {
            if (System.currentTimeMillis() < rec.lockUntil) {
                long remainMin = (rec.lockUntil - System.currentTimeMillis() + 59_999L) / 60_000L;
                throw new BusinessException(ErrorCode.FORBIDDEN.getCode(),
                        "登录失败次数过多，账号已锁定，请约 " + remainMin + " 分钟后再试");
            }
        }

        AdminUser admin = adminUserMapper.selectByUsername(username);
        // 统一口径，避免账号枚举
        if (admin == null || !passwordEncoder.matches(password, admin.getPasswordHash())) {
            registerFail(username);
            throw new BusinessException(ErrorCode.UNAUTHORIZED.getCode(), "用户名或密码错误");
        }

        // 成功：清除失败计数并签发 12 小时 admin JWT
        failMap.remove(username);
        String token = jwtUtil.createAdminToken(admin.getId(), admin.getUsername());

        AdminLoginVo vo = new AdminLoginVo();
        vo.setToken(token);
        vo.setAdminId(admin.getId());
        vo.setUsername(admin.getUsername());
        vo.setNickname(admin.getNickname());
        return vo;
    }

    private void registerFail(String username) {
        FailRecord rec = failMap.computeIfAbsent(username, k -> new FailRecord());
        synchronized (rec) {
            rec.count++;
            if (rec.count >= MAX_FAIL) {
                rec.lockUntil = System.currentTimeMillis() + LOCK_MILLIS;
                rec.count = 0;
            }
        }
    }

    /** 每个用户名的失败计数与锁定截止时间。 */
    private static final class FailRecord {
        int count = 0;
        long lockUntil = 0;
    }
}
