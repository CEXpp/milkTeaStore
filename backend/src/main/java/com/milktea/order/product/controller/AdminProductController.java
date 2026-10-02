package com.milktea.order.product.controller;

import com.milktea.order.common.result.PageResult;
import com.milktea.order.common.result.R;
import com.milktea.order.product.dto.ProductBatchStatusRequest;
import com.milktea.order.product.dto.ProductCategoryStatusRequest;
import com.milktea.order.product.dto.ProductSaveRequest;
import com.milktea.order.product.dto.ProductSpecGroupRequest;
import com.milktea.order.product.dto.ProductStatusRequest;
import com.milktea.order.product.service.AdminProductService;
import com.milktea.order.product.vo.AdminProductVo;
import com.milktea.order.product.vo.ProductBatchStatusVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端商品管理（T21，LLD 3.5.1，商家 JWT——路径属 {@code /api/admin/**} 鉴权矩阵）。
 *
 * <ul>
 *   <li>{@code GET /api/admin/products?categoryId=&page=&size=&status=}：分页列表；</li>
 *   <li>{@code POST /api/admin/products}：新建；</li>
 *   <li>{@code PUT /api/admin/products/{id}}：编辑；</li>
 *   <li>{@code PUT /api/admin/products/{id}/status}：上下架（body {status: 0|1}）；</li>
 *   <li>{@code PUT /api/admin/products/{id}/spec-groups}：设置适用规格组（body {groupIds: []}，全量覆盖）；</li>
 *   <li>{@code PUT /api/admin/products/batch-status}：批量上下架（T61，body {status, productIds}）；</li>
 *   <li>{@code PUT /api/admin/products/category-status}：分类级一键估清（T61，body {categoryId, status}）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminProductController {

    private final AdminProductService adminProductService;

    /** 商品分页列表：status/categoryId 均可缺省；金额为两位小数字符串。 */
    @GetMapping("/products")
    public R<PageResult<AdminProductVo>> page(
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return R.ok(adminProductService.page(categoryId, status, page, size));
    }

    /** 新建商品：返回新商品 id。 */
    @PostMapping("/products")
    public R<Long> create(@Valid @RequestBody ProductSaveRequest request) {
        return R.ok(adminProductService.create(request));
    }

    /** 编辑商品（价格修改不影响历史订单——快照机制）。 */
    @PutMapping("/products/{id}")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody ProductSaveRequest request) {
        adminProductService.update(id, request);
        return R.ok(null);
    }

    /** 上下架：下架后顾客端菜单立即消失（AC-08）。 */
    @PutMapping("/products/{id}/status")
    public R<Void> updateStatus(@PathVariable("id") Long id, @Valid @RequestBody ProductStatusRequest request) {
        adminProductService.updateStatus(id, request.getStatus());
        return R.ok(null);
    }

    /** 设置适用规格组：全量覆盖（先删后插）。 */
    @PutMapping("/products/{id}/spec-groups")
    public R<Void> setSpecGroups(@PathVariable("id") Long id,
                                 @Valid @RequestBody ProductSpecGroupRequest request) {
        adminProductService.setSpecGroups(id, request.getGroupIds());
        return R.ok(null);
    }

    /**
     * 批量上下架（T61，F07）：列表页多选后一次生效（body {status: 0|1, productIds: []}）。
     *
     * <p>「估清」在本系统即批量下架——不引入库存字段（11 章既有决策）。返回实际受影响条数，
     * 便于界面显示「已下架 N / 选中 M」，避免半生效被误认为全成功。</p>
     */
    @PutMapping("/products/batch-status")
    public R<ProductBatchStatusVo> updateStatusBatch(@Valid @RequestBody ProductBatchStatusRequest request) {
        return R.ok(adminProductService.updateStatusBatch(request.productIds(), request.status()));
    }

    /**
     * 分类级一键估清（T61，F07）：整类上下架（body {categoryId, status: 0|1}）。
     *
     * <p>不接受商品 id 子集——部分保留会让顾客仍能点到做不出来的商品，那正是估清要消除的体验损失。</p>
     */
    @PutMapping("/products/category-status")
    public R<ProductBatchStatusVo.Category> updateCategoryStatus(
            @Valid @RequestBody ProductCategoryStatusRequest request) {
        return R.ok(adminProductService.updateCategoryStatus(request.categoryId(), request.status()));
    }
}
