package com.milktea.order.product.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.product.dto.CategorySaveRequest;
import com.milktea.order.product.service.AdminCategoryService;
import com.milktea.order.product.vo.AdminCategoryVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端分类管理（T22，LLD 3.5.1，商家 JWT）。
 *
 * <ul>
 *   <li>{@code GET /api/admin/categories}：列表（含每组商品数）；</li>
 *   <li>{@code POST /api/admin/categories}：新建；</li>
 *   <li>{@code PUT /api/admin/categories/{id}}：编辑（更名 / 排序）；</li>
 *   <li>{@code DELETE /api/admin/categories/{id}}：删除（分类下有商品时返回 1001）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final AdminCategoryService adminCategoryService;

    @GetMapping("/categories")
    public R<List<AdminCategoryVo>> list() {
        return R.ok(adminCategoryService.list());
    }

    @PostMapping("/categories")
    public R<Long> create(@Valid @RequestBody CategorySaveRequest request) {
        return R.ok(adminCategoryService.create(request));
    }

    @PutMapping("/categories/{id}")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody CategorySaveRequest request) {
        adminCategoryService.update(id, request);
        return R.ok(null);
    }

    @DeleteMapping("/categories/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        adminCategoryService.delete(id);
        return R.ok(null);
    }
}
