package com.milktea.order.product.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量上下架请求（T61，v1 底座 F07）。
 *
 * <p><b>与既有单条 {@link ProductStatusRequest} 的区别</b>：本请求只带 id 列表 + 目标状态，
 * <b>不涉及任何数量字段</b>——「估清」不是库存扣减，只是把 {@code product.status} 置为下架
 * （11 章既有决策「上下架已覆盖售罄场景」）。</p>
 *
 * <p>与 {@link ProductCategoryStatusRequest} 二选一：按选中项批量走本请求，按分类整体走
 * 分类请求。两者不同时生效，避免「我只想下架这一类里的一部分」被悄悄扩成整类。</p>
 */
public record ProductBatchStatusRequest(

        /** 目标状态：1 上架 / 0 下架（估清）。 */
        @NotNull(message = "status 不能为空")
        @Min(value = 0, message = "status 仅支持 0 或 1")
        @Max(value = 1, message = "status 仅支持 0 或 1")
        Integer status,

        /** 目标商品 id 列表（来自列表页多选）；为空视为非法，避免误发空批量。 */
        @NotNull(message = "productIds 不能为空")
        @Size(min = 1, max = 200, message = "单次批量 1~200 个商品")
        List<Long> productIds) {
}