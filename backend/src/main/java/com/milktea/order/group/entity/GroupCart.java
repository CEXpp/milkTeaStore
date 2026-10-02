package com.milktea.order.group.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 拼单共享草稿池（group_cart，LLD 11.2）。
 *
 * <p><b>复用 ai_session 的「草稿 + expires_at」范式</b>（T63 任务卡）：AI 草稿单与拼单池
 * 是同一种东西——都是临时聚合、都可能过期、都要最终转成正式订单。故本表结构与
 * {@code ai_session} 刻意同构（uuid / expires_at / created_at / updated_at），
 * 同一套「草稿 → 冻结 → 转正式单」生命周期服务两个场景。</p>
 *
 * <p><b>{@code sharedDraft} 的形状</b>：{@code [{customerId, tag, items:[{productId,optionIds,quantity}]}]}
 * ——每个参与者一个元素，{@code tag} 是给店员看的成员标识（如「003 王工」，T64 用）。
 * <b>刻意存 productId 而非商品名</b>：拼单要在支付那一刻由后端重算金额（验收项
 * 「金额由后端重算」），存 id 才能直接喂给计价引擎；存名字会引入「改名后算不出价」的问题。</p>
 *
 * <p><b>隐私纪律</b>（任务卡）：{@code tag} 是参与者自填的展示名，只在本单与订单项上流转，
 * <b>不写入 customer 表、不进任何画像与推荐计算</b>。</p>
 */
@Data
@TableName("group_cart")
public class GroupCart {

    /** 收单中：可加入、可改自己的选品。 */
    public static final String STATUS_OPEN = "OPEN";
    /** 已冻结：发起人已确认，禁止加入与改单，等待其支付。 */
    public static final String STATUS_FROZEN = "FROZEN";
    /** 支付中：正在生成正式单，防重复提交。 */
    public static final String STATUS_CONVERTING = "CONVERTING";
    /** 已转正式单：{@link #orderId} 指向生成的订单。 */
    public static final String STATUS_CONVERTED = "CONVERTED";
    /** 已失效：到期未支付或发起人放弃，不生成任何订单。 */
    public static final String STATUS_EXPIRED = "EXPIRED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 拼单池标识（对外暴露的唯一定位符，参与者凭它加入）。 */
    private String groupUuid;

    /** 发起人 customer_id（唯一有资格冻结与支付的人）。 */
    private Long ownerId;

    /** 共享草稿 JSON，形状见类注释。 */
    private String sharedDraft;

    /** 参与方 customer_id 列表 JSON。 */
    private String memberIds;

    /**
     * 乐观锁版本（T63 并发核心）。
     *
     * <p>每次写入 {@code SET version = version + 1 WHERE id = ? AND version = ?}：
     * 更新数为 0 即说明有人抢先改过，本次放弃并让调用方重试。这让「十人同时改」不会互相覆盖
     * ——而不是靠给整表加锁把所有人串行化。</p>
     */
    private Long version;

    /** 截止时间（默认发起后 30 分钟）；过期即冻结不了、也转不了正式单。 */
    private LocalDateTime expiresAt;

    /** 阶段状态（T63/V7 加列，取值见 STATUS_* 常量）。 */
    private String status;

    /** 发起人确认冻结的时间。 */
    private LocalDateTime frozenAt;

    /** 转正式单后指向 orders.id。 */
    private Long orderId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}