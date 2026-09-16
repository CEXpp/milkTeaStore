package com.milktea.order.common.storage;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T08 单元测试：以 Mockito 模拟 MinioClient，覆盖类型/大小校验、key 生成、桶自建与读取分支。
 * 不加载 Spring 上下文，故不依赖数据库与真实 MinIO。
 */
@DisplayName("MinioFileStorageService 单元测试（T08）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MinioFileStorageServiceTest {

    private static final MinioProperties PROPS =
            new MinioProperties("http://115.190.216.167:9000", "root", "12345678", "dev-bucket");

    @Mock
    private MinioClient minioClient;

    private MinioFileStorageService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new MinioFileStorageService(minioClient, PROPS);
        when(minioClient.bucketExists(any())).thenReturn(true);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));
    }

    @Test
    @DisplayName("上传：非图片类型被拒绝")
    void put_rejectNonImage() {
        MultipartFile file = mockFile("note.txt", "text/plain", 10L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.put(file));
        assertEquals(ErrorCode.FILE_TYPE_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("上传：gif 不在白名单，被拒绝")
    void put_rejectGif() {
        MultipartFile file = mockFile("anim.gif", "image/gif", 1024L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.put(file));
        assertEquals(ErrorCode.FILE_TYPE_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("上传：超过 2MB 被拒绝")
    void put_rejectOversize() {
        MultipartFile file = mockFile("big.png", "image/png", 2L * 1024 * 1024 + 1);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.put(file));
        assertEquals(ErrorCode.FILE_TOO_LARGE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("上传：jpg 成功，key 形如 menu/yyyyMM/uuid.jpg 并返回代理 url")
    void put_successJpg() throws Exception {
        MultipartFile file = mockFile("logo.jpg", "image/jpeg", 2048L);

        FileUploadResult result = service.put(file);

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        assertEquals("dev-bucket", captor.getValue().bucket());
        assertEquals("image/jpeg", captor.getValue().contentType());

        String month = YearMonth.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = result.key();
        assertTrue(key.startsWith("menu/" + month + "/"), "key 前缀应为 menu/yyyyMM/，实际=" + key);
        assertTrue(key.endsWith(".jpg"), "jpg 应落盘为 .jpg，实际=" + key);
        assertEquals(key, captor.getValue().object());
        assertEquals(FileStorageService.FILE_URL_PREFIX + key, result.url());
        assertEquals("/api/files/" + key, result.url());
    }

    @Test
    @DisplayName("上传：webp 成功并落盘为 .webp")
    void put_successWebp() {
        MultipartFile file = mockFile("pic.webp", "image/webp", 1024L);
        FileUploadResult result = service.put(file);
        assertTrue(result.key().endsWith(".webp"), "webp 应落盘为 .webp，实际=" + result.key());
    }

    @Test
    @DisplayName("上传：桶不存在时自动创建")
    void put_createsBucketWhenAbsent() throws Exception {
        when(minioClient.bucketExists(any())).thenReturn(false);
        service.put(mockFile("logo.png", "image/png", 1024L));
        verify(minioClient).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    @DisplayName("读取：NoSuchKey 映射为 FILE_NOT_FOUND")
    void get_noSuchKey() throws Exception {
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(errorResponse.code()).thenReturn("NoSuchKey");
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(errorResponse);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(ex);

        BusinessException thrown =
                assertThrows(BusinessException.class, () -> service.get("menu/202609/missing.png"));
        assertEquals(ErrorCode.FILE_NOT_FOUND.getCode(), thrown.getCode());
    }

    @Test
    @DisplayName("读取：命中对象时返回资源与 Content-Type")
    void get_success() throws Exception {
        GetObjectResponse response = mock(GetObjectResponse.class);
        when(response.headers()).thenReturn(Headers.of("Content-Type", "image/png"));
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(response);

        StorageObject object = service.get("menu/202609/abc.png");

        assertNotNull(object.resource());
        assertEquals("image/png", object.contentType());
        ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(minioClient).getObject(captor.capture());
        assertEquals("dev-bucket", captor.getValue().bucket());
        assertEquals("menu/202609/abc.png", captor.getValue().object());
    }

    @Test
    @DisplayName("读取：危险 key（.. 或绝对路径）被拒绝")
    void get_unsafeKey() {
        BusinessException dotdot =
                assertThrows(BusinessException.class, () -> service.get("../secret"));
        assertEquals(ErrorCode.FILE_NOT_FOUND.getCode(), dotdot.getCode());

        BusinessException absolute =
                assertThrows(BusinessException.class, () -> service.get("/etc/passwd"));
        assertEquals(ErrorCode.FILE_NOT_FOUND.getCode(), absolute.getCode());
    }

    private MultipartFile mockFile(String name, String contentType, long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getContentType()).thenReturn(contentType);
        when(file.getSize()).thenReturn(size);
        when(file.isEmpty()).thenReturn(false);
        InputStream stream = new ByteArrayInputStream(new byte[16]);
        try {
            when(file.getInputStream()).thenReturn(stream);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return file;
    }
}
