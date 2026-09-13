package com.milktea.order.common.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * JWT 工具（双体系：顾客 / 商家）。
 * <p>
 * 算法 HS256（HLD 9.1），密钥取自 {@code jwt.secret}（环境变量注入，不入库）。
 * 两类令牌均在 claims 中携带 {@code role}，过滤器据此按路径矩阵分派（见 {@link JwtAuthenticationFilter}）。
 * <p>
 * 约定（对齐 LLD 9.1 / schedule T05）：
 * <ul>
 *   <li>顾客令牌：role=customer，claim 含 customerId、openid，有效期 7 天</li>
 *   <li>商家令牌：role=admin，claim 含 adminId、username，有效期 12 小时</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtUtil {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_CUSTOMER_ID = "customerId";
    public static final String CLAIM_OPENID = "openid";
    public static final String CLAIM_ADMIN_ID = "adminId";
    public static final String CLAIM_USERNAME = "username";

    public static final String ROLE_CUSTOMER = "customer";
    public static final String ROLE_ADMIN = "admin";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.customer-expire-days:7}")
    private int customerExpireDays;

    @Value("${jwt.admin-expire-hours:12}")
    private int adminExpireHours;

    private SecretKey key;

    @PostConstruct
    public void init() {
        // HS256 要求密钥 ≥ 256 bit（32 字节）。配置缺位或不足时直接失败，杜绝静默弱密钥。
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("[T06] jwt.secret 长度不足 32 字节，无法满足 HS256 安全要求");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** 签发顾客令牌（有效期默认 7 天）。 */
    public String createCustomerToken(Long customerId, String openid) {
        return buildToken(ROLE_CUSTOMER, Duration.ofDays(customerExpireDays),
                builder -> builder
                        .subject(openid)
                        .claim(CLAIM_CUSTOMER_ID, customerId)
                        .claim(CLAIM_OPENID, openid));
    }

    /** 签发商家令牌（有效期默认 12 小时）。 */
    public String createAdminToken(Long adminId, String username) {
        return buildToken(ROLE_ADMIN, Duration.ofHours(adminExpireHours),
                builder -> builder
                        .subject(username)
                        .claim(CLAIM_ADMIN_ID, adminId)
                        .claim(CLAIM_USERNAME, username));
    }

    private String buildToken(String role, Duration ttl, java.util.function.Consumer<io.jsonwebtoken.JwtBuilder> customize) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttl.toMillis());
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .claim(CLAIM_ROLE, role)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key, Jwts.SIG.HS256);
        customize.accept(builder);
        return builder.compact();
    }

    /** 解析并校验签名/过期，返回 claims；非法或过期抛出 JwtException。 */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getRole(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }
}
