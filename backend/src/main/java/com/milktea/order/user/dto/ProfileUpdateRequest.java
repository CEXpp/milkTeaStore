package com.milktea.order.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 昵称更新请求（PUT /api/customer/profile）。
 *
 * <p><b>为什么不做 {@code @NotBlank} 之外的复杂校验</b>：微信昵称本身允许 emoji 与中英文混排，
 * 过度的字符集限制会把正常昵称挡在门外。这里只卡「非空」与「长度上限」两条，
 * 长度上限按 {@code customer.nickname} 的列宽留足余量（列 64，这里取 20 个字符，
 * 因为再长的昵称在个人中心的头部一行根本显示不下）。</p>
 */
@Data
public class ProfileUpdateRequest {

    /** 昵称上限：超过它在个人中心头部一行显示不下，且会撑破卡片布局。 */
    public static final int MAX_NICKNAME_LENGTH = 20;

    @NotBlank(message = "昵称不能为空")
    @Size(max = MAX_NICKNAME_LENGTH, message = "昵称最长 " + MAX_NICKNAME_LENGTH + " 个字符")
    private String nickname;
}
