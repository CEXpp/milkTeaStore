package com.milktea.order.order.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.result.PageResult;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.BrewArchiveMapper;
import com.milktea.order.order.vo.BrewArchiveVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 每一杯茶的制作档案服务（T59，W21）。
 *
 * <p><b>纯只读</b>：不写任何表、不改订单状态、不动统计口径。档案 = {@code orders} 的六个时间戳
 * + {@code order_item} 的规格与金额快照，两侧字段都是下单瞬间锁定的，故本服务只需做
 * 「投影 + 派生 + 格式化」，没有任何写入路径可走。</p>
 *
 * <p><b>检索维度</b>（任务卡要求「按日期 / 商品 / 规格检索」）：
 * 日期传 {@code date}（单日）或 {@code from} + {@code to}（闭开区间）；商品可按 id 精确或
 * 按名称模糊；规格按 {@code options_snapshot} 文本匹配；另可按订单状态过滤。</p>
 *
 * <p><b>为什么档案不含顾客身份</b>：T59 边界写明「只做自查用的只读追溯，不做监管对接」。
 * 监管场景要的是「谁在什么时间买了什么」，而自查场景要的是「这一杯是怎么做出来的」。
 * 掺入顾客信息只会带来泄露面而无收益，故档案不返回 customerId / 昵称 / openid。</p>
 */
