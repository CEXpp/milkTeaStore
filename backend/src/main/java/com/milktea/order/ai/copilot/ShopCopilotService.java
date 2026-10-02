package com.milktea.order.ai.copilot;

import com.milktea.order.ai.ShopCopilotAssistant;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 店长 Copilot 服务（T67，W07）。
 *
 * <p><b>降级不影响报表</b>（验收项）：模型不可用时本服务只回一条提示，
 * 不经手任何统计数据——报表走的是独立的 {@code StatsController}，
 * 与本服务没有共享状态，因此「AI 挂了报表照常」不靠兜底逻辑保证，
 * 而是两条链路本来就不相交。</p>
 *
 * <p><b>数字可复现</b>（验收项）：{@code tables} 里放的是工具返回的<b>原文</b>，
 * 而工具直接委托 {@code StatsService}（与报表同一实现）。所以「AI 说的数字能在
 * 统计接口复现」是同源的必然，不需要人工比对。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopCopilotService {

    /** 工具名 → 面板标题（折叠面板的抬头，让店长知道展开的是什么）。 */
    private static final java.util.Map<String, String> TOOL_TITLES = java.util.Map.of(
            "dailySummary", "当日经营概览",
            "trend", "近 N 日趋势",
            "ranking", "商品销量排行",
            "orderFlow", "订单流水明细");

    private final ShopCopilotAssistant shopCopilotAssistant;

    /**
     * 一轮经营问答。
     *
     * @param conversationId 会话标识，空则新建（多轮追问时前端回传）
     * @param question       店长的自然语言提问
     * @return 结论 + 原始数据表格；模型不可用时降级为纯提示
     * @throws BusinessException 1001 提问为空
     */
    public ShopCopilotVo ask(String conversationId, String question) {
        if (!StringUtils.hasText(question)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "请输入要问的问题");
        }
        String sessionId = StringUtils.hasText(conversationId) ? conversationId.trim() : UUID.randomUUID().toString();

        CopilotTrace trace = new CopilotTrace();
        String conclusion;
        try {
            // 轨迹绑定在 ScopedValue 上：助手是单例、被多个店长并发共用，
            // 存实例字段会串号（A 店长看到 B 店长的查询）
            conclusion = CopilotTrace.CURRENT
                    .where(CopilotTrace.CURRENT, trace)
                    .call(() -> shopCopilotAssistant.chat(sessionId, question.trim()));
        } catch (Exception e) {
            // 模型不可用：降级提示，但**不编造结论**（编一个「今天生意不错」比不回答更糟）
            log.warn("[T67] 店长 Copilot 模型调用失败，降级为提示：{}", e.getMessage());
            return new ShopCopilotVo(
                    sessionId,
                    null,
                    true,
                    "AI 暂时不可用，暂时无法解读数据。经营报表与统计接口不受影响，可在「账台统计」页正常查看。",
                    false,
                    List.of());
        }

        List<ShopCopilotVo.ToolResult> tables = new ArrayList<>(trace.calls().size());
        for (CopilotTrace.ToolCall call : trace.calls()) {
            tables.add(new ShopCopilotVo.ToolResult(
                    call.tool(),
                    TOOL_TITLES.getOrDefault(call.tool(), call.tool()),
                    call.args(),
                    call.result()));
        }

        boolean rejected = trace.hasRejected();
        String tip = null;
        if (rejected) {
            // 出现过越权尝试：明确告知，而不是让店长以为操作成功了
            tip = "该操作涉及修改数据，AI 只有查询权限，请到对应管理页面手动完成。";
        } else if (tables.isEmpty()) {
            tip = "本轮没有查询数据，仅作说明性回答。";
        }

        log.info("[T67] copilot answered session={} tools={} rejected={}",
                sessionId, tables.size(), rejected);
        return new ShopCopilotVo(sessionId, conclusion, false, tip, rejected, tables);
    }
}