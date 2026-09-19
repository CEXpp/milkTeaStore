package com.milktea.order.product.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.common.storage.FileStorageService;
import com.milktea.order.common.storage.FileUploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 商家端图片上传（T08）：{@code POST /api/admin/upload}，multipart 字段名 {@code file}。
 * <p>
 * 路径属 {@code /api/admin/**} 鉴权矩阵，需商家 JWT；成功返回 {@code {key, url}}。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminUploadController {

    private final FileStorageService fileStorageService;

    @PostMapping("/upload")
    public R<FileUploadResult> upload(@RequestParam("file") MultipartFile file) {
        return R.ok(fileStorageService.put(file));
    }
}
