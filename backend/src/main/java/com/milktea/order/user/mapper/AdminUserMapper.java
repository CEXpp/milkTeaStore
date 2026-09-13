package com.milktea.order.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milktea.order.user.entity.AdminUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 商家账号 Mapper（被 {@code @MapperScan("com.milktea.order.**.mapper")} 覆盖）。
 */
@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {

    @Select("SELECT id, username, password_hash, nickname, created_at, updated_at " +
            "FROM admin_user WHERE username = #{username}")
    AdminUser selectByUsername(String username);
}
