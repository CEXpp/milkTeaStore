package com.milktea.order.order.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.order.mapper.OrderTimelineMapper;
import com.milktea.order.order.vo.OrderTimelineVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 订单全生命周期时间轴（T50，W03）。
 *
 * <p><b>纯只读</b>：不写任何表、不改订单状态、不改统计口径——它只是把订单上已有的六个时间戳
 * 重新组织成顾客能读懂的叙述，并补一个「同渠道同日中位数」的横向参照。</p>
 *
 * <p><b>异常分支的处理</b>：超时关闭单只会有 {@code closed_at}（没有 {@code paid_at}），
 * 作废单只会有 {@code voided_at}；两者互斥、至多一个非空，因此按实际存在的那个追加节点即可，
 * 不会出现「既关闭又作废」的矛盾时间轴。</p>
 *
 * <p><b>耗时口径</b>：每个节点的 {@code durationSeconds} = 该节点时间 − <b>上一个已发生节点</b>的时间。
 * 对超时关闭单，上一个节点是「已下单」（因为它从未支付）；对作废单同理。
 * 这样「什么时候卡住了」一眼可见。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTimelineService {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OrderMapper orderMapper;
    private final OrderTimelineMapper timelineMapper;

    /**
     * 组装某订单的时间轴。
     *
     * @param orderId    订单主键
     * @param customerId 当前登录顾客
     * @throws BusinessException 1004 订单不存在；1005 订单不属于当前顾客
     */
    public OrderTimelineVo timeline(Long orderId, Long customerId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "订单不存在");
        }
        if (!Objects.equals(order.getCustomerId(), customerId)) {
            // 他人订单一律 1005，不泄露订单内容（LLD 9.1）
            throw new BusinessException(ErrorCode.ORDER_NOT_BELONG);
        }
        Median median = medianPrep(order);
        return new OrderTimelineVo(orderId, order.getStatus(), order.getSource(),
                buildNodes(order), prepMinutes(order), median.minutes(), median.samples());
    }

    /** 六个时间戳 → 节点序列（未发生的节点保留，供前端置灰展示流程全貌）。 */
    private List<OrderTimelineVo.Node> buildNodes(Order order) {
        LocalDateTime created = order.getCreatedAt();
        LocalDateTime paid = order.getPaidAt();
        LocalDateTime started = order.getStartedAt();
        LocalDateTime completed = order.getCompletedAt();

        List<OrderTimelineVo.Node> nodes = new ArrayList<>(6);
        nodes.add(node("CREATED", "已下单", created, null));
        nodes.add(node("PAID", "已支付", paid, created));
        nodes.add(node("PREPARING", "开始制作", started, paid));
        nodes.add(node("COMPLETED", "已出餐", completed, started));

        // 异常终态（互斥）：超时关闭单无 paid_at，故其上一节点回落到 created_at
        LocalDateTime terminalBase = paid == null ? created : paid;
        if (order.getClosedAt() != null) {
            nodes.add(node("CLOSED", "已关闭", order.getClosedAt(), terminalBase));
        }
        if (order.getVoidedAt() != null) {
            nodes.add(node("VOIDED", "已作废", order.getVoidedAt(), terminalBase));
        }
        return nodes;
    }

    private OrderTimelineVo.Node node(String key, String label, LocalDateTime time, LocalDateTime previous) {
        Long durationSeconds = (time != null && previous != null)
                ? Math.max(0, Duration.between(previous, time).getSeconds())
                : null;
        return new OrderTimelineVo.Node(key, label,
                time == null ? null : time.format(DATETIME_FMT), durationSeconds, time != null);
    }

    /** 本单制作耗时（分钟，四舍五入）；未开始或未完成为 {@code null}。 */
    private Integer prepMinutes(Order order) {
        if (order.getStartedAt() == null || order.getCompletedAt() == null) {
            return null;
        }
        long seconds = Math.max(0, Duration.between(order.getStartedAt(), order.getCompletedAt()).getSeconds());
        return (int) Math.round(seconds / 60.0);
    }

    /**
     * 同渠道同日制作耗时中位数（分钟）。
     *
     * <p>日期归属按 {@code paid_at}（与 6.5 当日口径一致）；未支付订单（如超时关闭）
     * 退化为按 {@code created_at} 取日——它本来也不会被纳入样本（样本仅含已完成单）。</p>
     */
    private Median medianPrep(Order order) {
        LocalDateTime anchor = order.getPaidAt() == null ? order.getCreatedAt() : order.getPaidAt();
        if (anchor == null) {
            return new Median(null, 0);
        }
        LocalDateTime dayStart = anchor.toLocalDate().atStartOfDay();
        Map<String, Object> row = timelineMapper.selectMedianPrepSeconds(
                order.getSource(), dayStart, dayStart.plusDays(1));
        if (row == null) {
            return new Median(null, 0);
        }
        int samples = (int) asLong(row.get("sampleCount"));
        Object medianSeconds = row.get("medianSeconds");
        if (samples == 0 || medianSeconds == null) {
            return new Median(null, samples);
        }
        return new Median((int) Math.round(asDouble(medianSeconds) / 60.0), samples);
    }

    /** JDBC 聚合值容错取值（同 T34 / T46 口径）。 */
    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(value.toString());
    }

    private static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value == null ? 0d : Double.parseDouble(value.toString());
    }

    /** 中位数结果（分钟 + 样本量）。 */
    private record Median(Integer minutes, int samples) {
    }
}
