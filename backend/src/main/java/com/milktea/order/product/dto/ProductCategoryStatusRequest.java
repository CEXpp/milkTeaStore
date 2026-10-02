package com.milktea.order.product.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 分类级一键估清请求（T61，v1 底座 F07）。
 *
 * <p>语义：「奶茶类今日售罄」= 把该分类下<b>全部</b>商品的 {@code status} 置为下架。</p>
 *
 * <p><b>为何整类一起动</b>：分类估清是店长对「这一类今天做不出来」的整体判断；
 * 若允许部分保留，店员在菜单上仍会看到该类别的部分商品，顾客点单时才发现做不了——
 * 那正是「估清」要消除的体验损失。故本请求<b>不接受</b>商品 id 子集：
 * 要留哪几个，先单独上架它们，再执行估清。</p>
 *
 * <p><b>不涉及数量字段</b>：同 {@link ProductBatchStatusRequest}，估清只是上下架。</p>
 */
public record ProductCategoryStatusRequest(

        /** 目标分类 id。 */
        @NotNull(message = "categoryId 不能为空")
        Long categoryId,

        /** 目标状态：1 整类恢复上架 / 0 整类估清下架。 */
        @NotNull(message = "status 不能为空")
        @Min(value = 0, message = "status 仅支持 0 或 1")
        @Max(value = 1, message = "status 仅支持 0 或 1")
        Integer status) {
}