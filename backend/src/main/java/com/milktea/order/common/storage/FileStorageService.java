package com.milktea.order.common.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储抽象（T08）：只暴露 put/get 两个动作，上层不感知具体存储介质，
 * 便于在 MinIO / 本地磁盘 / 其他对象存储之间替换实现。
 */
public interface FileStorageService {

    /** 对外图片代理路径前缀，与 LLD 3.3 的 imageUrl 约定一致 */
    String FILE_URL_PREFIX = "/api/files/";

    /** 默认对象 key 前缀：菜单图片（T08 既有约定）。 */
    String DEFAULT_KEY_PREFIX = "menu/";

    /**
     * 写入文件（使用默认前缀 {@link #DEFAULT_KEY_PREFIX}）。
     *
     * <p>保留这个方法是为了不动既有调用方（菜单图片上传）；新用途请用
     * {@link #put(MultipartFile, String)} 显式指定前缀——把顾客头像也塞进 {@code menu/}
     * 目录下，日后排查「这个 key 是哪来的」会毫无线索。</p>
     */
    FileUploadResult put(MultipartFile file);

    /**
     * 写入文件，返回对象 key 与对外代理 url。
     *
     * @param file      上传文件（类型与大小由实现校验）
     * @param keyPrefix 对象 key 前缀，如 {@code menu/}、{@code avatar/}
     */
    FileUploadResult put(MultipartFile file, String keyPrefix);

    /**
     * 按键读取文件，返回可流式读出的资源与内容类型。
     */
    StorageObject get(String key);
}
