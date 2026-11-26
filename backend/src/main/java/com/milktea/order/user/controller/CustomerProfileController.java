package com.milktea.order.user.controller;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.result.R;
import com.milktea.order.user.dto.ProfileUpdateRequest;
import com.milktea.order.user.service.CustomerService;
import com.milktea.order.user.vo.CustomerProfileVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 顾客资料接口（个人中心）。
 *
 * <ul>
 *   <li>{@code GET  /api/customer/profile} —— 读昵称与头像</li>
 *   <li>{@code PUT  /api/customer/profile} —— 改昵称</li>
 *   <li>{@code POST /api/customer/profile/avatar} —— 上传头像（multipart），转存 MinIO 后落 key</li>
 * </ul>
 *
 * <p><b>为什么头像必须走上传而不是让前端直接提交一个地址</b>：微信 {@code chooseAvatar}
 * 只返回本机临时路径，提交上来的任何「地址」都无法长期访问。让前端把文件传上来、
 * 由服务端转存为对象 key，是唯一能保证「换个设备打开还在」的做法。</p>
 *
 * <p>鉴权：路径 {@code /api/customer/**} 已在 {@code JwtAuthenticationFilter} 的顾客 JWT 矩阵内；
 * 顾客身份一律取自 {@code AuthContext}，<b>不从请求参数取</b>（否则可以改别人的资料）。</p>
 */
@RestController
@RequestMapping("/api/customer/profile")
@RequiredArgsConstructor
public class CustomerProfileController {

    private final CustomerService customerService;

    /** GET /api/customer/profile —— 读当前顾客资料。 */
    @GetMapping
    public R<CustomerProfileVo> getProfile() {
        return R.ok(customerService.profile(currentCustomerId()));
    }

    /** PUT /api/customer/profile —— 更新昵称。 */
    @PutMapping
    public R<CustomerProfileVo> updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        return R.ok(customerService.updateNickname(currentCustomerId(), request));
    }

    /**
     * POST /api/customer/profile/avatar —— 上传头像（multipart，字段名 {@code file}）。
     *
     * <p>类型与大小由 {@code MinioFileStorageService} 统一校验（jpg/png/webp、≤2MB），
     * 与菜单图片上传同一套规则，不在这里重复实现。</p>
     */
    @PostMapping("/avatar")
    public R<CustomerProfileVo> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return R.ok(customerService.updateAvatar(currentCustomerId(), file));
    }

    private Long currentCustomerId() {
        AuthContext.Principal principal = AuthContext.get();
        if (principal == null || !principal.isCustomer()) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "仅顾客可访问个人资料");
        }
        return principal.getCustomerId();
    }
}
