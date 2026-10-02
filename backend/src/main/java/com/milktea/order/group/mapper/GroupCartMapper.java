package com.milktea.order.group.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milktea.order.group.entity.GroupCart;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 拼单池数据访问（T63）。
 *
 * <p><b>并发控制全部收敛在本接口</b>：业务层一律走带 {@code version} 的条件更新，
 * 不用「先查后写」——那样两个请求会同时读到同一个 version 并双双写入，造成互相覆盖。
 * {@code affected == 0} 即代表「你读到的那份已经不是最新的了」，调用方须重试或报错。</p>
 */
@Mapper
public interface GroupCartMapper extends BaseMapper<GroupCart> {

    /**
     * 提交/更新某人的选品（乐观锁写入）。
     *
     * <p>CAS 条件含 {@code version} 与 {@code status = 'OPEN'}：既防并发覆盖，
     * 也顺带实现「冻结后不可加入」——冻结后 status 不再是 OPEN，条件更新自然影响 0 行，
     * 无需在业务层再查一次状态（少一次竞态窗口）。</p>
     *
     * @param expectedVersion 本次读取到的版本号
     * @return 影响行数；1 成功，0 表示版本过期或状态已非 OPEN
     */
    @Update("""
            UPDATE group_cart
               SET shared_draft = #{draft},
                   member_ids  = #{memberIds},
                   version     = version + 1,
                   updated_at  = NOW()
             WHERE id = #{id} AND version = #{expectedVersion} AND status = 'OPEN'
            """)
    int casUpdateDraft(@Param("id") Long id,
                       @Param("expectedVersion") Long expectedVersion,
                       @Param("draft") String draft,
                       @Param("memberIds") String memberIds);

    /**
     * 冻结（仅发起人 + 仅 OPEN + 未过期）。
     *
     * <p>{@code expires_at > NOW()} 写进 CAS 条件而非业务层判断：到点与并发冻结竞态时，
     * 由数据库裁决更可靠——业务层「先判断时间再更新」中间存在缝隙。</p>
     *
     * @return 影响行数；0 表示已被冻结 / 不是发起人 / 已过期
     */
    @Update("""
            UPDATE group_cart
               SET status    = 'FROZEN',
                   frozen_at = NOW(),
                   version   = version + 1,
                   updated_at = NOW()
             WHERE id = #{id} AND version = #{expectedVersion}
               AND status = 'OPEN' AND owner_id = #{ownerId} AND expires_at > NOW()
            """)
    int casFreeze(@Param("id") Long id,
                  @Param("expectedVersion") Long expectedVersion,
                  @Param("ownerId") Long ownerId);

    /**
     * 冻结 → 支付中（CAS 目标转移，防重复提交）。
     *
     * <p>单独一个中间态的意义：支付窗口内若被重复点击，第二次的状态已不是 FROZEN，
     * 影响 0 行——从而<b>不会生成第二张订单</b>。</p>
     */
    @Update("""
            UPDATE group_cart
               SET status    = 'CONVERTING',
                   version   = version + 1,
                   updated_at = NOW()
             WHERE id = #{id} AND status = 'FROZEN' AND owner_id = #{ownerId}
            """)
    int casStartConvert(@Param("id") Long id, @Param("ownerId") Long ownerId);

    /**
     * 支付中 → 已转正式单。
     *
     * @param orderId 生成的订单 id
     */
    @Update("""
            UPDATE group_cart
               SET status     = 'CONVERTED',
                   order_id   = #{orderId},
                   updated_at = NOW()
             WHERE id = #{id} AND status = 'CONVERTING'
            """)
    int markConverted(@Param("id") Long id, @Param("orderId") Long orderId);

    /**
     * 任意阶段 → 已失效（到期未支付 / 发起人放弃）。
     *
     * <p>刻意<b>不限定来源状态</b>：已 CONVERTED 的不能被误改（那会让已生成的订单失去拼单关联），
     * 故单独一个条件版本供定时任务用。</p>
     */
    @Update("""
            UPDATE group_cart
               SET status     = 'EXPIRED',
                   updated_at = NOW()
             WHERE id = #{id} AND status IN ('OPEN', 'FROZEN')
            """)
    int markExpired(@Param("id") Long id);

    /** 到期仍未转单的拼单（定时任务批量置失效，走 idx_status_expires）。 */
    @Select("""
            SELECT id FROM group_cart
             WHERE status IN ('OPEN', 'FROZEN') AND expires_at <= NOW()
             LIMIT #{limit}
            """)
    List<Long> selectExpirable(@Param("limit") int limit);
}