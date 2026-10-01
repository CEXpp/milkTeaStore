package com.milktea.order.notify.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 微信订阅消息额度（T44）：一次性订阅次数的计数与原子扣减。
 *
 * <p><b>为什么扣减必须是一条 UPDATE 而不是「先查后改」</b>：额度是并发资源——同一顾客可能
 * 因快速连点 / 多端操作触发并发下发，微信对此会返回 {@code errcode=43108}
 * （「并发下发消息给同一个粉丝」）。把判断与扣减合并成
 * {@code UPDATE ... WHERE remaining > 0}，由数据库的行锁保证「只有一次能拿到额度」，
 * 既避免超发，也顺带挡住并发下发。</p>
 */
@Mapper
public interface WxSubscribeQuotaMapper {

    /**
     * 登记授权额度（幂等累加）：不存在则插入，存在则累加。
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE} 让插入与累加在一条语句内完成，
     * 避免「查无则插、查有则改」的竞态。</p>
     *
     * @param delta 本次新增的可用次数（通常为 1，一次授权 = 一次下发机会）
     */
    @Insert("INSERT INTO wx_subscribe_quota (customer_id, template_key, remaining, created_at, updated_at) "
            + "VALUES (#{customerId}, #{templateKey}, #{delta}, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE remaining = remaining + #{delta}, updated_at = #{now}")
    int grant(@Param("customerId") Long customerId,
              @Param("templateKey") String templateKey,
              @Param("delta") int delta,
              @Param("now") LocalDateTime now);

    /**
     * 原子占用一次下发额度。
     *
     * @return 1 = 占用成功（可下发）；0 = 无可用额度（用户未授权 / 已用完）→ 调用方直接跳过
     */
    @Update("UPDATE wx_subscribe_quota SET remaining = remaining - 1, updated_at = #{now} "
            + "WHERE customer_id = #{customerId} AND template_key = #{templateKey} AND remaining > 0")
    int consume(@Param("customerId") Long customerId,
                @Param("templateKey") String templateKey,
                @Param("now") LocalDateTime now);

    /**
     * 退还一次额度：仅用于「已占用但下发未成功」（网络异常 / access_token 取不到 / 微信返回非 0）。
     *
     * <p>不退还会让用户「授权了却永远收不到」——因为额度被一次失败的下发消耗掉了。
     * 微信侧 {@code 43101}（次数已用完）不会走这里：那种情况额度本就为 0，压根没占用成功。</p>
     */
    @Update("UPDATE wx_subscribe_quota SET remaining = remaining + 1, updated_at = #{now} "
            + "WHERE customer_id = #{customerId} AND template_key = #{templateKey}")
    int refund(@Param("customerId") Long customerId,
               @Param("templateKey") String templateKey,
               @Param("now") LocalDateTime now);

    /** 顾客当前剩余可下发总次数（供前端判断「是否还需要再次请求授权」）。 */
    @Select("SELECT COALESCE(SUM(remaining), 0) FROM wx_subscribe_quota WHERE customer_id = #{customerId}")
    int remainingTotal(@Param("customerId") Long customerId);
}
