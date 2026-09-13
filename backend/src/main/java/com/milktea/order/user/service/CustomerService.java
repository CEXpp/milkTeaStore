package com.milktea.order.user.service;

import com.milktea.order.common.jwt.JwtUtil;
import com.milktea.order.user.entity.Customer;
import com.milktea.order.user.mapper.CustomerMapper;
import com.milktea.order.user.vo.CustomerLoginVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 顾客身份服务：按 openid 查/建顾客，并签发顾客 JWT。
 * <p>
 * 依据排期 T05：openid 落 {@code customer} 表（首次插入）；签发 JWT 的 claims 含
 * customerId、openid、role=customer，有效期 7 天（见 {@link JwtUtil}）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerMapper customerMapper;
    private final JwtUtil jwtUtil;

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
        return vo;
    }
}
