package com.milktea.order.ai.stream;

import com.milktea.order.ai.dto.AiChatRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 顾客端 AI 流式对话端点（SSE）。
 *
 * <p>与非流式的 {@code POST /api/customer/ai/chat} 并存：本端点提供「先出文字、后出卡」的
 * 低首字延迟体验，非流式端点<b>保持不变</b>，作为前端在不支持分块接收时的降级出口
 * （微信开发者工具低版本或真机异常时自动回落）。</p>
 *
 * <h2>为什么是 POST + SSE 而不是原生 EventSource</h2>
 * <p>浏览器原生 {@code EventSource} 只能发 GET 且不能带请求头，因此既有订单状态通道
 * （{@code GET /orders/events}）才需要「令牌放查询参数」的回落。而本端点由小程序的
 * {@code uni.request(enableChunked: true)} 消费——它<b>能发 POST、能带 Authorization 头</b>，
 * 于是不需要把令牌放进 URL（令牌进 URL 会被写进访问日志，是能避免就避免的泄露面）。</p>
 *
 * <h2>charset 必须显式声明</h2>
 * <p>流式内容里是中文。若只声明 {@code text/event-stream}，Spring 的字符串转换器会退回
 * ISO-8859-1，中文会变成乱码。故这里把 {@code charset=UTF-8} 写死在 produces 里——
 * 它不是可有可无的装饰，而是「中文能不能正常显示」的开关。</p>
 */
@RestController
@RequestMapping("/api/customer/ai")
@RequiredArgsConstructor
public class AiStreamController {

    private final AiStreamOrchestrator aiStreamOrchestrator;

    /**
     * POST /api/customer/ai/chat/stream —— 流式对话。
     *
     * <p>响应为 {@code text/event-stream}，事件类型见 {@link AiStreamEvent}。
     * 鉴权失败（401）由 {@code JwtAuthenticationFilter} 以普通 JSON 返回，
     * 因此前端必须先看 HTTP 状态码，再决定是解析事件流还是走错误分支。</p>
     */
    @PostMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@Valid @RequestBody AiChatRequest request) {
        return aiStreamOrchestrator.stream(request);
    }
}
