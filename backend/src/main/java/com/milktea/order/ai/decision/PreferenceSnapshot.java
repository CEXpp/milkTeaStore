package com.milktea.order.ai.decision;

import java.util.List;
import java.util.Map;

/**
 * 顾客口味偏好快照（长期记忆在决策层的投影）。
 *
 * <p><b>为什么存中文名而不是下标</b>：这份数据有两个消费方——决策层要把它编码进 state，
 * 个人中心的「口味档案」要直接展示给顾客看。存中文名两边都能用；若存下标，
 * 个人中心还得反查一次菜单，且菜单改版后下标会错位到别的选项上（静默显示错口味）。</p>
 *
 * <p>下标的换算交给 {@link MenuStateAssembler}：它本来就要加载全量菜单，顺手映射是最省事
 * 也最不容易漂移的做法。</p>
 *
 * @param optionNamesByGroup 规格组 code → 该组的中文选项名（保序，多选组可多项）
 * @param favoriteProductName 常点商品的中文名；无历史时为空
 * @param sampleOrders        样本订单数（供决策层判断「这条偏好有多可信」）
 */
public record PreferenceSnapshot(
        Map<String, List<String>> optionNamesByGroup,
        String favoriteProductName,
        int sampleOrders) {

    /** 空偏好：新客或历史订单不足时不注入任何默认值（宁可问一句，不可猜错）。 */
    public static PreferenceSnapshot empty() {
        return new PreferenceSnapshot(Map.of(), null, 0);
    }

    public boolean isEmpty() {
        boolean noOptions = optionNamesByGroup == null || optionNamesByGroup.isEmpty();
        return noOptions && (favoriteProductName == null || favoriteProductName.isBlank());
    }

    /** 取某规格组的偏好选项名；无则返回空列表。 */
    public List<String> names(String groupCode) {
        if (optionNamesByGroup == null) {
            return List.of();
        }
        List<String> names = optionNamesByGroup.get(groupCode);
        return names == null ? List.of() : names;
    }
}
