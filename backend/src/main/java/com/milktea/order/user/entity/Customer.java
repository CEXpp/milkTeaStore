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

    private LocalDateTime createdAt;

    private LocalDateTime lastActiveAt;
}
