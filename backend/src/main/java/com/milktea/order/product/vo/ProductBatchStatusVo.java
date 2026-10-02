package com.milktea.order.product.vo;

/**
 * 批量上下架结果（T61，v1 底座 F07）。
 *
 * <p><b>返回实际受影响条数而不是静默成功</b>：批量操作里最危险的是「以为全下了、其实只下了一半」。
 * 把 {@code affected} 与 {@code requested} 一并回给前端，让界面能明确显示「已下架 3 / 选中 5」，
 * 由店长决定是否继续处理剩下的。</p>
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param requested 前端提交的条数（去重后）
 * @param affected  实际被改动的条数；等于 {@code requested} 表示状态已是目标态、无需改动也算在内
 */
public record ProductBatchStatusVo(int requested, int affected) {

    /** 按分类估清的结果：额外给出该分类下的商品总数，便于店长核对范围。 */
    public record Category(Long categoryId, String categoryName, int total, int affected) {
    }
}