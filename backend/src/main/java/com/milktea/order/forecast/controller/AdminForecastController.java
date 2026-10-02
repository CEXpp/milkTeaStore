package com.milktea.order.forecast.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.forecast.service.DemandForecastService;
import com.milktea.order.forecast.vo.DemandForecastVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 爆单预测接口（T69，D3b · W09，商家 JWT）。
 *
 * <p>{@code GET /api/admin/forecast}：返回未来一个窗口的预测单量与备料建议。</p>
 *
 * <p><b>这个接口只读</b>：没有「执行建议」对应的第二个接口，也永远不会有——
 * 「建议权在系统，决定权在人」。暂停接单、上下架、改价各自有它们自己的业务接口，
 * 预测域不提供通往它们的任何通道。</p>
 */
@RestController
@RequestMapping("/api/admin/forecast")
@RequiredArgsConstructor
public class AdminForecastController {

    private final DemandForecastService demandForecastService;

    /**
     * 预测未来 15 分钟单量并给备料建议。
     *
     * <p>预测未启用时 {@code enabled=false}，与「算了但正常」在响应里可区分。</p>
     */
    @GetMapping
    public R<DemandForecastVo> forecast() {
        return R.ok(demandForecastService.forecast());
    }
}