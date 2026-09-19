package com.milktea.order.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milktea.order.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单主表访问（orders）。
 *
 * <p>支付落库走 {@code BaseMapper.update(entity, LambdaUpdateWrapper)} 的条件更新：
 * 单条 UPDATE 内完成「状态仍为 PENDING_PAYMENT」的判定与字段写入，天然防并发双击重复支付。</p>
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
