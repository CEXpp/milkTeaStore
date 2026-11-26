package com.milktea.order.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 顾客（静默登录标识，绑定微信 openid）。
 * <p>首次微信登录时由 {@code CustomerService} 写入，openid 唯一。</p>
 */
@Data
@TableName("customer")
public class Customer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String openid;

    private String nickname;

    /**
     * 微信头像对象 key（V9 加列，MinIO 对象 key，<b>不是</b>完整 URL）。
     *
     * <p>必须存 key：{@code chooseAvatar} 返回的是本机临时路径（{@code http://tmp/…}、
     * {@code wxfile://…}），只在当次运行有效——存进库里等于给顾客留了一张永远打不开的图。
     * 对外展示统一拼 {@code /api/files/{key}} 代理路径（与商品图同一约定）。</p>
     */
    private String avatarKey;

    private LocalDateTime createdAt;

    private LocalDateTime lastActiveAt;
}
