package com.milktea.order.common.storage;

import org.springframework.core.io.Resource;

/**
 * 读取结果（T08）：{@code resource} 供调用方流式写出并关闭，{@code contentType} 用于响应头。
 * <p>
 * 以 Spring {@link Resource} 承载数据流，避免抽象层暴露具体存储 SDK 的类型。
 */
public record StorageObject(Resource resource, String contentType) {
}
