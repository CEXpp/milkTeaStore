package com.milktea.order.common.storage;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * MinIO 实现（T08）：对象 key 形如 {@code menu/yyyyMM/uuid.ext}，桶可保持私有，
 * 对外统一经 {@code /api/files/{key}} 代理访问。
 * <p>
 * 约束：仅允许 jpg / png / webp，单文件 ≤ 2MB（与 {@code spring.servlet.multipart} 双重校验）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioFileStorageService implements FileStorageService {

    /** 单文件上限 2MB */
    private static final long MAX_SIZE = 2L * 1024 * 1024;
    /** 类型白名单：Content-Type → 落盘扩展名（image/jpg 为部分客户端发送的兼容写法） */
    private static final Map<String, String> ALLOWED_CONTENT_TYPES = Map.of(
            "image/jpeg", ".jpg",
            "image/jpg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp");
    /** 对象 key 前缀，与 T08 约定的 menu/yyyyMM/uuid.ext 一致 */
    private static final String KEY_PREFIX = "menu/";
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @Override
    public FileUploadResult put(MultipartFile file) {
        String extension = validate(file);
        String key = buildKey(extension);
        ensureBucket();
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.bucket())
                    .object(key)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        } catch (Exception e) {
            log.error("[T08] putObject 失败 bucket={} key={}", minioProperties.bucket(), key, e);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
        return new FileUploadResult(key, FILE_URL_PREFIX + key);
    }

    @Override
    public StorageObject get(String key) {
        rejectUnsafeKey(key);
        try {
            GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(minioProperties.bucket())
                    .object(key)
                    .build());
            // 响应流由调用方关闭：包装为 Resource，避免向外暴露 MinIO 类型
            return new StorageObject(new InputStreamResource(response), response.headers().get("Content-Type"));
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
            }
            log.error("[T08] getObject 失败 bucket={} key={} code={}",
                    minioProperties.bucket(), key, e.errorResponse().code(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        } catch (Exception e) {
            log.error("[T08] getObject 失败 bucket={} key={}", minioProperties.bucket(), key, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /** 校验类型与大小；通过则返回落盘扩展名。 */
    private String validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "上传文件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        String contentType = file.getContentType();
        String extension = contentType == null ? null : ALLOWED_CONTENT_TYPES.get(contentType.toLowerCase());
        if (extension == null) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        return extension;
    }

    private String buildKey(String extension) {
        return KEY_PREFIX + YearMonth.now(ZONE).format(MONTH_FORMATTER) + "/" + UUID.randomUUID() + extension;
    }

    /** 桶不存在则创建；桶已存在或权限不足时交由后续 putObject 暴露真实错误。 */
    private void ensureBucket() {
        try {
            String bucket = minioProperties.bucket();
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[T08] 自动创建存储桶 bucket={}", bucket);
            }
        } catch (Exception e) {
            log.warn("[T08] 存储桶检查失败 bucket={}：{}", minioProperties.bucket(), e.getMessage());
        }
    }

    /** key 防穿越：拒绝空 key、相对路径片段与绝对路径前缀。 */
    private void rejectUnsafeKey(String key) {
        if (key == null || key.isBlank() || key.contains("..") || key.startsWith("/")) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
    }
}
