package com.milktea.order.delegate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 取餐委托令牌（pickup_token，LLD 11.3；T65 落地，W15）。
 *
 * <p><b>本表是 SRS 9.2「不可跨 openid 读取」的唯一例外</b>——代取人凭令牌能看到取餐码。
 * 代价是这个例外必须有四条约定补回来，全部落在这张表上：</p>
 * <ol>
 *   <li><b>限时</b>：{@code expires_at} 到点即失效，不需任何人来关；</li>
 *   <li><b>一次性</b>：{@code used_at} 核销后即失效，杜绝令牌在群里流转；</li>
 *   <li><b>可撤销</b>：{@code revoked} 让原主随时收回权限——<b>原主始终是权限终点</b>；</li>
 *   <li><b>不可转赠</b>：代取人侧没有任何签发接口，令牌无法二次扩散。</li>
 * </ol>
 *
 * <p>{@code proxyTag} 是代取人的自填标识（如「李工」），仅用于让店员知道谁来取，
 * <b>不写入 customer 表、不参与任何画像</b>。</p>
 */
@Data
@TableName("pickup_token")
public class PickupToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 绑定订单：代取人凭令牌能看到的范围就锁定在这一单。 */
    private Long orderId;

    /**
     * 令牌本体：{@code SecureRandom} 32 字节的 Base64URL 串（43 字符）。
     *
     * <p>刻意用<b>密码学随机</b>而非自增 id 或时间戳拼接——它是唯一的授权凭据，
     * 可预测等于任何人可代取。</p>
     */
    private String token;

    /** 代取人标识（昵称/手机尾号），不可转赠锚点；代取人自填，可为空。 */
    private String proxyTag;

    /** 限时：到期即失效（随时间自然过期，不需要任何人来关）。 */
    private LocalDateTime expiresAt;

    /** 一次性：核销时间；非空即已用过。 */
    private LocalDateTime usedAt;

    /** 可撤销标记：原主随时收回。 */
    private Boolean revoked;

    private LocalDateTime createdAt;
}