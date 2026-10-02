package com.milktea.order.order.controller;

import com.milktea.order.common.result.PageResult;
import com.milktea.order.common.result.R;
import com.milktea.order.order.service.BrewArchiveService;
import com.milktea.order.order.vo.BrewArchiveVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每一杯茶的制作档案接口（T59，W21，商家 JWT——路径属 {@code /api/admin/**} 鉴权矩阵）。
 *
 * <ul>
 *   <li>{@code GET /api/admin/brew-archives}：按日期 / 商品 / 规格 / 状态检索（分页）；</li>
 *   <li>{@code GET /api/admin/brew-archives/{orderItemId}}：单杯完整档案。</li>
 * </ul>
 *
 * <p><b>整个控制器只有两个 {@code GET}</b>——这正是任务卡「档案只读、不可篡改」与验收项
 * 「无编辑入口」的落地方式：后端<b>不存在</b>任何修改档案的接口形状，前端即便想改也无处可调。
 * 与之配套，档案的数据全部取自下单瞬间的快照列，商品改价改规格不会回溯（AC-09）。</p>
 *
 * <p><b>边界</b>：T59 明确「只做自查用的只读追溯，不做监管对接、不做合规承诺」。本控制器
 * 相应地不返回任何顾客身份信息，也不提供导出与外部推送——监管化留待后续单独立项。</p>
 */
@RestController
@RequestMapping("/api/admin/brew-archives")
@RequiredArgsConstructor
public class BrewArchiveController {

    private final BrewArchiveService brewArchiveService;

    /**
     * 检索制作档案。
     *
     * <p>日期三选一：{@code date=2026-10-02}（单日）、{@code from=&to=}（左闭右开区间）、
     * 全空（不限日期）。商品可传 {@code productId} 精确或 {@code productName} 模糊；
     * {@code spec} 匹配规格组名 / 选项名 / 加料名。</p>
     */
    @GetMapping
    public R<PageResult<BrewArchiveVo>> archives(
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "productId", required = false) Long productId,
            @RequestParam(value = "productName", required = false) String productName,
            @RequestParam(value = "spec", required = false) String spec,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return R.ok(brewArchiveService.archives(date, from, to, productId, productName, spec, status, page, size));
    }

    /** 单杯完整档案：六个时间戳 + 规格快照 + 金额 + 备注 + 作废原因，一次给全（还原一杯的一生）。 */
    @GetMapping("/{orderItemId}")
    public R<BrewArchiveVo> archive(@PathVariable("orderItemId") Long orderItemId) {
        return R.ok(brewArchiveService.archive(orderItemId));
    }
}