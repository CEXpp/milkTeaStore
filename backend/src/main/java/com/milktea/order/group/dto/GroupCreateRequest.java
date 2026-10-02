package com.milktea.order.group.dto;

import jakarta.validation.constraints.Size;

/**
 * 创建拼单请求（T63）。
 *
 * @param minutes 收单时长（分钟）。空则用服务端默认 30，上限 120——
 *                 拼单是「一起喝」，不是「预约明天」；放太久成员会散。
 */
public record GroupCreateRequest(
        @Size(min = 1, message = "收单时长需为 1~120 分钟")
        Integer minutes) {
}