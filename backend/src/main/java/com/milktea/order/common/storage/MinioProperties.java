package com.milktea.order.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 连接配置（T08），绑定 {@code minio.*}。
 * <p>
 * 注意：{@code endpoint} 必须指向 S3 对象存储 API 端口（9000）；9001 是 Web 控制台端口，
 * SDK 无法用于对象读写。
 */
@ConfigurationProperties(prefix = "minio")
public record MinioProperties(String endpoint, String accessKey, String secretKey, String bucket) {
}
