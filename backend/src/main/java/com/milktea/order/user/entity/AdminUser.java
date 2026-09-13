package com.milktea.order.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家账号（单账号，BCrypt 密码哈希）。
 * <p>由 Flyway V2 种子数据写入，凭据来自 application 配置 {@code admin.initial-*}。</p>
 */
@Data
@TableName("admin_user")
public class AdminUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String nickname;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
