package com.milktea.order.preference.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milktea.order.preference.entity.CustomerPreference;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 顾客偏好 Mapper（{@code customer_preference} 表）。
 *
 * <p>写入用一条 {@code INSERT ... ON DUPLICATE KEY UPDATE}（依赖唯一键 {@code uk_customer}），
 * 而不是「先查后插/改」：偏好刷新既可能来自订单事件的异步回调，也可能来自页面进入时的惰性补算，
 * 两者完全可能同时到达同一顾客。交给数据库唯一键去串行化，比在应用层加锁更简单也更可靠——
 * 而且这条语句本身就幂等，重复执行结果一致。</p>
 */
@Mapper
public interface CustomerPreferenceMapper extends BaseMapper<CustomerPreference> {

    /**
     * 覆盖写偏好（不存在则插入，存在则整体覆盖）。
     *
     * <p>用 {@code VALUES(col)} 引用待插入值：MySQL 8.0.20 起标记为 deprecated 但仍可用，
     * 相比「把参数再写一遍」更不容易在改字段时漏改一处。</p>
     *
     * @param customerId          顾客 id（唯一键）
     * @param optionNamesJson     偏好选项 JSON；无认定项传 {@code null}
     * @param favoriteProductName 常点商品名；无认定项传 {@code null}
     * @param sampleOrders        参与聚合的已成交订单数
     * @param source              来源，当前恒为 {@code INFERRED}
     * @param builtAt             本次聚合时间
     * @return 受影响行数（MySQL 对「命中唯一键但值未变」返回 0，不代表失败）
     */
    @Insert("INSERT INTO customer_preference "
            + "(customer_id, option_names_json, favorite_product_name, sample_orders, source, built_at, created_at, updated_at) "
            + "VALUES (#{customerId}, #{optionNamesJson,jdbcType=VARCHAR}, #{favoriteProductName,jdbcType=VARCHAR}, "
            + "#{sampleOrders}, #{source}, #{builtAt}, #{builtAt}, #{builtAt}) "
            + "ON DUPLICATE KEY UPDATE "
            + "option_names_json = VALUES(option_names_json), "
            + "favorite_product_name = VALUES(favorite_product_name), "
            + "sample_orders = VALUES(sample_orders), "
            + "source = VALUES(source), "
            + "built_at = VALUES(built_at), "
            + "updated_at = VALUES(updated_at)")
    int upsert(@Param("customerId") Long customerId,
               @Param("optionNamesJson") String optionNamesJson,
               @Param("favoriteProductName") String favoriteProductName,
               @Param("sampleOrders") int sampleOrders,
               @Param("source") String source,
               @Param("builtAt") LocalDateTime builtAt);
}
