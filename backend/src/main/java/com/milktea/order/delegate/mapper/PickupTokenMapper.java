package com.milktea.order.delegate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milktea.order.delegate.entity.PickupToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 取餐委托令牌数据访问（T65）。
 *
 * <p><b>四条约束全部收敛在本接口</b>，靠 {@code WHERE} 条件而非业务层判断来保证——
 * 业务层「先查后判再改」存在竞态窗口：查的时候未撤销、改的时候已被撤销。</p>
 */
@Mapper
public interface PickupTokenMapper extends BaseMapper<PickupToken> {

    /**
     * 核销：把「有效」一次性变成「已用」。
     *
     * <p><b>四个条件缺一不可</b>，这是四条约束的落地点：</p>
     * <ul>
     *   <li>{@code used_at IS NULL} —— 一次性；</li>
     *   <li>{@code revoked = 0} —— 可撤销（已撤销的不许再核销）；</li>
     *   <li>{@code expires_at > NOW()} —— 限时；</li>
     *   <li>外层已校验订单处于可取餐状态（PAID / PREPARING / COMPLETED）。</li>
     * </ul>
     *
     * <p>用条件更新而非「查出来再判断」：两个代取人同时点开时只有一个能核销成功，
     * 另一个拿到 0 行——这正是「一次性」要的效果。</p>
     *
     * @return 影响行数；1 = 核销成功，0 = 已被用过 / 已撤销 / 已过期
     */
    @Update("""
            UPDATE pickup_token
               SET used_at = NOW(),
                   proxy_tag = #{proxyTag}
             WHERE token = #{token}
               AND used_at IS NULL
               AND revoked = 0
               AND expires_at > NOW()
            """)
    int consume(@Param("token") String token, @Param("proxyTag") String proxyTag);

    /**
     * 撤销本人订单下的全部有效令牌。
     *
     * <p>用 {@code EXISTS} 子查询把「订单归属」这一层约束交给数据库，
     * 避免业务层先查订单再撤销的两次往返，也避免归属判断与撤销之间被篡改。</p>
     */
    @Update("""
            UPDATE pickup_token t
               SET t.revoked = 1
             WHERE t.order_id = #{orderId}
               AND t.used_at IS NULL
               AND t.revoked = 0
               AND EXISTS (SELECT 1 FROM orders o
                            WHERE o.id = t.order_id AND o.customer_id = #{ownerId})
            """)
    int revokeOwned(@Param("orderId") Long orderId, @Param("ownerId") Long ownerId);

    /**
     * 该订单当前是否还有有效（未用、未撤销、未过期）令牌。
     *
     * <p>供「重复签发」判断：原主已经发过一个还在有效期内的令牌时，
     * 再次签发会作废前一个——避免同一订单存在多个并行有效的授权。</p>
     */
    @Update("""
            UPDATE pickup_token
               SET revoked = 1
             WHERE order_id = #{orderId}
               AND used_at IS NULL
               AND revoked = 0
            """)
    int revokeAllForOrder(@Param("orderId") Long orderId);
}