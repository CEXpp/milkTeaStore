package com.milktea.order.common.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储抽象（T08）：只暴露 put/get 两个动作，上层不感知具体存储介质，
 * 便于在 MinIO / 本地磁盘 / 其他对象存储之间替换实现。
 */
public interface FileStorageService {

    /** 对外图片代理路径前缀，与 LLD 3.3 的 imageUrl 约定一致 */
    String FILE_URL_PREFIX = "/api/files/";

    /**
     * 写入文件，返回对象 key 与对外代理 url。
     */
    FileUploadResult put(MultipartFile file);

    /**
     * 按键读取文件，返回可流式读出的资源与内容类型。
     */
    StorageObject get(String key);
}
