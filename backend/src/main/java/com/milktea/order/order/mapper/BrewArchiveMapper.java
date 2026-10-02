package com.milktea.order.order.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 每一杯茶的制作档案取数（T59，W21）。
 *
 * <p><b>零新表、零新列</b>：档案要还原「一杯茶的一生」，所需的六个时间戳在 {@code orders}
 * 建表时已全部预留（V1__init_schema.sql:68-91），规格快照 / 单价 / 金额在 {@code order_item}
 * 建表时已锁定（V1__init_schema.sql:93-105），备注与作废原因同在 {@code orders} 上。
 * 因此本 Mapper 只做<b>投影与派生</b>，不写入任何数据。</p>
 *
 * <p><b>「档案一经生成不得修改」由取数路径保证，而非由约定保证</b>（T59 设计纪律）：
 * 档案的每个字段都取自下单瞬间锁定的快照列，且本 Mapper 只暴露 {@code SELECT}——
 * 商品改价、改规格、改名、改加料价改的都是 {@code product} 表，历史档案读到的仍是
 * {@code order_item} 里的旧值。这与 6.3「价格不回溯」同源，从「价格不回溯」扩展到
 * 「整份档案不回溯」（AC-09 复现依据）。</p>
 *
 * <p><b>归属日锚点</b>：{@code COALESCE(paid_at, created_at)}——已支付单按支付完成日归属
 * （与 6.5 当日口径一致），未支付单（待支付 / 超时关闭）无 {@code paid_at}，回落到下单日。
 * 该回落规则与 T50 时间轴中位数的日期锚点一致（OrderTimelineService:114）。</p>
 *
 * <p><b>规格检索</b>：{@code options_snapshot} 是 JSON 列，显式 {@code CAST(... AS CHAR)}
 * 取其文本表示后 {@code LIKE}，可命中 {@code optionName}。此处只用于<b>检索定位</b>
 * （召回哪些杯），展示时仍由服务层反序列化快照逐项呈现——不拿摘要字符串顶替快照（T58 纪律）。</p>
 */
@Mapper
public interface BrewArchiveMapper {

    /**
     * 检索条件（列表与总数共用，保证「页内条数」与「总数」口径一致）。
     *
     * <p>{@code <script>} 内为动态 SQL；所有入参走 {@code #{}} 预编译占位，不做字符串拼接
     * ——含 {@code %} / {@code _} 的检索词只会被当普通字符比对，不会退化成通配符。</p>
     */
    String ARCHIVE_WHERE = """
            <where>
              <if test='start != null'>AND COALESCE(o.paid_at, o.created_at) &gt;= #{start}</if>
              <if test='end != null'>AND COALESCE(o.paid_at, o.created_at) &lt; #{end}</if>
              <if test='productId != null'>AND oi.product_id = #{productId}</if>
              <if test='productName != null and productName != ""'>
                AND oi.product_name LIKE CONCAT('%', #{productName}, '%')
              </if>
              <if test='spec != null and spec != ""'>
                AND CAST(oi.options_snapshot AS CHAR) LIKE CONCAT('%', #{spec}, '%')
              </if>
              <if test='status != null and status != ""'>AND o.status = #{status}</if>
            </where>
            """;

    /** 满足条件的档案总条数（分页总数）。 */
    @Select("""
            SELECT COUNT(*) AS total
            FROM order_item oi JOIN orders o ON o.id = oi.order_id
            """ + ARCHIVE_WHERE)
    Map<String, Object> selectArchiveCount(@Param("start") LocalDateTime start,
                                           @Param("end") LocalDateTime end,
                                           @Param("productId") Long productId,
                                           @Param("productName") String productName,
                                           @Param("spec") String spec,
                                           @Param("status") String status);

    /**
     * 档案列表（分页）。
     *
     * <p>投影列：订单侧的六个时间戳 + 备注 + 作废原因，订单项侧的规格快照与金额快照。
     * {@code prepSeconds} 为派生量（制作耗时秒数），未完成时为 {@code null}。</p>
     *
     * <p>排序：订单锚点时间倒序（同日「先付先做」的自然序），同锚点按 {@code order_item.id}
     * 倒序——保证同一订单的各杯相邻，便于逐杯核对。</p>
     *
     * @param limit  本页条数
     * @param offset 偏移量
     */
    @Select("""
            SELECT oi.id AS orderItemId, oi.order_id AS orderId, o.order_no AS orderNo,
                   o.pickup_code AS pickupCode, o.source AS source, o.status AS status,
                   oi.product_id AS productId, oi.product_name AS productName,
                   oi.quantity AS quantity, oi.base_price AS basePrice,
                   oi.unit_price AS unitPrice, oi.item_amount AS itemAmount,
                   CAST(oi.options_snapshot AS CHAR) AS optionsSnapshot,
                   o.remark AS remark, o.remark_tags AS remarkTags,
                   o.void_reason AS voidReason,
                   o.created_at AS createdAt, o.paid_at AS paidAt, o.started_at AS startedAt,
                   o.completed_at AS completedAt, o.closed_at AS closedAt, o.voided_at AS voidedAt,
                   CASE WHEN o.started_at IS NOT NULL AND o.completed_at IS NOT NULL
                        THEN TIMESTAMPDIFF(SECOND, o.started_at, o.completed_at)
                        ELSE NULL END AS prepSeconds
            FROM order_item oi JOIN orders o ON o.id = oi.order_id
            """ + ARCHIVE_WHERE
            + " ORDER BY COALESCE(o.paid_at, o.created_at) DESC, oi.id DESC"
            + " LIMIT #{limit} OFFSET #{offset}")
    List<Map<String, Object>> selectArchives(@Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end,
                                             @Param("productId") Long productId,
                                             @Param("productName") String productName,
                                             @Param("spec") String spec,
                                             @Param("status") String status,
                                             @Param("limit") int limit,
                                             @Param("offset") long offset);

    /**
     * 按订单项主键取单份档案（「任选一杯可还原完整生命周期」的入口）。
     *
     * <p>{@code oi.id} 是 {@code order_item} 主键，有 PK 索引，走点查而非全表扫描。</p>
     *
     * @return 单行档案，字段与 {@link #selectArchives} 一致；无此杯时返回 {@code null}
     */
    @Select("""
            SELECT oi.id AS orderItemId, oi.order_id AS orderId, o.order_no AS orderNo,
                   o.pickup_code AS pickupCode, o.source AS source, o.status AS status,
                   oi.product_id AS productId, oi.product_name AS productName,
                   oi.quantity AS quantity, oi.base_price AS basePrice,
                   oi.unit_price AS unitPrice, oi.item_amount AS itemAmount,
                   CAST(oi.options_snapshot AS CHAR) AS optionsSnapshot,
                   o.remark AS remark, o.remark_tags AS remarkTags,
                   o.void_reason AS voidReason,
                   o.created_at AS createdAt, o.paid_at AS paidAt, o.started_at AS startedAt,
                   o.completed_at AS completedAt, o.closed_at AS closedAt, o.voided_at AS voidedAt,
                   CASE WHEN o.started_at IS NOT NULL AND o.completed_at IS NOT NULL
                        THEN TIMESTAMPDIFF(SECOND, o.started_at, o.completed_at)
                        ELSE NULL END AS prepSeconds
            FROM order_item oi JOIN orders o ON o.id = oi.order_id
            WHERE oi.id = #{orderItemId}
            """)
    Map<String, Object> selectArchiveByItemId(@Param("orderItemId") Long orderItemId);
}