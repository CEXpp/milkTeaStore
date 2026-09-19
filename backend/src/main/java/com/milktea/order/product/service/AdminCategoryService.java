package com.milktea.order.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.product.dto.CategorySaveRequest;
import com.milktea.order.product.entity.Category;
import com.milktea.order.product.entity.Product;
import com.milktea.order.product.mapper.CategoryMapper;
import com.milktea.order.product.mapper.ProductMapper;
import com.milktea.order.product.vo.AdminCategoryVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商家端分类管理服务（T22，LLD 3.5.1）。
 *
 * <ul>
 *   <li>{@code GET /api/admin/categories}：全量列表（含每组商品数，供删除前置校验展示）；</li>
 *   <li>{@code POST /api/admin/categories}：新建（名称唯一，排序值缺省排到末尾）；</li>
 *   <li>{@code PUT /api/admin/categories/{id}}：更名 / 排序；</li>
 *   <li>{@code DELETE /api/admin/categories/{id}}：删除——分类下仍有商品时返回 1001（LLD 3.5.1）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCategoryService {

    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;

    /**
     * 分类列表（sortOrder 升序、id 升序），productCount 含下架商品。
     */
    public List<AdminCategoryVo> list() {
        List<Category> categories = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .orderByAsc(Category::getSortOrder, Category::getId));
        Map<Long, Long> countByCategory = productMapper.selectList(null).stream()
                .filter(product -> product.getCategoryId() != null)
                .collect(Collectors.groupingBy(Product::getCategoryId, Collectors.counting()));

        List<AdminCategoryVo> list = new ArrayList<>(categories.size());
        for (Category category : categories) {
            AdminCategoryVo vo = new AdminCategoryVo();
            vo.setId(category.getId());
            vo.setName(category.getName());
            vo.setSortOrder(category.getSortOrder());
            vo.setProductCount(countByCategory.getOrDefault(category.getId(), 0L));
            list.add(vo);
        }
        return list;
    }

    /** 新建分类：同名冲突返回 1001；sortOrder 缺省时追加到末尾。 */
    public Long create(CategorySaveRequest request) {
        String name = request.getName().trim();
        requireNameAvailable(name, null);

        Category category = new Category();
        category.setName(name);
        category.setSortOrder(request.getSortOrder() == null ? nextSortOrder() : request.getSortOrder());
        category.setCreatedAt(LocalDateTime.now());
        categoryMapper.insert(category);
        log.info("[T22] category created id={} name={}", category.getId(), name);
        return category.getId();
    }

    /** 编辑分类：更名 / 排序（sortOrder 为 null 时保持原值）。 */
    public void update(Long id, CategorySaveRequest request) {
        Category category = requireCategory(id);
        String name = request.getName().trim();
        requireNameAvailable(name, id);

        category.setName(name);
        if (request.getSortOrder() != null) {
            category.setSortOrder(request.getSortOrder());
        }
        categoryMapper.updateById(category);
        log.info("[T22] category updated id={} name={}", id, name);
    }

    /** 删除分类：分类下有商品（含下架）时拒绝，返回 1001。 */
    public void delete(Long id) {
        requireCategory(id);
        long count = productMapper.selectCount(new LambdaQueryWrapper<Product>()
                .eq(Product::getCategoryId, id));
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(),
                    "该分类下仍有 " + count + " 个商品，请先移动或删除商品");
        }
        categoryMapper.deleteById(id);
        log.info("[T22] category deleted id={}", id);
    }

    private Category requireCategory(Long id) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "分类不存在");
        }
        return category;
    }

    /** 名称唯一校验（excludeId 用于编辑时排除自身）。 */
    private void requireNameAvailable(String name, Long excludeId) {
        long duplicated = categoryMapper.selectCount(new LambdaQueryWrapper<Category>()
                .eq(Category::getName, name)
                .ne(excludeId != null, Category::getId, excludeId));
        if (duplicated > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "分类名称已存在");
        }
    }

    private int nextSortOrder() {
        List<Category> categories = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .orderByDesc(Category::getSortOrder)
                .last("LIMIT 1"));
        int max = categories.isEmpty() || categories.get(0).getSortOrder() == null
                ? 0
                : categories.get(0).getSortOrder();
        return max + 1;
    }
}
