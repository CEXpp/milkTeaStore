package com.milktea.order.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.product.entity.Category;
import com.milktea.order.product.entity.Product;
import com.milktea.order.product.entity.ProductSpecGroup;
import com.milktea.order.product.entity.SpecGroup;
import com.milktea.order.product.entity.SpecOption;
import com.milktea.order.product.mapper.CategoryMapper;
import com.milktea.order.product.mapper.ProductMapper;
import com.milktea.order.product.mapper.ProductSpecGroupMapper;
import com.milktea.order.product.mapper.SpecGroupMapper;
import com.milktea.order.product.mapper.SpecOptionMapper;
import com.milktea.order.product.vo.CategoryVo;
import com.milktea.order.product.vo.MenuVo;
import com.milktea.order.product.vo.ProductVo;
import com.milktea.order.product.vo.SpecGroupVo;
import com.milktea.order.product.vo.SpecOptionVo;
import com.milktea.order.shop.entity.ShopConfig;
import com.milktea.order.shop.mapper.ShopConfigMapper;
import com.milktea.order.shop.vo.ShopStatusVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 顾客端菜单读链路：分类（sort asc）→ 分类下上架商品 → 商品适用的规格组与选项（价差）。
 * <p>
 * 图片只存 MinIO 对象 key，对外统一拼接 {@code /api/files/{key}} 代理路径。
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    /** 图片代理路径前缀，与 LLD 3.3 的 imageUrl 约定一致 */
    public static final String FILE_URL_PREFIX = "/api/files/";

    private static final String CONFIG_PAUSED = "paused";
    private static final String CONFIG_NOTICE = "notice";
    private static final int ENABLED = 1;

    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;
    private final SpecGroupMapper specGroupMapper;
    private final SpecOptionMapper specOptionMapper;
    private final ProductSpecGroupMapper productSpecGroupMapper;
    private final ShopConfigMapper shopConfigMapper;

    /**
     * 全量菜单：暂停营业时仍返回完整菜单，由前端展示暂停横幅。
     */
    public MenuVo getMenu() {
        MenuVo menu = new MenuVo();
        menu.setPaused(isPaused());

        List<Category> categories = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .orderByAsc(Category::getSortOrder, Category::getId));
        List<Product> onSaleProducts = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, Product.STATUS_ON)
                .orderByAsc(Product::getSortOrder, Product::getId));

        Map<Long, List<Product>> productsByCategory = onSaleProducts.stream()
                .collect(Collectors.groupingBy(Product::getCategoryId, LinkedHashMap::new, Collectors.toList()));
        List<Long> productIds = onSaleProducts.stream().map(Product::getId).toList();

        Map<Long, List<SpecGroup>> groupsByProduct = loadSpecGroups(productIds);
        Map<Long, List<SpecOption>> optionsByGroup = loadSpecOptions(groupsByProduct.values().stream()
                .flatMap(List::stream)
                .map(SpecGroup::getId)
                .collect(Collectors.toSet()));

        List<CategoryVo> categoryVos = new ArrayList<>(categories.size());
        for (Category category : categories) {
            CategoryVo categoryVo = new CategoryVo();
            categoryVo.setId(category.getId());
            categoryVo.setName(category.getName());
            categoryVo.setSortOrder(category.getSortOrder());
            categoryVo.setProducts(productsByCategory.getOrDefault(category.getId(), Collections.emptyList())
                    .stream()
                    .map(product -> toProductVo(product,
                            groupsByProduct.getOrDefault(product.getId(), Collections.emptyList()),
                            optionsByGroup))
                    .collect(Collectors.toList()));
            categoryVos.add(categoryVo);
        }
        menu.setCategories(categoryVos);
        return menu;
    }

    /**
     * 门店营业状态（读 shop_config：paused / notice）。
     */
    public ShopStatusVo getShopStatus() {
        Map<String, String> config = loadShopConfig();
        ShopStatusVo status = new ShopStatusVo();
        status.setPaused(Boolean.parseBoolean(config.getOrDefault(CONFIG_PAUSED, Boolean.FALSE.toString())));
        status.setNotice(config.get(CONFIG_NOTICE));
        return status;
    }

    private boolean isPaused() {
        return Boolean.parseBoolean(loadShopConfig().getOrDefault(CONFIG_PAUSED, Boolean.FALSE.toString()));
    }

    private Map<String, String> loadShopConfig() {
        return shopConfigMapper.selectList(null).stream()
                .collect(Collectors.toMap(ShopConfig::getConfigKey, ShopConfig::getConfigValue, (a, b) -> b));
    }

    /**
     * 商品 → 适用的规格组，按规格组 sort_order 升序。
     */
    private Map<Long, List<SpecGroup>> loadSpecGroups(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProductSpecGroup> relations = productSpecGroupMapper.selectList(
                new LambdaQueryWrapper<ProductSpecGroup>().in(ProductSpecGroup::getProductId, productIds));
        Set<Long> groupIds = relations.stream().map(ProductSpecGroup::getGroupId).collect(Collectors.toSet());
        if (groupIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, SpecGroup> groupById = specGroupMapper.selectList(new LambdaQueryWrapper<SpecGroup>()
                        .in(SpecGroup::getId, groupIds)
                        .eq(SpecGroup::getEnabled, ENABLED)
                        .orderByAsc(SpecGroup::getSortOrder, SpecGroup::getId))
                .stream()
                .collect(Collectors.toMap(SpecGroup::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        Map<Long, List<SpecGroup>> result = new LinkedHashMap<>();
        for (ProductSpecGroup relation : relations) {
            SpecGroup group = groupById.get(relation.getGroupId());
            if (group != null) {
                result.computeIfAbsent(relation.getProductId(), key -> new ArrayList<>()).add(group);
            }
        }
        return result;
    }

    /**
     * 规格组 → 启用中的规格项，按 sort_order 升序。
     */
    private Map<Long, List<SpecOption>> loadSpecOptions(Set<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return specOptionMapper.selectList(new LambdaQueryWrapper<SpecOption>()
                        .in(SpecOption::getGroupId, groupIds)
                        .eq(SpecOption::getEnabled, ENABLED)
                        .orderByAsc(SpecOption::getSortOrder, SpecOption::getId))
                .stream()
                .collect(Collectors.groupingBy(SpecOption::getGroupId, LinkedHashMap::new, Collectors.toList()));
    }

    private ProductVo toProductVo(Product product, List<SpecGroup> groups, Map<Long, List<SpecOption>> optionsByGroup) {
        ProductVo vo = new ProductVo();
        vo.setId(product.getId());
        vo.setName(product.getName());
        vo.setDescription(product.getDescription());
        vo.setImageKey(product.getImageKey());
        vo.setImageUrl(buildImageUrl(product.getImageKey()));
        vo.setBasePrice(MoneyUtils.format(product.getBasePrice()));
        vo.setSpecGroups(groups.stream()
                .map(group -> toSpecGroupVo(group, optionsByGroup.getOrDefault(group.getId(), Collections.emptyList())))
                .collect(Collectors.toList()));
        return vo;
    }

    private SpecGroupVo toSpecGroupVo(SpecGroup group, List<SpecOption> options) {
        SpecGroupVo vo = new SpecGroupVo();
        vo.setCode(group.getCode());
        vo.setName(group.getName());
        vo.setMultiSelect(group.getMultiSelect() != null && group.getMultiSelect() == 1);
        vo.setOptions(options.stream().map(this::toSpecOptionVo).collect(Collectors.toList()));
        return vo;
    }

    private SpecOptionVo toSpecOptionVo(SpecOption option) {
        SpecOptionVo vo = new SpecOptionVo();
        vo.setId(option.getId());
        vo.setName(option.getName());
        vo.setPriceDelta(MoneyUtils.format(option.getPriceDelta()));
        return vo;
    }

    private String buildImageUrl(String imageKey) {
        return StringUtils.hasText(imageKey) ? FILE_URL_PREFIX + imageKey : null;
    }
}
