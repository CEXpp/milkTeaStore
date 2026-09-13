package com.milktea.order.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milktea.order.user.entity.Customer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 顾客 Mapper（被 {@code @MapperScan("com.milktea.order.**.mapper")} 覆盖）。
 */
@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {

    @Select("SELECT id, openid, nickname, created_at, last_active_at " +
            "FROM customer WHERE openid = #{openid}")
    Customer selectByOpenid(String openid);
}
