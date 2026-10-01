package com.milktea.order.queue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.queue.config.QueueProperties;
import com.milktea.order.queue.mapper.QueueMapper;
import com.milktea.order.queue.vo.QueueEstimateVo;
import com.milktea.order.queue.vo.QueueSnapshotVo;
import com.milktea.order.shop.entity.ShopConfig;
import com.milktea.order.shop.mapper.ShopConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 队列预估内核（T46，LLD 11.6）——T47 / T48 / T49 / T69 的统一取数入口。
 *
 * <p><b>算法（LLD 11.6 施工口径，不得擅自变更）</b></p>
 * <pre>
 *   eta_minutes = ceil( jobs * t_unit / max(s, 1) )
 *   err         = ceil( k * sqrt(jobs) * t_unit + buffer )
 *   eta_low     = max(0, eta_minutes - err)
 *   eta_high    = eta_minutes + err
 * </pre>
 *
 * <p><b>jobs 的口径裁定</b>：LLD 11.6 写作「订单数」，而任务卡写「队列杯数（主饮品件数）」并注明
 * 「6.5 口径不新增指标」。本实现取<b>任务卡 + SRS 6.5「售出杯数 = 主饮品件数」</b>的口径，
 * 即 {@code jobs} = 队列内相应范围的主饮品件数（杯），{@code t_unit} 相应为<b>单杯</b>基准耗时——
 * 公式形状与 LLD 完全一致，仅把「一单 = 一个工位任务」细化为「一杯 = 一个工位任务」，
 * 一单一杯时两者等价；多杯单不再被低估。加料 / 规格是 {@code options_snapshot} 内的选项，
 * 不产生额外 {@code order_item} 行，天然不另计（同 6.5）。</p>
 *
 * <p><b>jobs 的取值</b>：</p>
 * <ul>
 *   <li>全店快照 / 下单前预估 = 全店待制作 + 制作中的件数（等价于「此刻新加入队列」）；</li>
 *   <li>指定订单 = 排在<b>该单之前</b>的队列件数（同刻支付按 {@code id} 升序，与商家看板排序一致），
 *       因此该单自身的制作量不计入自己的等待时间。</li>
 * </ul>
 *
 * <p><b>只读</b>：全部为 SELECT 聚合，不写 {@code orders}、不触发状态迁移（LLD 11.6）。
 * 不加缓存：单店量级下直接聚合，队列一变预估立刻跟得上（同 T34 口径思路）。</p>
 *
 * <p><b>参数来源</b>：{@code application.yml} 的 {@code queue.*} 给默认值，
 * {@code shop_config}（KV 表）可按运行期覆盖单杯耗时 / 制作单元数 / 波动系数
 * （任务卡「单杯基准耗时 shop_config 可配」；LLD 11.6 亦注明 {@code k} 可由 T69 校准）；
 * 安全垫 {@code buffer} 按 LLD 定位为「常量」，不开放运行期覆盖。</p>
 */
@Slf4j
@Service
public class QueueEstimateService {

    /** shop_config 覆盖键：单杯基准耗时（分钟）。 */
    public static final String KEY_PREP_MINUTES = "queue.prep-minutes";
    /** shop_config 覆盖键：在岗制作单元数。 */
    public static final String KEY_STAFF = "queue.staff";
    /** shop_config 覆盖键：波动系数 k（T69 爆单预测可校准）。 */
    public static final String KEY_VOLATILITY = "queue.volatility";

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 队列内状态（与 {@link QueueMapper#IN_QUEUE} 同源；两者任一变更须同步）。 */
    private static final List<String> QUEUED_STATUSES =
            List.of(OrderStatus.PAID.name(), OrderStatus.PREPARING.name());

    private final QueueMapper queueMapper;
    private final OrderMapper orderMapper;
    private final ShopConfigMapper shopConfigMapper;
    private final QueueProperties properties;
    private final Clock clock;

    public QueueEstimateService(QueueMapper queueMapper,
                                OrderMapper orderMapper,
                                ShopConfigMapper shopConfigMapper,
                                QueueProperties properties,
                                @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.queueMapper = queueMapper;
        this.orderMapper = orderMapper;
        this.shopConfigMapper = shopConfigMapper;
        this.properties = properties;
        this.clock = Clock.system(ZoneId.of(timeZone));
    }

    /**
     * 全店队列快照（T48 动态接单节奏 / T69 爆单预测取数）。
     *
     * @return 队列分态计数 + 全店预估 + 生效参数 + 计算时间戳
     */
    public QueueSnapshotVo snapshot() {
        Map<String, Object> row = queueMapper.selectQueueTotals();
        long waitingOrders = asLong(row.get("waitingOrders"));
        long preparingOrders = asLong(row.get("preparingOrders"));
        long waitingCups = asLong(row.get("waitingCups"));
        long preparingCups = asLong(row.get("preparingCups"));

        QueueSettings settings = settings();
        EtaBand band = band(waitingCups + preparingCups, settings);
        return new QueueSnapshotVo(waitingOrders, preparingOrders, waitingCups, preparingCups,
                waitingCups + preparingCups, band.eta(), band.low(), band.high(),
                settings.unitMinutes(), settings.staff(), now());
    }

    /**
     * 下单前预估（T47 结算页「下单前预期管理」）：未指定订单，工作量取当前全店队列。
     *
     * @return {@code orderId} 与 {@code position} 均为 {@code null} 的预估
     */
    public QueueEstimateVo estimateForNewOrder() {
        QueueSettings settings = settings();
        long cups = totalCups();
        EtaBand band = band(cups, settings);
        return new QueueEstimateVo(null, null, cups, band.eta(), band.low(), band.high(), now());
    }

    /**
     * 指定订单的等待预估（T49 进行中订单页）。
     *
     * <p>订单不在队列（待支付 / 已完成 / 超时关闭 / 已作废，或缺少 {@code paid_at}）时，
     * {@code position} 归 0 表示「不在队列」，工作量与预估退化为「此刻加入队列」的口径。</p>
     *
     * @param orderId    订单主键
     * @param customerId 当前登录顾客（JWT 身份）
     * @return 队列序号 + 前序工作量 + 预估区间 + 计算时间戳
     * @throws BusinessException 1004 订单不存在；1005 订单不属于当前顾客
     */
    public QueueEstimateVo estimateForOrder(Long orderId, Long customerId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "订单不存在");
        }
        if (!Objects.equals(order.getCustomerId(), customerId)) {
            // 他人订单一律 1005，不泄露订单内容（LLD 9.1）
            throw new BusinessException(ErrorCode.ORDER_NOT_BELONG);
        }

