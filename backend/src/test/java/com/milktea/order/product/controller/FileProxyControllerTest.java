package com.milktea.order.product.controller;

import com.milktea.order.common.storage.FileStorageService;
import com.milktea.order.common.storage.StorageObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T08 路由验证：确认 {@code /api/files/{key}} 能接受含 {@code /} 的多段 key
 * （即 {@code {*key}} 的 PathPattern 剩余路径捕获生效），并校验响应头。
 */
@DisplayName("FileProxyController 路由与响应头测试（T08）")
class FileProxyControllerTest {

    private final FileStorageService fileStorageService = mock(FileStorageService.class);

    private final MockMvc mockMvc =
            MockMvcBuilders.standaloneSetup(new FileProxyController(fileStorageService)).build();

    @Test
    @DisplayName("多段 key 原样透传，并带 Content-Type 与 1 天缓存")
    void stream_multiSegmentKey() throws Exception {
        byte[] body = "fake-image".getBytes();
        when(fileStorageService.get(anyString()))
                .thenReturn(new StorageObject(new ByteArrayResource(body), "image/png"));

        mockMvc.perform(get("/api/files/menu/202609/abc.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "public, max-age=86400"))
                .andExpect(content().bytes(body));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(fileStorageService).get(captor.capture());
        assertEquals("menu/202609/abc.png", captor.getValue(), "路由通配捕获的 key 应与请求子路径一致");
    }

    @Test
    @DisplayName("Content-Type 缺失时回退为 application/octet-stream")
    void stream_defaultContentType() throws Exception {
        when(fileStorageService.get(anyString()))
                .thenReturn(new StorageObject(new ByteArrayResource(new byte[0]), null));

        mockMvc.perform(get("/api/files/menu/202609/raw"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"));
    }
}
