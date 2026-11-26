package com.milktea.order.preference.vo;

import java.util.List;

/**
 * 口味档案（GET /api/customer/preference 响应 data），供个人中心「口味档案」区块展示。
 *
 * <p><b>为什么返回组名与中文选项名而不是下标</b>：这是给顾客看的界面数据，
 * 顾客认识「甜度 · 半糖」，不认识 {@code SWEETNESS:1}。下标只是决策层内部的编码，
 * 不该泄漏到展示层——一旦泄漏，前端就会开始依赖它，菜单改版时前端会跟着错。</p>
 *
 * <p>{@code available=false} 时前端走空态（插画 + 一句引导），而不是展示一堆空标签：
 * 新客看到空标签会以为是坏的，看到引导才会去下单——下了第一单，档案才有东西可算。</p>
 *
 * @param available           是否已有可展示的偏好（样本足量且至少认定出一项）
 * @param sampleOrders        参与聚合的已成交订单数（前端展示「基于 N 单」）
 * @param favoriteProductName 常点商品中文名；无则 null
 * @param groups              认定的规格偏好（按规格组顺序，仅含有内容的组）
 */
public record PreferenceVo(
        boolean available,
        int sampleOrders,
        String favoriteProductName,
        List<GroupPreference> groups) {

    /** 空档（新客或样本不足）。 */
    public static PreferenceVo empty(int sampleOrders) {
        return new PreferenceVo(false, sampleOrders, null, List.of());
    }

    /**
     * 单个规格组的偏好。
     *
     * @param code        规格组 code（如 {@code SWEETNESS}）
     * @param name        中文组名（如 {@code 甜度}，前端直接展示）
     * @param optionNames 认定的中文选项名（单选组最多一项，多选组可多项）
     */
    public record GroupPreference(String code, String name, List<String> optionNames) {
    }
}
