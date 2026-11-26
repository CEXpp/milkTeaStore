package com.milktea.order.preference.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 顾客口味偏好（{@code customer_preference}，V9 新增）。
 *
 * <p><b>数据来源只有一条</b>：已成交订单（PAID / PREPARING / COMPLETED）的规格快照。
 * 不解析对话内容、不接受任何显式写入——这是用户拍板的隐私口径：
 * 「只从历史订单隐式学习，纯被动学习，无隐私争议」。</p>
 *
 * <p><b>为什么存中文选项名</b>：个人中心的「口味档案」要直接展示给顾客看，
 * 而决策层编码进 state 时再由菜单映射表把中文名换成下标。
 * 若这里存下标，菜单改版后下标会错位到别的选项上——那是静默显示错口味。</p>
 *
 * <p>{@code sampleOrders} 在样本不足时也照实记录（配合空的 {@code optionNamesJson}），
 * 用于把「顾客没有历史」与「还没算过」区分开，避免每次对话都重新聚合。</p>
 */
@Data
@TableName("customer_preference")
public class CustomerPreference {

    /** 来源：仅由已成交订单隐式聚合（本方案唯一的写入来源）。 */
    public static final String SOURCE_INFERRED = "INFERRED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long customerId;

    /**
     * {@code [{"groupCode":"SWEETNESS","optionNames":["半糖"]}]}，仅含达到频次阈值的项；
     * 无认定项时为 null。
     *
     * <p>用<b>数组</b>而不是「组 code → 选项名」的映射，是为了与项目既有的
     * {@code DraftItem[]} / {@code OptionSnapshot[]} 保持同一种读写方式（数组 + 具体记录类型）。</p>
     */
    private String optionNamesJson;

    /** 常点商品中文名（按商品 id 聚合后取当前名称）；无认定项时为 null。 */
    private String favoriteProductName;

    /** 参与聚合的已成交订单数（阈值分母，也用于「基于 N 单」的展示）。 */
    private Integer sampleOrders;

    private String source;

    /** 最近一次聚合时间（惰性补算的过期基准）。 */
    private LocalDateTime builtAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
