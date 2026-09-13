package com.milktea.order.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.dto.OrderItemRequest;
import com.milktea.order.order.dto.PricedItem;
import com.milktea.order.order.dto.PricingResult;
import com.milktea.order.product.entity.Product;
import com.milktea.order.product.entity.ProductSpecGroup;
import com.milktea.order.product.entity.SpecGroup;
import com.milktea.order.product.entity.SpecOption;
import com.milktea.order.product.mapper.ProductMapper;
import com.milktea.order.product.mapper.ProductSpecGroupMapper;
import com.milktea.order.product.mapper.SpecGroupMapper;
import com.milktea.order.product.mapper.SpecOptionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 计价引擎 —— 全系统唯一算价入口（LLD 4.5）。
 *
 * <p>小程序购物车、柜台单、AI 草稿转订单三个入口一律走 {@link #calculatePrice(List)}，
 * 菜单展示的规格价差也复用同一份规格数据，保证「价格一律后端计算」。
 * 计算规则：{@code unitPrice = basePrice + Σ priceDelta}，{@code itemAmount = unitPrice × quantity}，
 * {@code totalAmount = Σ itemAmount}（无优惠体系、无抹零）。</p>
 *
 * <p>校验与错误码（LLD 3.2）：</p>
 * <ul>
 *     <li>商品不存在或 status≠1（下架）→ 1002</li>
 *     <li>单选组未选 / 选多项、选项不属于该商品、选项已停用、重复选择 → 1003</li>
 *     <li>字段缺失、quantity 不在 1..20 → 1001</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private static final int ENABLED = 1;
    private static final int SCALE = 2;
    private static final int MULTI_SELECT = 1;

    private final ProductMapper productMapper;
    private final SpecGroupMapper specGroupMapper;
    private final SpecOptionMapper specOptionMapper;
    private final ProductSpecGroupMapper productSpecGroupMapper;

    /**
     * 整单计价（唯一入口，批量）。
     *
     * @param items 下单项，至少一个
     * @return 每项单价/金额与整单合计
     */
    public PricingResult calculatePrice(List<OrderItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "下单项不能为空");
        }
        List<PricedItem> priced = new ArrayList<>(items.size());
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest item : items) {
            PricedItem pricedItem = calculatePrice(item);
            priced.add(pricedItem);
            total = total.add(pricedItem.getItemAmount());
        }
        PricingResult result = new PricingResult();
        result.setItems(priced);
        result.setTotalAmount(total.setScale(SCALE, RoundingMode.HALF_UP));
        return result;
    }

    /**
     * 单项计价：返回单杯价、单项金额与规格快照（不落库）。
     *
     * @param item 下单项（productId + optionIds + quantity）
     * @return 计价结果，含商品名与规格快照
     */
    public PricedItem calculatePrice(OrderItemRequest item) {
        if (item == null || item.getProductId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "下单项缺少 productId");
        }
        Integer quantity = item.getQuantity();
        if (quantity == null || quantity < OrderItemRequest.MIN_QUANTITY || quantity > OrderItemRequest.MAX_QUANTITY) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(),
                    "quantity 需在 " + OrderItemRequest.MIN_QUANTITY + ".." + OrderItemRequest.MAX_QUANTITY + " 之间");
        }

        Product product = productMapper.selectById(item.getProductId());
        if (product == null || product.getStatus() == null || product.getStatus() != Product.STATUS_ON) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        Map<Long, SpecGroup> applicableGroups = loadApplicableGroups(product.getId());
        List<SpecOption> selected = resolveOptions(item.getOptionIds(), applicableGroups);

        BigDecimal unitPrice = product.getBasePrice() == null ? BigDecimal.ZERO : product.getBasePrice();
        for (SpecOption option : selected) {
            unitPrice = unitPrice.add(option.getPriceDelta() == null ? BigDecimal.ZERO : option.getPriceDelta());
        }

        PricedItem priced = new PricedItem();
        priced.setProductId(product.getId());
        priced.setProductName(product.getName());
        priced.setBasePrice(scale(product.getBasePrice() == null ? BigDecimal.ZERO : product.getBasePrice()));
        priced.setOptions(buildSnapshot(applicableGroups, selected));
        priced.setQuantity(quantity);
        priced.setUnitPrice(scale(unitPrice));
        priced.setItemAmount(scale(unitPrice.multiply(BigDecimal.valueOf(quantity))));
        return priced;
    }

    /**
     * 商品 → 适用的启用中规格组（sort_order 升序）。
     */
    private Map<Long, SpecGroup> loadApplicableGroups(Long productId) {
        List<ProductSpecGroup> relations = productSpecGroupMapper.selectList(
                new LambdaQueryWrapper<ProductSpecGroup>().eq(ProductSpecGroup::getProductId, productId));
        if (relations == null || relations.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> groupIds = relations.stream()
                .map(ProductSpecGroup::getGroupId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<SpecGroup> groups = specGroupMapper.selectList(new LambdaQueryWrapper<SpecGroup>()
                .in(SpecGroup::getId, groupIds)
                .eq(SpecGroup::getEnabled, ENABLED)
                .orderByAsc(SpecGroup::getSortOrder, SpecGroup::getId));
        if (groups == null) {
            return Collections.emptyMap();
        }
        return groups.stream()
                .collect(Collectors.toMap(SpecGroup::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * 解析并校验规格项：存在、启用、属于该商品适用的规格组、无重复，
     * 再按「单选组恰 1 项、多选组 0..n 项」校验分组选择数。
     */
    private List<SpecOption> resolveOptions(List<Long> optionIds, Map<Long, SpecGroup> applicableGroups) {
        List<SpecOption> selected = new ArrayList<>();
        if (optionIds != null && !optionIds.isEmpty()) {
            if (new HashSet<>(optionIds).size() != optionIds.size()) {
                throw new BusinessException(ErrorCode.SPEC_INVALID.getCode(), "同一规格项不可重复选择");
            }
            List<SpecOption> found = specOptionMapper.selectBatchIds(optionIds);
            if (found == null || found.size() != optionIds.size()) {
                throw new BusinessException(ErrorCode.SPEC_INVALID.getCode(), "存在不存在的规格项");
            }
            for (SpecOption option : found) {
                if (option.getEnabled() == null || option.getEnabled() != ENABLED) {
                    throw new BusinessException(ErrorCode.SPEC_INVALID.getCode(),
                            "规格项[" + option.getName() + "]已停用");
                }
                if (!applicableGroups.containsKey(option.getGroupId())) {
                    throw new BusinessException(ErrorCode.SPEC_INVALID.getCode(),
                            "规格项[" + option.getName() + "]不属于该商品");
                }
                selected.add(option);
            }
        }

        Map<Long, List<SpecOption>> byGroup = selected.stream()
                .collect(Collectors.groupingBy(SpecOption::getGroupId));
        for (SpecGroup group : applicableGroups.values()) {
            int count = byGroup.getOrDefault(group.getId(), Collections.emptyList()).size();
            boolean multiSelect = group.getMultiSelect() != null && group.getMultiSelect() == MULTI_SELECT;
            if (!multiSelect && count != 1) {
                throw new BusinessException(ErrorCode.SPEC_INVALID.getCode(),
                        "规格组[" + group.getName() + "]必须且只能选择 1 项");
            }
        }
        return selected;
    }

    /**
     * 规格快照：按规格组 sort_order、组内规格项 sort_order 升序，价差统一两位小数字符串。
     */
    private List<OptionSnapshot> buildSnapshot(Map<Long, SpecGroup> applicableGroups, List<SpecOption> selected) {
        Map<Long, List<SpecOption>> byGroup = selected.stream()
                .collect(Collectors.groupingBy(SpecOption::getGroupId, LinkedHashMap::new, Collectors.toList()));
        List<OptionSnapshot> snapshot = new ArrayList<>(selected.size());
        for (SpecGroup group : applicableGroups.values()) {
            List<SpecOption> options = byGroup.getOrDefault(group.getId(), Collections.emptyList());
            options.stream()
                    .sorted((a, b) -> {
                        int bySort = Integer.compare(nullSafe(a.getSortOrder()), nullSafe(b.getSortOrder()));
                        return bySort != 0 ? bySort : Long.compare(nullSafeLong(a.getId()), nullSafeLong(b.getId()));
                    })
                    .forEach(option -> snapshot.add(new OptionSnapshot(group.getId(), group.getName(),
                            option.getId(), option.getName(), MoneyUtils.format(option.getPriceDelta()))));
        }
        return snapshot;
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private long nullSafeLong(Long value) {
        return value == null ? 0L : value;
    }
}