        QueueSettings settings = settings();
        boolean queued = order.getPaidAt() != null && QUEUED_STATUSES.contains(order.getStatus());
        if (!queued) {
            long cups = totalCups();
            EtaBand band = band(cups, settings);
            return new QueueEstimateVo(orderId, 0L, cups, band.eta(), band.low(), band.high(), now());
        }

        Map<String, Object> row = queueMapper.selectAheadOfWork(order.getPaidAt(), orderId);
        long aheadOrders = asLong(row.get("aheadOrders"));
        long aheadCups = asLong(row.get("aheadCups"));
        EtaBand band = band(aheadCups, settings);
        return new QueueEstimateVo(orderId, aheadOrders + 1, aheadCups,
                band.eta(), band.low(), band.high(), now());
    }

    /** 全店队列工作量（杯）：待制作 + 制作中的主饮品件数。 */
    private long totalCups() {
        Map<String, Object> row = queueMapper.selectQueueTotals();
        return asLong(row.get("waitingCups")) + asLong(row.get("preparingCups"));
    }

    /**
     * LLD 11.6 公式：预估与误差区间。
     *
     * <p>空队列（{@code jobs = 0}）时 {@code eta = 0}、{@code err = ceil(buffer)}，
     * 即「0 分钟（0 ~ buffer）」——按施工图逐字实现，不额外特判（T49 验收「下限 ≤ 上限且非负」成立）。</p>
     */
    private EtaBand band(long jobs, QueueSettings settings) {
        int eta = (int) Math.ceil(jobs * settings.unitMinutes() / Math.max(settings.staff(), 1));
        int err = (int) Math.ceil(settings.volatility() * Math.sqrt(jobs) * settings.unitMinutes()
                + settings.bufferMinutes());
        return new EtaBand(eta, Math.max(0, eta - err), eta + err);
    }

    /** 生效参数 = application.yml 默认 + shop_config 覆盖（非法覆盖值回落默认并告警）。 */
    private QueueSettings settings() {
        Map<String, String> overrides = loadOverrides();
        double unitMinutes = positive(overrides.get(KEY_PREP_MINUTES), properties.prepMinutes(), KEY_PREP_MINUTES);
        int staff = atLeastOne(overrides.get(KEY_STAFF), properties.staff(), KEY_STAFF);
        double volatility = nonNegative(overrides.get(KEY_VOLATILITY), properties.volatility(), KEY_VOLATILITY);
        return new QueueSettings(unitMinutes, staff, volatility, properties.bufferMinutes());
    }

    /** 一次 IN 查询取全部覆盖键，避免逐个按键查询。 */
    private Map<String, String> loadOverrides() {
        List<ShopConfig> rows = shopConfigMapper.selectList(new LambdaQueryWrapper<ShopConfig>()
                .in(ShopConfig::getConfigKey, List.of(KEY_PREP_MINUTES, KEY_STAFF, KEY_VOLATILITY)));
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, String> overrides = new HashMap<>(rows.size());
        for (ShopConfig row : rows) {
            overrides.put(row.getConfigKey(), row.getConfigValue());
        }
        return overrides;
    }

    private double positive(String raw, double fallback, String key) {
        Double parsed = parseDouble(raw, key);
        if (parsed == null) {
            return fallback;
        }
        if (parsed <= 0) {
            log.warn("[T46] shop_config {} 须为正数，回落默认 {}：{}", key, fallback, raw);
            return fallback;
        }
        return parsed;
    }

    private double nonNegative(String raw, double fallback, String key) {
        Double parsed = parseDouble(raw, key);
        if (parsed == null) {
            return fallback;
        }
        if (parsed < 0) {
            log.warn("[T46] shop_config {} 须非负，回落默认 {}：{}", key, fallback, raw);
            return fallback;
        }
        return parsed;
    }

    private int atLeastOne(String raw, int fallback, String key) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 1) {
                log.warn("[T46] shop_config {} 须 ≥1，回落默认 {}：{}", key, fallback, raw);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException e) {
            log.warn("[T46] shop_config {} 非整数，回落默认 {}：{}", key, fallback, raw);
            return fallback;
        }
    }

    /** 解析 shop_config 的数值覆盖；空值 / 非数字返回 {@code null}（由调用方回落默认）。 */
    private Double parseDouble(String raw, String key) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("[T46] shop_config {} 非数值，回落默认：{}", key, raw);
            return null;
        }
    }

    private String now() {
        return LocalDateTime.now(clock).format(DATETIME_FMT);
    }

    /** JDBC 聚合值容错取值（同 T34 统计服务口径）。 */
    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(value.toString());
    }

    /** 生效的队列预估参数。 */
    private record QueueSettings(double unitMinutes, int staff, double volatility, double bufferMinutes) {
    }

    /** 预估结果三元组（预估 / 下限 / 上限，分钟）。 */
    private record EtaBand(int eta, int low, int high) {
    }
}
