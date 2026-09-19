package com.milktea.order.common.storage;

/**
 * 上传结果（T08）：{@code key} 为对象 key，{@code url} 为对外代理路径 {@code /api/files/{key}}。
 */
public record FileUploadResult(String key, String url) {
}
