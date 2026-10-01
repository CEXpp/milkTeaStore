package com.milktea.order.queue.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.queue.service.QueueEstimateService;
import com.milktea.order.queue.service.QueuePaceService;
import com.milktea.order.queue.vo.QueuePaceVo;
import com.milktea.order.queue.vo.QueueSnapshotVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端队列预估接口（T46，LLD 11.6）。
 *
 * <ul>
 *   <li>{@code GET /api/admin/queue/estimate}：全店队列快照——队列分态计数（订单数 / 件数）、
 *       全店等待预估与误差区间、生效参数与计算时间戳。</li>
 * </ul>
 *
 * <p>供 T48（动态接单节奏：按队列压力建议暂停 / 恢复）与 T69（爆单预测与备料建议）取数；
 * 只读接口，不改变任何订单状态（LLD 11.6「预估为只读分析」）。</p>
 */
@RestController
@RequestMapping("/api/admin/queue")
@RequiredArgsConstructor
public class AdminQueueController {

    private final QueueEstimateService queueEstimateService;
    private final QueuePaceService queuePaceService;

    /** 全店队列快照（等价于「此刻新来一单」的等待预估）。 */
    @GetMapping("/estimate")
    public R<QueueSnapshotVo> estimate() {
        return R.ok(queueEstimateService.snapshot());
    }

    /**
     * 动态接单节奏（T48）：队列压力档位（NORMAL / BUSY / OVERLOAD）+ 给店长的建议。
     *
     * <p><b>只读</b>：本接口不写任何表、不改 4.6 的暂停开关——超阈值时只返回
     * {@code suggestPause=true} 供看板提示，是否暂停由店长自行决定（任务卡验收项
     * 「任何情况下不自动暂停接单」）。</p>
     */
    @GetMapping("/pace")
    public R<QueuePaceVo> pace() {
        return R.ok(queuePaceService.pace());
    }
}
