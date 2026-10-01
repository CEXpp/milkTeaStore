package com.milktea.order.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milktea.order.notify.entity.RemindTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 稍后提醒任务 Mapper（T47）。
 *
 * <p>查询与更新全部走 MyBatis-Plus 的条件构造器（与 4.3 超时关单同风格），
 * 无需自定义 SQL——本表没有聚合与联表需求。</p>
 */
@Mapper
public interface RemindTaskMapper extends BaseMapper<RemindTask> {
}
