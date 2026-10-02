package com.milktea.order.ai.copilot;

import com.milktea.order.common.result.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 店长 Copilot 接口（T67，W07，商家 JWT——路径属 {@code /api/admin/**} 鉴权矩阵）。
 *
 * <p>{@code POST /api/admin/copilot/chat}：body {{@code conversationId?, question}}。</p>
 *
 * <p><b>只有一个接口</b>：Copilot 是「问一句、答一句」的对话，没有第二条链路
 * ——尤其没有「让 AI 执行某个操作」的接口。这是刻意的：写操作的入口只存在于
 * 各自的业务控制器（商品、店铺、订单），AI 域不提供任何通往它们的通道。</p>
 */
@RestController
@RequestMapping("/api/admin/copilot")
@RequiredArgsConstructor
public class ShopCopilotController {

    private final ShopCopilotService shopCopilotService;

    /** 一轮经营问答：返回中文结论 + 可折叠的原始数据表格（供核对）。 */
    @PostMapping("/chat")
    public R<ShopCopilotVo> chat(@Valid @RequestBody CopilotChatRequest request) {
        return R.ok(shopCopilotService.ask(request.getConversationId(), request.getQuestion()));
    }

    /** 对话请求：conversationId 空则新建（多轮追问时回传上一轮的值）。 */
    public static class CopilotChatRequest {

        /** 会话标识；空表示新会话 */
        private String conversationId;

        /** 店长的自然语言提问 */
        @NotBlank(message = "请输入要问的问题")
        private String question;

        public String getConversationId() {
            return conversationId;
        }

        public void setConversationId(String conversationId) {
            this.conversationId = conversationId;
        }

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }
    }
}