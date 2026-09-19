package com.milktea.order.product.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.product.dto.SpecGroupSaveRequest;
import com.milktea.order.product.dto.SpecOptionSaveRequest;
import com.milktea.order.product.service.AdminSpecService;
import com.milktea.order.product.vo.AdminSpecGroupVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端规格模板管理（T22，LLD 3.5.2，商家 JWT）。
 *
 * <ul>
 *   <li>{@code GET /api/admin/spec-groups}：全部规格组及选项（含停用项）；</li>
 *   <li>{@code POST /api/admin/spec-groups/{gid}/options}：组内新增选项；</li>
 *   <li>{@code PUT /api/admin/spec-options/{id}}：编辑选项（含价差；enabled=0 等效停用）；</li>
 *   <li>{@code PUT /api/admin/spec-groups/{gid}}：编辑组（名称/排序/启用）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminSpecController {

    private final AdminSpecService adminSpecService;

    @GetMapping("/spec-groups")
    public R<List<AdminSpecGroupVo>> listGroups() {
        return R.ok(adminSpecService.listGroups());
    }

    @PostMapping("/spec-groups/{gid}/options")
    public R<Long> createOption(@PathVariable("gid") Long groupId,
                                @Valid @RequestBody SpecOptionSaveRequest request) {
        return R.ok(adminSpecService.createOption(groupId, request));
    }

    @PutMapping("/spec-options/{id}")
    public R<Void> updateOption(@PathVariable("id") Long id,
                                @Valid @RequestBody SpecOptionSaveRequest request) {
        adminSpecService.updateOption(id, request);
        return R.ok(null);
    }

    @PutMapping("/spec-groups/{gid}")
    public R<Void> updateGroup(@PathVariable("gid") Long groupId,
                               @Valid @RequestBody SpecGroupSaveRequest request) {
        adminSpecService.updateGroup(groupId, request);
        return R.ok(null);
    }
}
