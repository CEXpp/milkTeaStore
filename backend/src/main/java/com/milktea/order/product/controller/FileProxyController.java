package com.milktea.order.product.controller;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.storage.FileStorageService;
import com.milktea.order.common.storage.StorageObject;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * 图片代理流（公开，T08）：{@code GET /api/files/{key}} 由后端从对象存储拉取并写回响应体，
 * 带 {@code Content-Type} 与 1 天缓存头。该前缀在 {@code JwtAuthenticationFilter} 中为公开白名单。
 * <p>
 * {@code {*key}} 是 PathPattern 的「捕获剩余路径」语法，使 key 中的 {@code /} 得以保留
 * （如 {@code menu/202609/uuid.png}）；其捕获值带前导斜杠，故此处先归一化再交给存储层，
 * 避免正常 key 被存储层的「绝对路径」防护误判。
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileProxyController {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    /** 缓存 1 天 */
    private static final String CACHE_CONTROL = "public, max-age=86400";

    private final FileStorageService fileStorageService;

    @GetMapping("/{*key}")
    public void stream(@PathVariable String key, HttpServletResponse response) {
        String objectKey = key.startsWith("/") ? key.substring(1) : key;
        StorageObject object = fileStorageService.get(objectKey);
        response.setContentType(object.contentType() != null ? object.contentType() : DEFAULT_CONTENT_TYPE);
        response.setHeader("Cache-Control", CACHE_CONTROL);
        try (InputStream in = object.resource().getInputStream()) {
            in.transferTo(response.getOutputStream());
            response.getOutputStream().flush();
        } catch (IOException e) {
            log.error("[T08] 代理流写出失败 key={}", objectKey, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
