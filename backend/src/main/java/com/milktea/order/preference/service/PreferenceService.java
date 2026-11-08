package com.milktea.order.preference.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.ai.decision.PreferenceSnapshot;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderItem;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.OrderItemMapper;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.preference.entity.CustomerPreference;
import com.milktea.order.preference.mapper.CustomerPreferenceMapper;
import com.milktea.order.preference.vo.PreferenceVo;
import com.milktea.order.product.entity.Product;
import com.milktea.order.product.entity.SpecGroup;
import com.milktea.order.product.mapper.ProductMapper;
import com.milktea.order.product.mapper.SpecGroupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 长期口味偏好（不进对话热路径的「记忆」实现）。
 *
 * <h2>只从已成交订单隐式聚合</h2>
 * <p>数据源是 {@code orders} + {@code order_item.options_snapshot}，<b>不解析对话内容</b>。
 * 计入的状态只有 PAID / PREPARING / COMPLETED（都代表已付款）；
 * PENDING_PAYMENT（未付）不算、CLOSED（超时关单）不算、VOIDED（已作废退款）不算——
 * 否则「点错又退掉的那杯」会污染偏好，下次还会照着推荐一遍。</p>
 *
 * <h2>「认定」的判定口径</h2>
 * <ul>
 *   <li><b>分母按订单数、不按杯数</b>：一杯点三次与一次点三杯，后者不代表「反复选择」。
 *       每个订单在每个规格组内只计一次（多杯同规格也只算一次）。</li>
 *   <li><b>组内分母单独统计</b>：不是所有商品都挂了全部规格组。若用总订单数当分母，
 *       「少数几个可加料的商品」会被稀释，永远认定不出加料偏好。</li>
 *   <li><b>样本不足不认定</b>：组内分母低于 {@code preference.min-sample-orders} 时整组放弃；
 *       这是「防一单定终身」——只下过一单就把它当成口味，比没有偏好更糟。</li>
 *   <li><b>按频次阈值取众数</b>：选项出现次数 / 组内订单数 ≥ {@code preference.mode-ratio} 才认定。
 *       单选组的占比天然互斥，故阈值 0.6 时最多只会命中一项；多选组则可能命中多项。</li>
 * </ul>
 *
 * <h2>为什么不进对话热路径</h2>
 * <p>聚合涉及 orders（{@code idx_customer(customer_id, created_at)} 命中）+ order_item 两次查询，
 * 放在每轮对话里做是浪费。因此：订单事件（PAID/COMPLETED）异步刷新一次，
 * 另有 {@code preference.stale-hours} 的惰性补算兜底（页面进入时触发）。
 * 注意这是<b>纯读 + 一条 upsert</b>，故没有 {@code @Transactional}——
 * 也刻意不写成「自己调自己」的事务方法（自调用不过代理，事务会静默失效）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreferenceService {

    /** 计入偏好的订单状态：都是「已付款」的态。 */
    private static final List<String> SETTLED_STATUSES = List.of(
            OrderStatus.PAID.name(), OrderStatus.PREPARING.name(), OrderStatus.COMPLETED.name());

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CustomerPreferenceMapper preferenceMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SpecGroupMapper specGroupMapper;
    private final ProductMapper productMapper;

    /** 认定所需的最少订单数（组内分母），默认 3：低于它宁可没有偏好。 */
    @Value("${preference.min-sample-orders:3}")
    private int minSampleOrders;

    /** 认定所需的频次占比，默认 0.6。 */
    @Value("${preference.mode-ratio:0.6}")
    private double modeRatio;

    /** 参与聚合的最近订单数上限，默认 50（口味会变，远古订单不该绑架今天的选择）。 */
    @Value("${preference.window-orders:50}")
    private int windowOrders;

    /** 惰性补算的过期时长（小时），默认 24。 */
    @Value("${preference.stale-hours:24}")
    private int staleHours;

    /**
     * 决策层用：读偏好快照（必要时先惰性补算）。
     *
     * @param customerId 顾客 id
     * @return 偏好快照；无历史或样本不足时返回 {@link PreferenceSnapshot#empty()}（不注入任何默认值）
     */
    public PreferenceSnapshot snapshot(Long customerId) {
        CustomerPreference row = loadFresh(customerId);
        if (row == null) {
            return PreferenceSnapshot.empty();
        }
        int sampleOrders = row.getSampleOrders() == null ? 0 : row.getSampleOrders();
        if (sampleOrders < minSampleOrders()) {
            return PreferenceSnapshot.empty();
        }
        return new PreferenceSnapshot(parseOptions(row.getOptionNamesJson()),
                row.getFavoriteProductName(), sampleOrders);
    }

    /**
     * 接口用：口味档案视图（个人中心展示）。
     *
     * @param customerId 顾客 id
     */
    public PreferenceVo view(Long customerId) {
        CustomerPreference row = loadFresh(customerId);
        int sampleOrders = row == null || row.getSampleOrders() == null ? 0 : row.getSampleOrders();
        Map<String, List<String>> options = row == null ? Map.of() : parseOptions(row.getOptionNamesJson());
        String favorite = row == null ? null : row.getFavoriteProductName();
        if (sampleOrders < minSampleOrders() || (options.isEmpty() && !StringUtils.hasText(favorite))) {
            return PreferenceVo.empty(sampleOrders);
        }
        List<PreferenceVo.GroupPreference> groups = new ArrayList<>();
        for (SpecGroup group : orderedGroups()) {
            List<String> names = options.get(group.getCode());
            if (names != null && !names.isEmpty()) {
                groups.add(new PreferenceVo.GroupPreference(group.getCode(), group.getName(), names));
            }
        }
        return new PreferenceVo(true, sampleOrders, favorite, groups);
    }

    /**
     * 读偏好行；不存在或已超过 {@code stale-hours} 时先重算（惰性补算）。
     *
     * <p>「不存在」也走重算：这样新客进入页面会落一行 {@code sample_orders=0} 的记录，
     * 下次请求即直接命中唯一索引，不会每次都在订单表上扫一遍。</p>
     */
    public CustomerPreference loadFresh(Long customerId) {
        if (customerId == null) {
            return null;
        }
        CustomerPreference row = selectByCustomer(customerId);
        LocalDateTime threshold = LocalDateTime.now().minusHours(Math.max(staleHours, 1));
        if (row != null && row.getBuiltAt() != null && row.getBuiltAt().isAfter(threshold)) {
            return row;
        }
        return rebuild(customerId);
    }

    /**
     * 重算并覆盖写偏好（幂等：同样的订单历史必然得到同样的结果）。
     *
     * @param customerId 顾客 id
     * @return 落库后的偏好行
     */
    public CustomerPreference rebuild(Long customerId) {
        LocalDateTime now = LocalDateTime.now();
        List<Order> orders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .select(Order::getId)
                .eq(Order::getCustomerId, customerId)
                .in(Order::getStatus, SETTLED_STATUSES)
                .orderByDesc(Order::getCreatedAt)
                .orderByDesc(Order::getId)
                .last("LIMIT " + Math.max(windowOrders, 1)));
        int sampleOrders = orders.size();

        Map<String, List<String>> options = new LinkedHashMap<>();
        String favoriteName = null;
        if (sampleOrders > 0) {
            List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                    .in(OrderItem::getOrderId, orders.stream().map(Order::getId).toList()));
            Aggregate aggregate = aggregate(items);
            Map<Long, String> codeByGroupId = groupCodeById();
            collectGroupOptions(aggregate, codeByGroupId, options);
            favoriteName = pickFavoriteProduct(aggregate, sampleOrders);
        }

        // 存成数组而非映射：与项目既有的 DraftItem[] / OptionSnapshot[] 同一读写方式，
        // 不用为了「组code → 选项名」的映射去碰泛型反序列化
        List<PreferenceEntry> entries = new ArrayList<>(options.size());
        for (Map.Entry<String, List<String>> entry : options.entrySet()) {
            entries.add(new PreferenceEntry(entry.getKey(), List.copyOf(entry.getValue())));
        }
        String json = entries.isEmpty() ? null : JSON.writeValueAsString(entries);
        preferenceMapper.upsert(customerId, json, favoriteName, sampleOrders,
                CustomerPreference.SOURCE_INFERRED, now);
        log.info("[PREF] 偏好已刷新 customerId={} sampleOrders={} groups={} favorite={}",
                customerId, sampleOrders, options.keySet(), favoriteName != null);
        return selectByCustomer(customerId);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 按「组内订单数 × 频次阈值」认定规格偏好。 */
    private void collectGroupOptions(Aggregate aggregate,
                                     Map<Long, String> codeByGroupId,
                                     Map<String, List<String>> options) {
        for (Map.Entry<Long, Map<String, Integer>> entry : aggregate.groupOptionCounts().entrySet()) {
            String code = codeByGroupId.get(entry.getKey());
            if (code == null) {
                // 规格组已被删除：不为一个不存在的组认定偏好（前端口径也对不上）
                continue;
            }
            int denominator = aggregate.groupDenominator().getOrDefault(entry.getKey(), 0);
            if (denominator < minSampleOrders()) {
                continue;
            }
            List<String> hit = new ArrayList<>();
            for (Map.Entry<String, Integer> option : entry.getValue().entrySet()) {
                if ((double) option.getValue() / denominator >= modeRatio) {
                    hit.add(option.getKey());
                }
            }
            if (!hit.isEmpty()) {
                options.put(code, hit);
            }
        }
    }

    /**
     * 常点商品：按订单数取众数，达阈值才认定；名称取当前商品名（商品已删则回落快照名）。
     *
     * <p>总的订单数同样要过 {@code min-sample-orders}：否则「只下过两单、恰好都是同一款」
     * 就会被当成常点商品，这正是「一单定终身」的另一种形态。规格偏好与常点商品的
     * 样本门槛必须一致，否则会出现「没有甜度偏好、却有常点商品」这种自相矛盾的档案。</p>
     */
    private String pickFavoriteProduct(Aggregate aggregate, int sampleOrders) {
        if (sampleOrders < minSampleOrders()) {
            return null;
        }
        Long bestProductId = null;
        int bestCount = 0;
        for (Map.Entry<Long, Integer> entry : aggregate.productCounts().entrySet()) {
            if (entry.getValue() > bestCount) {
                bestProductId = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        if (bestProductId == null || (double) bestCount / sampleOrders < modeRatio) {
            return null;
        }
        Product product = productMapper.selectById(bestProductId);
        return product != null ? product.getName() : aggregate.productNameById().get(bestProductId);
    }

    /**
     * 聚合订单项快照。
     *
     * <p><b>每个订单在每个组内只计一次</b>（用 Set 去重）：一杯点三次与一次点三杯是两回事，
     * 只有前者才代表「反复选择」。</p>
     */
    private Aggregate aggregate(List<OrderItem> items) {
        Map<Long, Set<Long>> productsByOrder = new HashMap<>();
        Map<Long, String> productNameById = new HashMap<>();
        Map<Long, Map<Long, Set<String>>> groupsByOrder = new HashMap<>();

        for (OrderItem item : items) {
            if (item.getProductId() != null) {
                productsByOrder.computeIfAbsent(item.getOrderId(), key -> new HashSet<>()).add(item.getProductId());
                if (item.getProductName() != null) {
                    productNameById.putIfAbsent(item.getProductId(), item.getProductName());
                }
            }
            for (OptionSnapshot snapshot : parseSnapshots(item.getOptionsSnapshot())) {
                if (snapshot.getGroupId() == null || !StringUtils.hasText(snapshot.getOptionName())) {
                    continue;
                }
                groupsByOrder.computeIfAbsent(item.getOrderId(), key -> new HashMap<>())
                        .computeIfAbsent(snapshot.getGroupId(), key -> new HashSet<>())
                        .add(snapshot.getOptionName());
            }
        }

        Map<Long, Integer> productCounts = new HashMap<>();
        for (Set<Long> productIds : productsByOrder.values()) {
            for (Long productId : productIds) {
                productCounts.merge(productId, 1, Integer::sum);
            }
        }

        Map<Long, Map<String, Integer>> groupOptionCounts = new HashMap<>();
        Map<Long, Integer> groupDenominator = new HashMap<>();
        for (Map<Long, Set<String>> byGroup : groupsByOrder.values()) {
            for (Map.Entry<Long, Set<String>> group : byGroup.entrySet()) {
                groupDenominator.merge(group.getKey(), 1, Integer::sum);
                for (String optionName : group.getValue()) {
                    groupOptionCounts.computeIfAbsent(group.getKey(), key -> new HashMap<>())
                            .merge(optionName, 1, Integer::sum);
                }
            }
        }
        return new Aggregate(groupOptionCounts, groupDenominator, productCounts, productNameById);
    }

    private CustomerPreference selectByCustomer(Long customerId) {
        return preferenceMapper.selectOne(new LambdaQueryWrapper<CustomerPreference>()
                .eq(CustomerPreference::getCustomerId, customerId));
    }

    /** 规格组 id → code（快照里存的是 id，故用 id 做键，改中文组名不会让历史偏好失效）。 */
    private Map<Long, String> groupCodeById() {
        Map<Long, String> codeById = new HashMap<>();
        for (SpecGroup group : specGroupMapper.selectList(null)) {
            if (group.getId() != null && StringUtils.hasText(group.getCode())) {
                codeById.put(group.getId(), group.getCode());
            }
        }
        return codeById;
    }

    private List<SpecGroup> orderedGroups() {
        return specGroupMapper.selectList(new LambdaQueryWrapper<SpecGroup>()
                .orderByAsc(SpecGroup::getSortOrder, SpecGroup::getId));
    }

    private Map<String, List<String>> parseOptions(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            PreferenceEntry[] parsed = JSON.readValue(json, PreferenceEntry[].class);
            if (parsed == null) {
                return Map.of();
            }
            Map<String, List<String>> options = new LinkedHashMap<>();
            for (PreferenceEntry entry : parsed) {
                if (entry != null && StringUtils.hasText(entry.groupCode()) && entry.optionNames() != null) {
                    options.put(entry.groupCode(), entry.optionNames());
                }
            }
            return options;
        } catch (Exception e) {
            // 库里的 JSON 坏了不该让整条链路不可用，按「无偏好」处理
            log.warn("[PREF] 偏好 JSON 解析失败，按无偏好处理：{}", e.getMessage());
            return Map.of();
        }
    }

    private List<OptionSnapshot> parseSnapshots(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            OptionSnapshot[] parsed = JSON.readValue(json, OptionSnapshot[].class);
            return parsed == null ? List.of() : List.of(parsed);
        } catch (Exception e) {
            log.warn("[PREF] 规格快照解析失败，跳过该条目：{}", e.getMessage());
            return List.of();
        }
    }

    private int minSampleOrders() {
        return Math.max(minSampleOrders, 1);
    }

    /** 一次聚合的中间结果。 */
    private record Aggregate(
            Map<Long, Map<String, Integer>> groupOptionCounts,
            Map<Long, Integer> groupDenominator,
            Map<Long, Integer> productCounts,
            Map<Long, String> productNameById) {
    }

    /**
     * 偏好 JSON 的存储形态：{@code [{"groupCode":"SWEETNESS","optionNames":["半糖"]}]}。
     *
     * <p>用数组而非映射，是为了与项目既有的 {@code DraftItem[]} / {@code OptionSnapshot[]}
     * 保持同一种读写方式（数组 + 具体记录类型），不必为泛型映射引入额外的反序列化写法。</p>
     *
     * @param groupCode   规格组 code
     * @param optionNames 认定的中文选项名
     */
    public record PreferenceEntry(String groupCode, List<String> optionNames) {
    }
}