@Slf4j
@Service
public class BrewArchiveService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final BrewArchiveMapper archiveMapper;

    /** 自然日基准：与 T11 流水号、T34 统计同源（{@code app.time-zone}）。 */
    private final Clock clock;

    public BrewArchiveService(BrewArchiveMapper archiveMapper,
                              @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.archiveMapper = archiveMapper;
        this.clock = Clock.system(ZoneId.of(timeZone));
    }

    /**
     * 检索制作档案（分页）。
     *
     * <p>日期三选一：{@code date}（单日）、{@code from} + {@code to}（区间，右开）、全空（不限日期）。
     * 传 {@code date} 时以它为准；{@code from}/{@code to} 只允许单独给出其一，按开区间处理
     * ——「从某天起」用 {@code from}，「到某天止」用 {@code to} 的次日零点是前端易错点，
     * 故本服务要求区间两端成对给出，避免出现「只有 to」的静默错查。</p>
     *
     * @param date        单日 yyyy-MM-dd
     * @param from        起始日（含）yyyy-MM-dd
     * @param to          结束日（不含）yyyy-MM-dd
     * @param productId   商品 id，精确
     * @param productName 商品名模糊（匹配<b>快照名</b>，非当前商品名）
     * @param spec        规格模糊（匹配快照中的规格组名 / 选项名 / 加料名）
     * @param status      订单状态过滤，非法值抛 1001
     * @param page        页码，从 1 起
     * @param size每页条数，默认 20，上限 100
     * @throws BusinessException 1001 日期格式非法 / 区间不成对 / 状态非法
     */
    public PageResult<BrewArchiveVo> archives(String date, String from, String to,
                                              Long productId, String productName, String spec,
                                              String status, int page, int size) {
        LocalDateTime start;
        LocalDateTime end;
        if (StringUtils.hasText(date)) {
            LocalDate day = parseDate(date, "date");
            start = day.atStartOfDay();
            end = day.plusDays(1).atStartOfDay();
        } else {
            boolean hasFrom = StringUtils.hasText(from);
            boolean hasTo = StringUtils.hasText(to);
            if (hasFrom != hasTo) {
                throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "from 与 to 需成对给出");
            }
            if (hasFrom) {
                LocalDate fromDay = parseDate(from, "from");
                LocalDate toDay = parseDate(to, "to");
                if (!toDay.isAfter(fromDay)) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "to 需晚于 from");
                }
                start = fromDay.atStartOfDay();
                end = toDay.atStartOfDay();
            } else {
                start = null;
                end = null;
            }
        }

        String normalizedStatus = normalizeStatus(status);
        int safePage = Math.max(page, 1);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String trimmedName = StringUtils.hasText(productName) ? productName.trim() : null;
        String trimmedSpec = StringUtils.hasText(spec) ? spec.trim() : null;

        Map<String, Object> countRow = archiveMapper.selectArchiveCount(start, end, productId,
                trimmedName, trimmedSpec, normalizedStatus);
        long total = countRow == null ? 0L : asLong(countRow.get("total"));
        if (total == 0) {
            return PageResult.of(List.of(), 0L, safePage, safeSize);
        }

        List<Map<String, Object>> rows = archiveMapper.selectArchives(start, end, productId,
                trimmedName, trimmedSpec, normalizedStatus, safeSize, (long) (safePage - 1) * safeSize);
        List<BrewArchiveVo> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            list.add(toVo(row));
        }
        return PageResult.of(list, total, safePage, safeSize);
    }

    /**
     * 取得单杯的完整档案（「任选一杯可还原完整生命周期」的入口）。
     *
     * <p>走 {@code oi.id} 主键点查，不扫表——档案是逐杯自查的交互，一次只问一杯。</p>
     *
     * @param orderItemId 订单项主键
     * @throws BusinessException 1004 档案不存在
     */
    public BrewArchiveVo archive(Long orderItemId) {
        Map<String, Object> row = archiveMapper.selectArchiveByItemId(orderItemId);
        if (row == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "档案不存在");
        }
        return toVo(row);
    }

    private BrewArchiveVo toVo(Map<String, Object> row) {
        List<BrewArchiveVo.OptionLine> options = optionLines(text(row.get("optionsSnapshot")));
        LocalDateTime createdAt = asTime(row.get("createdAt"));
        LocalDateTime paidAt = asTime(row.get("paidAt"));
        LocalDateTime anchor = paidAt == null ? createdAt : paidAt;
        return new BrewArchiveVo(
                asLong(row.get("orderItemId")),
                asLong(row.get("orderId")),
                text(row.get("orderNo")),
                text(row.get("pickupCode")),
                text(row.get("source")),
                text(row.get("status")),
                asLong(row.get("productId")),
                text(row.get("productName")),
                (int) asLong(row.get("quantity")),
                money(row.get("basePrice")),
                money(row.get("unitPrice")),
                money(row.get("itemAmount")),
                options,
                summarize(options),
                text(row.get("remark")),
                countRemarkTags(text(row.get("remarkTags"))),
                text(row.get("voidReason")),
                format(createdAt),
                format(paidAt),
                format(asTime(row.get("startedAt"))),
                format(asTime(row.get("completedAt"))),
                format(asTime(row.get("closedAt"))),
                format(asTime(row.get("voidedAt"))),
                prepMinutes(row.get("prepSeconds")),
                anchor == null ? null : anchor.format(DATE_FMT));
    }

    /** 规格快照 → 逐行规格（保留快照原序，含加料；解析失败按空规格，不让脏数据打断整份档案）。 */
    private List<BrewArchiveVo.OptionLine> optionLines(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            OptionSnapshot[] arr = JSON.readValue(json, OptionSnapshot[].class);
            if (arr == null) {
                return Collections.emptyList();
            }
            List<BrewArchiveVo.OptionLine> lines = new ArrayList<>(arr.length);
            for (OptionSnapshot option : arr) {
                lines.add(new BrewArchiveVo.OptionLine(option.getGroupName(), option.getOptionName(),
                        option.getPriceDelta()));
            }
            return lines;
        } catch (Exception e) {
            log.warn("[T59] 规格快照解析失败，该杯按空规格返回：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 规格一行摘要（仅供列表速览；核对仍以 {@link BrewArchiveVo.OptionLine} 逐行为准）。 */
    private String summarize(List<BrewArchiveVo.OptionLine> options) {
        if (options.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (BrewArchiveVo.OptionLine line : options) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(line.optionName());
        }
        return sb.toString();
    }

    /** 结构化备注标签条数（T53 的 {@code orders.remark_tags}；落地前恒为 0）。 */
    private int countRemarkTags(String json) {
        if (!StringUtils.hasText(json)) {
            return 0;
        }
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isArray() ? node.size() : 0;
        } catch (Exception e) {
            log.warn("[T59] 备注标签解析失败，按 0 条返回：{}", e.getMessage());
            return 0;
        }
    }

    /** 制作耗时（秒 → 分钟四舍五入；未完成或未取到派生列时为 null）。 */
    private Integer prepMinutes(Object prepSeconds) {
        if (prepSeconds == null) {
            return null;
        }
        long seconds = asLong(prepSeconds);
        return (int) Math.round(seconds / 60.0);
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        for (OrderStatus candidate : OrderStatus.values()) {
            if (candidate.name().equals(normalized)) {
                return normalized;
            }
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "status 非法：" + status);
    }

    private LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value.trim(), DATE_FMT);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), field + " 需为 yyyy-MM-dd");
        }
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.format(DATETIME_FMT);
    }

    private static LocalDateTime asTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(value.toString());
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(value.toString());
    }

    private static String money(Object value) {
        if (value instanceof BigDecimal decimal) {
            return MoneyUtils.format(decimal);
        }
        return value == null ? MoneyUtils.format(null) : MoneyUtils.format(new BigDecimal(value.toString()));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}