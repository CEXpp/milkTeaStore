package com.milktea.order.queue.service;

import com.milktea.order.queue.config.QueuePaceProperties;
import com.milktea.order.queue.vo.QueuePaceVo;
import com.milktea.order.queue.vo.QueueSnapshotVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 动态接单节奏（T48，W18）。
 *
 * <h2>做什么</h2>
 * <p>把 T46 的全店队列快照与两个杯数阈值比较，得出「正常 / 偏忙 / 拥挤」三档，并把**建议**
 * 告诉店长（看板横幅）。档位越高，顾客端越应看到更长的预估等待——这就是「动态展示预估出餐时间
 * 以调节接单节奏」的落点：靠信息透明让顾客自己决定要不要现在下单。</p>
 *
 * <h2>明确不做什么</h2>
 * <ul>
 *   <li><b>不自动暂停接单</b>（任务卡验收项）：本服务只读，不调用 4.6 的暂停开关，
 *       也不写任何表。暂停永远由店长在顶栏开关上手动作出。</li>
 *   <li><b>不拒绝任何订单</b>：系统不产生「因队列满而拒单」的记录，因此既不改变 6.5 统计口径
 *       （没有新的口径输入），也不留下需要解释的失败单据。</li>
 *   <li><b>不新增统计指标</b>：只消费 T46 已有的队列快照。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueuePaceService {

    private final QueueEstimateService queueEstimateService;
    private final QueuePaceProperties properties;

    /**
     * 计算当前接单节奏与建议。
     *
     * @return 档位 + 队列压力 + 生效阈值 + 建议文案（只读，不产生任何副作用）
     */
    public QueuePaceVo pace() {
        QueueSnapshotVo snapshot = queueEstimateService.snapshot();
        long cups = snapshot.totalCups();
        String level = resolveLevel(cups);
        boolean suggestPause = QueuePaceVo.LEVEL_OVERLOAD.equals(level);
        return new QueuePaceVo(level, cups,
                snapshot.etaMinutes(), snapshot.etaLow(), snapshot.etaHigh(),
                properties.busyCups(), properties.overloadCups(),
                suggestPause, suggestionFor(level, cups, snapshot.etaMinutes()), snapshot.computedAt());
    }

    /** 档位判定：达到拥挤阈值 → OVERLOAD；达到偏忙阈值 → BUSY；否则 NORMAL。 */
    private String resolveLevel(long cups) {
        if (cups >= properties.overloadCups()) {
            return QueuePaceVo.LEVEL_OVERLOAD;
        }
        if (cups >= properties.busyCups()) {
            return QueuePaceVo.LEVEL_BUSY;
        }
        return QueuePaceVo.LEVEL_NORMAL;
    }

    private String suggestionFor(String level, long cups, int etaMinutes) {
        return switch (level) {
            case QueuePaceVo.LEVEL_OVERLOAD -> "队列已达 " + cups + " 杯（阈值 " + properties.overloadCups()
                    + "），此刻新单预计等待 " + etaMinutes + " 分钟。建议暂停接单；系统不会自动暂停，请自行决定。";
            case QueuePaceVo.LEVEL_BUSY -> "队列偏长（" + cups + " 杯），此刻新单预计等待 " + etaMinutes
                    + " 分钟。顾客端会看到该预估，可按需提示顾客稍后再来。";
            default -> "接单节奏正常，队列 " + cups + " 杯，预计等待 " + etaMinutes + " 分钟。";
        };
    }
}
