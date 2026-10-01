package com.milktea.order.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.order.vo.ArrivalEtaVo;
import com.milktea.order.order.vo.ArrivalVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 到店握手（T49，W19「诚实的不确定性 + 到店握手」）。
 *
 * <h2>这是一条信号，不是一个状态</h2>
 * <p>「我已到店」只写 {@code orders.arrived_at} 一个时间戳：<b>不推进 4.1 状态机</b>、
 * <b>不触发任何状态迁移</b>、<b>不影响 6.1 超时关单</b>、<b>不进 6.5 统计</b>。
 * 看板据此给卡片打一个「已到店」标记，店长可以据此优先处理——但也完全可以选择无视
 * （任务卡：「提示而非强制，店长可无视」；验收项：「不强制改排序」，
 * 因此 {@code AdminOrderService.board()} 的 ORDER BY 一个字都没动）。</p>
 *
 * <h2>与 W02「我将到」的分工</h2>
 * <p>{@code orders.eta_minutes}（V3 加列）承载「我将到：预计 X 分钟后到店」，
 * 本服务的 {@code arrived_at} 承载「我已到店」。两者共同构成双向到店信号体系：
 * 一个说「快到了」，一个说「到了」。二者都不改变订单状态机。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArrivalService {

    /**
     * 可申报到店的状态 = 队列内（已支付、尚未出餐）。
     *
     * <p>其余状态一律拒绝：待支付单还没开始排队；已完成单说明已经取走了；
     * 已关闭 / 已作废单再去柜台也没有意义。前端只在进行中订单页展示该按钮，
     * 此处是服务端的第二道防线。</p>
     */
    private static final List<String> ARRIVABLE_STATUSES =
            List.of(OrderStatus.PAID.name(), OrderStatus.PREPARING.name());

    /**
     * 允许申报的预计到店时长（分钟，T51）。
     *
     * <p>前端只给 3 / 5 / 10 三个快捷项，服务端做二次校验——否则任意数值都能塞进
     * {@code eta_minutes}，看板的建议排序就失去了业务含义。</p>
     */
    private static final Set<Integer> ALLOWED_ETA_MINUTES = Set.of(3, 5, 10);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OrderMapper orderMapper;

    /**
     * 申报「我已到店」。
     *
     * <p><b>幂等</b>：重复申报返回首次申报的时间，不覆盖、不报错——
     * 顾客连点、或从多端进入重复提交都不应产生冲突提示。</p>
     *
     * @param orderId    订单主键
     * @param customerId 当前登录顾客
     * @throws BusinessException 1004 订单不存在 / 状态不可申报；1005 订单不属于当前顾客
     */
    @Transactional(rollbackFor = Exception.class)
    public ArrivalVo arrive(Long orderId, Long customerId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "订单不存在");
        }
        if (!Objects.equals(order.getCustomerId(), customerId)) {
            // 他人订单一律 1005，不泄露订单内容（LLD 9.1）
            throw new BusinessException(ErrorCode.ORDER_NOT_BELONG);
        }
        if (order.getArrivedAt() != null) {
            return new ArrivalVo(orderId, order.getArrivedAt().format(FMT), true);
        }
        if (!ARRIVABLE_STATUSES.contains(order.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "当前订单状态无需申报到店");
        }

        LocalDateTime now = LocalDateTime.now();
        // 条件更新（WHERE arrived_at IS NULL）：并发双击只有一次写入成功，另一次走幂等分支
        int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, orderId)
                .isNull(Order::getArrivedAt)
                .set(Order::getArrivedAt, now)
                .set(Order::getUpdatedAt, now));
        if (updated == 0) {
            Order latest = orderMapper.selectById(orderId);
            LocalDateTime arrivedAt = latest == null ? null : latest.getArrivedAt();
            return new ArrivalVo(orderId, arrivedAt == null ? null : arrivedAt.format(FMT), true);
        }
        log.info("[T49] 顾客申报到店 orderId={} customerId={}", orderId, customerId);
        return new ArrivalVo(orderId, now.format(FMT), true);
    }

    /**
     * 申报 / 修改 / 撤销「我到店还需 X 分钟」（T51，W02「我将到」）。
     *
     * <p>与 {@link #arrive} 一样只写一个<b>信号</b>：不推进状态机、不影响超时关单、不进统计。
     * 但它与「我已到店」的差别在于——<b>它会参与看板的建议制作顺序</b>（到达近的优先），
     * 这正是 T51 的验收要求；而「我已到店」不参与排序（T49 验收「不强制改排序」）。</p>
     *
     * <p><b>可改可撤</b>：重复申报直接覆盖；传 {@code null} 撤销，看板随即回到「先付先做」
     * 的原始顺序（验收「不申报时与现状一致」）。</p>
     *
     * @param orderId    订单主键
     * @param customerId 当前登录顾客
     * @param etaMinutes 3 / 5 / 10；{@code null} 表示撤销
     * @throws BusinessException 1001 时长不在允许集合；1004 订单不存在 / 状态不可申报；1005 非本人订单
     */
    @Transactional(rollbackFor = Exception.class)
    public ArrivalEtaVo updateEta(Long orderId, Long customerId, Integer etaMinutes) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "订单不存在");
        }
        if (!Objects.equals(order.getCustomerId(), customerId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_BELONG);
        }
        if (!ARRIVABLE_STATUSES.contains(order.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "当前订单状态无需申报到店时间");
        }
        if (etaMinutes != null && !ALLOWED_ETA_MINUTES.contains(etaMinutes)) {
            // 服务端二次校验：前端只给 3/5/10 三个快捷项，此处防绕过
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "预计到店时间仅支持 3 / 5 / 10 分钟");
        }

        // set 无条件赋值：etaMinutes 为 null 时即写库为 NULL（撤销语义）
        orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, orderId)
                .set(Order::getEtaMinutes, etaMinutes)
                .set(Order::getUpdatedAt, LocalDateTime.now()));
        log.info("[T51] 到店时间申报 orderId={} customerId={} etaMinutes={}", orderId, customerId, etaMinutes);
        return new ArrivalEtaVo(orderId, etaMinutes);
    }
}
