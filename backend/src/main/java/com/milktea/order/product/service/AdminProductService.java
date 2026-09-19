package com.milktea.order.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.result.PageResult;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.product.dto.ProductSaveRequest;
import com.milktea.order.product.entity.Category;
import com.milktea.order.product.entity.Product;
import com.milktea.order.product.entity.ProductSpecGroup;
import com.milktea.order.product.entity.SpecGroup;
import com.milktea.order.product.mapper.CategoryMapper;
import com.milktea.order.product.mapper.ProductMapper;
import com.milktea.order.product.mapper.ProductSpecGroupMapper;
import com.milktea.order.product.mapper.SpecGroupMapper;
import com.milktea.order.product.vo.AdminProductVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商家端商品管理服务（T21，LLD 3.5.1）。
 *
 * <ul>
 *   <li>{@code GET /api/admin/products}：分页列表（categoryId / status 可筛，含图片 key、价格、状态、适用规格组 id）；</li>
 *   <li>{@code POST /api/admin/products}：新建（默认上架，可同时绑定适用规格组）；</li>
 *   <li>{@code PUT /api/admin/products/{id}}：编辑（价格改动不影响历史订单——快照机制）；</li>
 *   <li>{@code PUT /api/admin/products/{id}/status}：上下架（下架后顾客端 menu 立即消失，AC-08）；</li>
 *   <li>{@code PUT /api/admin/products/{id}/spec-groups}：适用规格组全量覆盖。</li>
 * </ul>
 *
 * <p>参数语义：分类不存在、规格组不存在一律 1001（参数错误）；商品不存在 1002。
 * 分页边界与 {@code OrderQueryService} 一致：page 从 1 起、size 默认 20 上限 100，越界归一化不报错。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminProductService {

    /** 分页默认每页条数与上限（LLD 3.1：默认 20，最大 100）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final SpecGroupMapper specGroupMapper;
    private final ProductSpecGroupMapper productSpecGroupMapper;

    /**
     * 商品分页列表：按 sortOrder 升序、id 升序；筛选条件均可缺省。
     */
    public PageResult<AdminProductVo> page(Long categoryId, Integer status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        long total = productMapper.selectCount(queryWrapper(categoryId, status));
        List<Product> products = Collections.emptyList();
        if (total > 0) {
            long offset = (long) (safePage - 1) * safeSize;
            products = productMapper.selectList(queryWrapper(categoryId, status)
                    .orderByAsc(Product::getSortOrder, Product::getId)
                    .last("LIMIT " + offset + "," + safeSize));
        }
        return PageResult.of(toVos(products), total, safePage, safeSize);
    }

    /**
     * 新建商品：默认上架（status=1）；specGroupIds 非空时同步写入适用规格组关系。
     */
    @Transactional
    public Long create(ProductSaveRequest request) {
        requireCategory(request.getCategoryId());
        List<Long> groupIds = request.getSpecGroupIds() == null ? List.of() : distinct(request.getSpecGroupIds());
        requireGroups(groupIds);

        LocalDateTime now = LocalDateTime.now();
        Product product = new Product();
        applySaveRequest(product, request);
        product.setStatus(Product.STATUS_ON);
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        productMapper.insert(product);

        replaceSpecGroups(product.getId(), groupIds);
        log.info("[T21] product created id={} name={} groups={}", product.getId(), product.getName(), groupIds);
        return product.getId();
    }

    /**
     * 编辑商品：specGroupIds 为 null 表示本次不改动适用规格组；非 null 则全量覆盖。
     */
    @Transactional
    public void update(Long id, ProductSaveRequest request) {
        Product product = requireProduct(id);
        requireCategory(request.getCategoryId());
        applySaveRequest(product, request);
        product.setUpdatedAt(LocalDateTime.now());
        productMapper.updateById(product);

        if (request.getSpecGroupIds() != null) {
            List<Long> groupIds = distinct(request.getSpecGroupIds());
            requireGroups(groupIds);
            replaceSpecGroups(id, groupIds);
        }
        log.info("[T21] product updated id={} status={}", id, product.getStatus());
    }

    /**
     * 上下架：下架后顾客端菜单立即不再返回该商品（AC-08）。
     */
    public void updateStatus(Long id, Integer status) {
        Product product = requireProduct(id);
        product.setStatus(status);
        product.setUpdatedAt(LocalDateTime.now());
        productMapper.updateById(product);
        log.info("[T21] product status changed id={} status={}", id, status);
    }

    /**
     * 适用规格组全量覆盖（先删后插，事务内完成）。
     */
    @Transactional
    public void setSpecGroups(Long id, List<Long> groupIds) {
        requireProduct(id);
        List<Long> distinctIds = distinct(groupIds == null ? List.of() : groupIds);
        requireGroups(distinctIds);
        replaceSpecGroups(id, distinctIds);
        log.info("[T21] product spec groups replaced id={} groups={}", id, distinctIds);
    }

    /** 批量组装 VO：一次 IN 查询取全部规格组关系，避免 N+1。 */
    private List<AdminProductVo> toVos(List<Product> products) {
        if (products == null || products.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> productIds = products.stream().map(Product::getId).collect(Collectors.toList());
        Map<Long, List<Long>> groupIdsByProduct = productSpecGroupMapper.selectList(
                        new LambdaQueryWrapper<ProductSpecGroup>().in(ProductSpecGroup::getProductId, productIds))
                .stream()
                .collect(Collectors.groupingBy(ProductSpecGroup::getProductId,
                        Collectors.mapping(ProductSpecGroup::getGroupId, Collectors.toList())));

        List<AdminProductVo> list = new ArrayList<>(products.size());
        for (Product product : products) {
            AdminProductVo vo = new AdminProductVo();
            vo.setId(product.getId());
            vo.setName(product.getName());
            vo.setCategoryId(product.getCategoryId());
            vo.setBasePrice(MoneyUtils.format(product.getBasePrice()));
            vo.setDescription(product.getDescription());
            vo.setImageKey(product.getImageKey());
            vo.setStatus(product.getStatus());
            vo.setSortOrder(product.getSortOrder());
            vo.setSpecGroupIds(groupIdsByProduct.getOrDefault(product.getId(), new ArrayList<>()));
            list.add(vo);
        }
        return list;
    }

    /** 覆盖写入商品-规格组关系。 */
    private void replaceSpecGroups(Long productId, List<Long> groupIds) {
        productSpecGroupMapper.delete(new LambdaQueryWrapper<ProductSpecGroup>()
                .eq(ProductSpecGroup::getProductId, productId));
        for (Long groupId : groupIds) {
            ProductSpecGroup relation = new ProductSpecGroup();
            relation.setProductId(productId);
            relation.setGroupId(groupId);
            productSpecGroupMapper.insert(relation);
        }
    }

    private void applySaveRequest(Product product, ProductSaveRequest request) {
        product.setName(request.getName().trim());
        product.setCategoryId(request.getCategoryId());
        product.setBasePrice(request.getBasePrice());
        product.setDescription(request.getDescription());
        product.setImageKey(request.getImageKey());
        product.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
    }

    private LambdaQueryWrapper<Product> queryWrapper(Long categoryId, Integer status) {
        return new LambdaQueryWrapper<Product>()
                .eq(categoryId != null, Product::getCategoryId, categoryId)
                .eq(status != null, Product::getStatus, status);
    }

    private Product requireProduct(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND.getCode(), "商品不存在");
        }
        return product;
    }

    private void requireCategory(Long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "分类不存在");
        }
    }

    /** 规格组必须全部存在，否则 1001（防止前端传入脏 id 造成商品无规格可选）。 */
    private void requireGroups(List<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return;
        }
        long found = specGroupMapper.selectCount(new LambdaQueryWrapper<SpecGroup>().in(SpecGroup::getId, groupIds));
        if (found != groupIds.size()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "所选规格组不存在");
        }
    }

    /** 去重且保持前端选择顺序；剔除 null 元素。 */
    private List<Long> distinct(List<Long> ids) {
        Set<Long> set = new LinkedHashSet<>();
        for (Long id : ids) {
            if (Objects.nonNull(id)) {
                set.add(id);
            }
        }
        return new ArrayList<>(set);
    }
}
