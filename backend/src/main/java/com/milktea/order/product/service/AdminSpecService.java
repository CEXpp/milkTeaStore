package com.milktea.order.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.product.dto.SpecGroupSaveRequest;
import com.milktea.order.product.dto.SpecOptionSaveRequest;
import com.milktea.order.product.entity.SpecGroup;
import com.milktea.order.product.entity.SpecOption;
import com.milktea.order.product.mapper.SpecGroupMapper;
import com.milktea.order.product.mapper.SpecOptionMapper;
import com.milktea.order.product.vo.AdminSpecGroupVo;
import com.milktea.order.product.vo.AdminSpecOptionVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商家端规格模板管理服务（T22，LLD 3.5.2）。
 *
 * <ul>
 *   <li>{@code GET /api/admin/spec-groups}：四组模板（杯型/温度/甜度/加料）及全部选项（含停用项）；</li>
 *   <li>{@code POST /api/admin/spec-groups/{gid}/options}：组内新增选项；</li>
 *   <li>{@code PUT /api/admin/spec-options/{id}}：编辑选项（含价差与启停）；</li>
 *   <li>{@code PUT /api/admin/spec-groups/{gid}}：编辑组（名称/排序/启用）。</li>
 * </ul>
 *
 * <p>规格组为系统模板不提供删除；停用选项 / 分组后顾客端菜单立即不可见
 * （{@link MenuService} 只取 enabled=1）。重名冲突返回 1001。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSpecService {

    private static final int ENABLED = 1;

    private final SpecGroupMapper specGroupMapper;
    private final SpecOptionMapper specOptionMapper;

    /**
     * 全部规格组及选项：组按 sortOrder 升序，选项同组内按 sortOrder 升序；停用项同样返回（管理页可见）。
     */
    public List<AdminSpecGroupVo> listGroups() {
        List<SpecGroup> groups = specGroupMapper.selectList(new LambdaQueryWrapper<SpecGroup>()
                .orderByAsc(SpecGroup::getSortOrder, SpecGroup::getId));
        if (groups.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> groupIds = groups.stream().map(SpecGroup::getId).toList();
        Map<Long, List<SpecOption>> optionsByGroup = specOptionMapper.selectList(
                        new LambdaQueryWrapper<SpecOption>()
                                .in(SpecOption::getGroupId, groupIds)
                                .orderByAsc(SpecOption::getSortOrder, SpecOption::getId))
                .stream()
                .collect(Collectors.groupingBy(SpecOption::getGroupId));

        List<AdminSpecGroupVo> list = new ArrayList<>(groups.size());
        for (SpecGroup group : groups) {
            AdminSpecGroupVo vo = new AdminSpecGroupVo();
            vo.setId(group.getId());
            vo.setCode(group.getCode());
            vo.setName(group.getName());
            vo.setMultiSelect(group.getMultiSelect() != null && group.getMultiSelect() == 1);
            vo.setSortOrder(group.getSortOrder());
            vo.setEnabled(group.getEnabled());
            vo.setOptions(optionsByGroup.getOrDefault(group.getId(), List.of()).stream()
                    .map(this::toOptionVo)
                    .collect(Collectors.toList()));
            list.add(vo);
        }
        return list;
    }

    /** 组内新增规格选项：name / priceDelta 必填，同组内重名返回 1001，默认启用。 */
    public Long createOption(Long groupId, SpecOptionSaveRequest request) {
        requireGroup(groupId);
        if (!StringUtils.hasText(request.getName())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "规格项名称不能为空");
        }
        if (request.getPriceDelta() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "价差不能为空");
        }
        String name = request.getName().trim();
        requireOptionNameAvailable(groupId, name, null);

        SpecOption option = new SpecOption();
        option.setGroupId(groupId);
        option.setName(name);
        option.setPriceDelta(request.getPriceDelta());
        option.setSortOrder(request.getSortOrder() == null ? nextOptionSortOrder(groupId) : request.getSortOrder());
        option.setEnabled(request.getEnabled() == null ? ENABLED : request.getEnabled());
        specOptionMapper.insert(option);
        log.info("[T22] spec option created id={} groupId={} name={}", option.getId(), groupId, name);
        return option.getId();
    }

    /** 编辑规格选项：仅覆盖非 null 字段（enabled=0 等效停用，顾客端不可选）。 */
    public void updateOption(Long optionId, SpecOptionSaveRequest request) {
        SpecOption option = requireOption(optionId);
        if (request.getName() != null) {
            String name = request.getName().trim();
            if (!StringUtils.hasText(name)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "规格项名称不能为空");
            }
            requireOptionNameAvailable(option.getGroupId(), name, optionId);
            option.setName(name);
        }
        if (request.getPriceDelta() != null) {
            option.setPriceDelta(request.getPriceDelta());
        }
        if (request.getSortOrder() != null) {
            option.setSortOrder(request.getSortOrder());
        }
        if (request.getEnabled() != null) {
            option.setEnabled(request.getEnabled());
        }
        specOptionMapper.updateById(option);
        log.info("[T22] spec option updated id={} enabled={}", optionId, option.getEnabled());
    }

    /** 编辑规格组：名称 / 排序 / 启用（停用后整组从顾客端菜单消失）。 */
    public void updateGroup(Long groupId, SpecGroupSaveRequest request) {
        SpecGroup group = requireGroup(groupId);
        if (request.getName() != null) {
            String name = request.getName().trim();
            if (!StringUtils.hasText(name)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "规格组名称不能为空");
            }
            group.setName(name);
        }
        if (request.getSortOrder() != null) {
            group.setSortOrder(request.getSortOrder());
        }
        if (request.getEnabled() != null) {
            group.setEnabled(request.getEnabled());
        }
        specGroupMapper.updateById(group);
        log.info("[T22] spec group updated id={} enabled={}", groupId, group.getEnabled());
    }

    private AdminSpecOptionVo toOptionVo(SpecOption option) {
        AdminSpecOptionVo vo = new AdminSpecOptionVo();
        vo.setId(option.getId());
        vo.setName(option.getName());
        vo.setPriceDelta(MoneyUtils.format(option.getPriceDelta()));
        vo.setSortOrder(option.getSortOrder());
        vo.setEnabled(option.getEnabled());
        return vo;
    }

    private SpecGroup requireGroup(Long groupId) {
        SpecGroup group = specGroupMapper.selectById(groupId);
        if (group == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "规格组不存在");
        }
        return group;
    }

    private SpecOption requireOption(Long optionId) {
        SpecOption option = specOptionMapper.selectById(optionId);
        if (option == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "规格项不存在");
        }
        return option;
    }

    /** 同组内选项名称唯一（不同组允许同名，如「少冰」与「少糖」无关）。 */
    private void requireOptionNameAvailable(Long groupId, String name, Long excludeId) {
        long duplicated = specOptionMapper.selectCount(new LambdaQueryWrapper<SpecOption>()
                .eq(SpecOption::getGroupId, groupId)
                .eq(SpecOption::getName, name)
                .ne(Objects.nonNull(excludeId), SpecOption::getId, excludeId));
        if (duplicated > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "该规格组下已存在同名选项");
        }
    }

    private int nextOptionSortOrder(Long groupId) {
        List<SpecOption> options = specOptionMapper.selectList(new LambdaQueryWrapper<SpecOption>()
                .eq(SpecOption::getGroupId, groupId)
                .orderByDesc(SpecOption::getSortOrder)
                .last("LIMIT 1"));
        int max = options.isEmpty() || options.get(0).getSortOrder() == null ? 0 : options.get(0).getSortOrder();
        return max + 1;
    }
}
