package com.milktea.order.user.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.JwtUtil;
import com.milktea.order.common.storage.FileStorageService;
import com.milktea.order.common.storage.FileUploadResult;
import com.milktea.order.user.dto.ProfileUpdateRequest;
import com.milktea.order.user.entity.Customer;
import com.milktea.order.user.mapper.CustomerMapper;
import com.milktea.order.user.vo.CustomerLoginVo;
import com.milktea.order.user.vo.CustomerProfileVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

/**
 * 顾客身份与资料服务：按 openid 查/建顾客、签发顾客 JWT、读写昵称与头像。
 *
 * <p>依据排期 T05：openid 落 {@code customer} 表（首次插入）；签发 JWT 的 claims 含
 * customerId、openid、role=customer，有效期 7 天（见 {@link JwtUtil}）。</p>
 *
 * <p><b>身份一律来自 {@code AuthContext}（由 JWT 过滤器解析）</b>，不从请求体接受 customerId
 * ——资料接口若信任入参，任何人都能改别人的昵称与头像。方法签名里的 {@code customerId}
 * 全部由 Controller 从 {@code AuthContext} 取出后传入，本类不做二次鉴权。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    /** 头像对象 key 前缀：与菜单图片分开目录，便于按用途排查与清理。 */
    public static final String AVATAR_KEY_PREFIX = "avatar/";

    /**
     * 合法对象 key 的形状：{@code 路径段 + 扩展名}，路径段只允许字母数字与 {@code / _ -}。
     *
     * <p>这条约束的作用是<b>从结构上堵住「把临时路径当 key 存库」</b>：
     * 微信 {@code chooseAvatar} 返回的是 {@code http://tmp/xxx}、{@code wxfile://xxx} 这类
     * 只在本机当次运行有效的路径，一旦入库，顾客的头像就会永久变成一个打不开的破图
     * （而且换个设备打开还会指向别人的临时文件）。</p>
     *
     * <p><b>为什么不是「允许 A-Za-z0-9/_.- 的白名单」</b>：那种写法会放过 {@code ..} 与
     * 以点开头的路径段（{@code avatar/../../x.png} 也能匹配）。这里的写法把 {@code .} 限定为
     * 「只能出现在最后一次扩展名前」，于是 {@code ..}、以点开头的段、含冒号的 scheme
     * 与绝对路径（以 {@code /} 开头）全部天然不匹配。</p>
     */
    private static final String SAFE_KEY_PATTERN = "^[A-Za-z0-9_][A-Za-z0-9_/\\-]*\\.[A-Za-z0-9]+$";

    private final CustomerMapper customerMapper;
    private final JwtUtil jwtUtil;
    private final FileStorageService fileStorageService;

    /** 依据 openid 登录（首次自动注册），返回顾客令牌与身份。 */
    public CustomerLoginVo loginByOpenid(String openid) {
        Customer customer = customerMapper.selectByOpenid(openid);
        if (customer == null) {
            customer = new Customer();
            customer.setOpenid(openid);
            customer.setCreatedAt(LocalDateTime.now());
            customer.setLastActiveAt(LocalDateTime.now());
            customerMapper.insert(customer);
            log.info("[T05] 新顾客注册 openid={} customerId={}", openid, customer.getId());
        } else {
            customer.setLastActiveAt(LocalDateTime.now());
            customerMapper.updateById(customer);
        }

        String token = jwtUtil.createCustomerToken(customer.getId(), openid);

        CustomerLoginVo vo = new CustomerLoginVo();
        vo.setToken(token);
        vo.setCustomerId(customer.getId());
        vo.setOpenid(openid);
        vo.setNickname(customer.getNickname());
        vo.setAvatarUrl(avatarUrl(customer.getAvatarKey()));
        return vo;
    }

    /**
     * 读顾客资料。
     *
     * @param customerId 当前登录顾客（来自 {@code AuthContext}）
     * @return 资料视图
     * @throws BusinessException 顾客不存在时 404 语义（沿用 PARAM_ERROR，避免为「自己不存在」新增码位）
     */
    public CustomerProfileVo profile(Long customerId) {
        return toProfileVo(requireCustomer(customerId));
    }

    /**
     * 更新昵称。
     *
     * <p>入库前做「去首尾空白 + 纯空白拒绝」：{@code @NotBlank} 已经挡了纯空白，
     * 但 {@code "  小茶  "} 这种带空格的昵称会入库并在界面上显示成带空格的怪样子，
     * 因此在服务层再归一化一次（校验注解管格式，服务层管口径）。</p>
     */
    public CustomerProfileVo updateNickname(Long customerId, ProfileUpdateRequest request) {
        Customer customer = requireCustomer(customerId);
        String nickname = request.getNickname() == null ? "" : request.getNickname().trim();
        if (!StringUtils.hasText(nickname)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "昵称不能为空");
        }
        if (nickname.length() > ProfileUpdateRequest.MAX_NICKNAME_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(),
                    "昵称最长 " + ProfileUpdateRequest.MAX_NICKNAME_LENGTH + " 个字符");
        }
        customer.setNickname(nickname);
        customerMapper.updateById(customer);
        return toProfileVo(customer);
    }

    /**
     * 上传并设置头像：先转存 MinIO，再把对象 key 落库。
     *
     * <p>顺序不能颠倒：先上传成功再改库，上传失败时顾客的旧头像仍然有效
     * （反过来先清空再上传，一旦上传失败就变成「头像没了」）。</p>
     */
    public CustomerProfileVo updateAvatar(Long customerId, MultipartFile file) {
        FileUploadResult uploaded = fileStorageService.put(file, AVATAR_KEY_PREFIX);
        return updateAvatarKey(customerId, uploaded.key());
    }

    /**
     * 以对象 key 设置头像（上传链路的落库步骤）。
     *
     * <p>公开可见是为了让「落库前必须校验 key 形状」这条规则可被单测直接命中
     * ——它守的是本项目一个真实踩过的坑：把微信临时路径当 key 存进库。</p>
     *
     * @param customerId 当前登录顾客
     * @param avatarKey  MinIO 对象 key（形如 {@code avatar/202610/uuid.png}）
     * @throws BusinessException key 形状不合法时 1001
     */
    public CustomerProfileVo updateAvatarKey(Long customerId, String avatarKey) {
        if (!StringUtils.hasText(avatarKey) || !avatarKey.matches(SAFE_KEY_PATTERN)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(),
                    "头像必须先上传成功再保存：不接受本地临时路径或完整 URL");
        }
        Customer customer = requireCustomer(customerId);
        customer.setAvatarKey(avatarKey);
        customerMapper.updateById(customer);
        log.info("[PROFILE] 头像已更新 customerId={} avatarKey={}", customerId, avatarKey);
        return toProfileVo(customer);
    }

    private Customer requireCustomer(Long customerId) {
        if (customerId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED.getCode(), "未登录或令牌过期");
        }
        Customer customer = customerMapper.selectById(customerId);
        if (customer == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED.getCode(), "登录状态已失效，请重新进入小程序");
        }
        return customer;
    }

    private CustomerProfileVo toProfileVo(Customer customer) {
        return new CustomerProfileVo(customer.getId(), customer.getNickname(),
                customer.getAvatarKey(), avatarUrl(customer.getAvatarKey()));
    }

    /** 对象 key → 对外代理路径；无头像时返回 null（前端据此走默认头像）。 */
    private String avatarUrl(String avatarKey) {
        return StringUtils.hasText(avatarKey) ? FileStorageService.FILE_URL_PREFIX + avatarKey : null;
    }
}
