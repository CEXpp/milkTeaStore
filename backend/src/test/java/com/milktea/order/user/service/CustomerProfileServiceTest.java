package com.milktea.order.user.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.jwt.JwtUtil;
import com.milktea.order.common.storage.FileStorageService;
import com.milktea.order.common.storage.FileUploadResult;
import com.milktea.order.user.dto.ProfileUpdateRequest;
import com.milktea.order.user.entity.Customer;
import com.milktea.order.user.mapper.CustomerMapper;
import com.milktea.order.user.vo.CustomerLoginVo;
import com.milktea.order.user.vo.CustomerProfileVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 顾客资料测试：昵称口径 + 头像 key 形状 + 上传失败时的数据安全性。
 *
 * <p>最要紧的一条是<b>头像 key 的形状校验</b>：微信 {@code chooseAvatar} 给的是本机临时路径
 * （{@code http://tmp/…} / {@code wxfile://…}），只当次运行有效。一旦这种串进了库，
 * 顾客的头像就永久成了打不开的破图——而且换台设备打开还可能指向别人的临时文件。
 * 因此这里逐个断言「临时路径与完整 URL 一律拒绝」。</p>
 */
@ExtendWith(MockitoExtension.class)
class CustomerProfileServiceTest {

    private static final Long CUSTOMER_ID = 7L;

    @Mock
    private CustomerMapper customerMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private FileStorageService fileStorageService;

    private CustomerService customerService;

    private Customer customer;

    @BeforeEach
    void setUp() {
        customerService = new CustomerService(customerMapper, jwtUtil, fileStorageService);
        customer = new Customer();
        customer.setId(CUSTOMER_ID);
        customer.setOpenid("openid-1");
    }

    @Test
    @DisplayName("昵称：去首尾空白后入库")
    void trimsNicknameBeforePersisting() {
        when(customerMapper.selectById(CUSTOMER_ID)).thenReturn(customer);

        CustomerProfileVo vo = customerService.updateNickname(CUSTOMER_ID, request("  小茶  "));

        assertEquals("小茶", vo.nickname());
        assertEquals("小茶", customer.getNickname());
        verify(customerMapper).updateById(customer);
    }

    @Test
    @DisplayName("昵称：纯空白与超长一律拒绝，且不写库")
    void rejectsBlankAndTooLongNickname() {
        when(customerMapper.selectById(CUSTOMER_ID)).thenReturn(customer);

        assertEquals(1001, assertThrows(BusinessException.class,
                () -> customerService.updateNickname(CUSTOMER_ID, request("   "))).getCode());
        assertEquals(1001, assertThrows(BusinessException.class,
                () -> customerService.updateNickname(CUSTOMER_ID, request("超".repeat(21)))).getCode());
        verify(customerMapper, never()).updateById(anyCustomer());
    }

    @Test
    @DisplayName("头像 key：拒绝微信临时路径与完整 URL（否则头像永久变破图）")
    void rejectsTemporaryPathAndAbsoluteUrl() {
        // 刻意不桩 selectById：key 形状校验发生在读库之前，
        // 校验失败时连一次数据库访问都不该发生（本用例会因「多余的桩」而失败，正是这个断言）
        String[] rejected = {
                "wxfile://tmp_abc123.png",
                "http://tmp/abc123.png",
                "https://example.com/avatar.png",
                "/absolute/path/avatar.png",
                "../menu/202601/escape.png",
                ""};
        for (String key : rejected) {
            assertEquals(1001, assertThrows(BusinessException.class,
                    () -> customerService.updateAvatarKey(CUSTOMER_ID, key)).getCode(),
                    "应拒绝非对象 key：" + key);
        }
        verify(customerMapper, never()).updateById(anyCustomer());
        assertNull(customer.getAvatarKey());
    }

    @Test
    @DisplayName("头像 key：接受 MinIO 对象 key，并回传可访问的代理路径")
    void acceptsMinioObjectKey() {
        when(customerMapper.selectById(CUSTOMER_ID)).thenReturn(customer);

        CustomerProfileVo vo = customerService.updateAvatarKey(CUSTOMER_ID, "avatar/202610/uuid.png");

        assertEquals("avatar/202610/uuid.png", vo.avatarKey());
        assertEquals("/api/files/avatar/202610/uuid.png", vo.avatarUrl());
        assertEquals("avatar/202610/uuid.png", customer.getAvatarKey());
    }

    @Test
    @DisplayName("上传头像：先转存到 avatar/ 目录，再把 key 落库")
    void uploadStoresToAvatarPrefixThenPersistsKey() {
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(customerMapper.selectById(CUSTOMER_ID)).thenReturn(customer);
        when(fileStorageService.put(file, CustomerService.AVATAR_KEY_PREFIX))
                .thenReturn(new FileUploadResult("avatar/202610/uuid.jpg", "/api/files/avatar/202610/uuid.jpg"));

        CustomerProfileVo vo = customerService.updateAvatar(CUSTOMER_ID, file);

        assertEquals("avatar/202610/uuid.jpg", vo.avatarKey());
        // 头像与菜单图片分目录：便于按用途排查与清理
        verify(fileStorageService).put(file, "avatar/");
    }

    @Test
    @DisplayName("上传失败：不修改库里的头像（旧头像必须仍然有效）")
    void uploadFailureKeepsExistingAvatar() {
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        customer.setAvatarKey("avatar/202609/old.png");
        when(fileStorageService.put(file, CustomerService.AVATAR_KEY_PREFIX))
                .thenThrow(new BusinessException(1011, "图片大小超出限制"));

        assertThrows(BusinessException.class, () -> customerService.updateAvatar(CUSTOMER_ID, file));

        assertEquals("avatar/202609/old.png", customer.getAvatarKey());
        verify(customerMapper, never()).updateById(anyCustomer());
    }

    @Test
    @DisplayName("登录返回带 avatarUrl，前端启动即可展示头像（少一次往返）")
    void loginReturnsAvatarUrl() {
        customer.setNickname("小茶");
        customer.setAvatarKey("avatar/202610/uuid.png");
        when(customerMapper.selectByOpenid("openid-1")).thenReturn(customer);
        when(jwtUtil.createCustomerToken(CUSTOMER_ID, "openid-1")).thenReturn("token-1");

        CustomerLoginVo vo = customerService.loginByOpenid("openid-1");

        assertEquals("token-1", vo.getToken());
        assertEquals("/api/files/avatar/202610/uuid.png", vo.getAvatarUrl());
        assertEquals("小茶", vo.getNickname());
    }

    @Test
    @DisplayName("无头像时 avatarUrl 为 null，前端据此走默认头像")
    void loginWithoutAvatarReturnsNullUrl() {
        when(customerMapper.selectByOpenid("openid-1")).thenReturn(customer);
        when(jwtUtil.createCustomerToken(CUSTOMER_ID, "openid-1")).thenReturn("token-1");

        assertNull(customerService.loginByOpenid("openid-1").getAvatarUrl());
    }

    @Test
    @DisplayName("顾客不存在：给出「重新进入小程序」的引导，而不是 500")
    void missingCustomerIsHandled() {
        when(customerMapper.selectById(CUSTOMER_ID)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> customerService.profile(CUSTOMER_ID));

        assertEquals(401, error.getCode());
    }

    /** 显式收窄到实体重载：MyBatis-Plus 另有 {@code updateById(Collection)}，裸 {@code any()} 会产生歧义。 */
    private Customer anyCustomer() {
        return org.mockito.ArgumentMatchers.any(Customer.class);
    }

    private ProfileUpdateRequest request(String nickname) {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setNickname(nickname);
        return request;
    }
}
