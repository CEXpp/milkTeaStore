<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  aiChat,
  confirmAiOrder,
  CODE_AI_UNAVAILABLE,
  CODE_DRAFT_EMPTY,
  CODE_RATE_LIMITED,
  type AiDraft,
  type AiDraftItem,
  type AiFallback
} from '@/api/ai'
import { payOrder } from '@/api/order'
import { ApiError } from '@/utils/request'
import MessageBubble from '@/components/ai/MessageBubble.vue'
import DraftCard from '@/components/ai/DraftCard.vue'

/**
 * AI 点单页（T33，SRS 5.1 / LLD 3.4）：
 * - 聊天式 UI：气泡列表 + 底部输入框；语音靠输入法麦克风（页面提示「按住键盘麦克风说话」）；
 * - TEXT / CARD 两类消息：CARD 在气泡下方追加草稿卡片（逐项 + 合计 + 行内改量/删除 + 立即支付）；
 * - 「立即支付」→ confirm-order → pay 两连发 → 跳订单详情页（付钱永远由用户触发，AI 不代付）；
 * - 异常分支：1008 降级横幅 + 「去菜单点单」；1009 限流软提示；会话被后端判过期时提示重新点单。
 *
 * 行内改量/删除之所以发一句自然语言而不是直接改本地数据：草稿只由后端 AI 工具集改写，
 * 且 confirm-order 用的就是服务端草稿——本地改会让卡片显示与实付金额不一致。
 */

/** 冷启动欢迎语与示例指令 */
const WELCOME = '我是点单员小茶～想喝点什么？直接说就行，比如「来杯珍珠奶茶少冰半糖」。'
const EXAMPLES = ['来杯珍珠奶茶少冰半糖', '两杯珍珠奶茶，一杯柠檬茶', '不要珍珠奶茶了']

/** 1008 降级话术的本地兜底（正常应取后端 data.fallbackText，LLD 3.4） */
const DEFAULT_FALLBACK = 'AI 助手休息中，请先到菜单手动点单'

interface ChatMessage {
  id: number
  role: 'user' | 'assistant'
  text: string
  /** CARD 形态下挂载的草稿单 */
  draft?: AiDraft | null
}

const messages = ref<ChatMessage[]>([])
const input = ref('')
const sessionId = ref<string | null>(null)
const sending = ref(false)
const paying = ref(false)
const fallbackText = ref<string | null>(null)
/** scroll-view 的锚点（最后一条消息的 id），用于自动滚到底部 */
const lastAnchor = ref('')

let sequence = 0

/** 只有欢迎语时展示示例指令，用户开口后收起 */
const showExamples = computed(() => messages.value.filter((item) => item.role === 'user').length === 0)

onShow(() => {
  if (messages.value.length === 0) {
    appendMessage({ role: 'assistant', text: WELCOME })
  }
})

function appendMessage(message: Omit<ChatMessage, 'id'>): void {
  sequence += 1
  messages.value.push({ id: sequence, ...message })
  lastAnchor.value = `msg-${sequence}`
}

function goMenu(): void {
  uni.switchTab({ url: '/pages/menu/index' })
}

/**
 * 发一轮对话。
 *
 * @param preset 传字符串则直接发送该内容（示例指令 / 行内操作翻译出的指令），否则取输入框
 */
async function send(preset?: string): Promise<void> {
  const content = (preset ?? input.value).trim()
  if (!content || sending.value) {
    return
  }
  input.value = ''
  fallbackText.value = null
  appendMessage({ role: 'user', text: content })

  sending.value = true
  const previousSessionId = sessionId.value
  try {
    const result = await aiChat(previousSessionId, content)
    if (previousSessionId && result.sessionId !== previousSessionId) {
      // 后端在会话过期时会新建会话（LLD 6.6 步骤 1），此时旧草稿已作废，提示重新点单
      uni.showToast({ title: '会话已超时，已重新开始点单', icon: 'none' })
    }
    sessionId.value = result.sessionId
    appendMessage({ role: 'assistant', text: result.text, draft: result.draft })
  } catch (error) {
    handleChatError(error)
  } finally {
    sending.value = false
  }
}

/** 对话失败分支：降级（1008）/ 限流（1009）/ 其它（request 层已弹提示，这里补一句保持对话连续）。 */
function handleChatError(error: unknown): void {
  if (error instanceof ApiError) {
    if (error.code === CODE_AI_UNAVAILABLE) {
      const fallback = (error.data as AiFallback | undefined)?.fallbackText
      fallbackText.value = fallback || DEFAULT_FALLBACK
      return
    }
    if (error.code === CODE_RATE_LIMITED) {
      appendMessage({ role: 'assistant', text: '问得有点快啦，歇一分钟再聊～' })
      return
    }
  }
  // 兜底文案必须说「服务出问题了」，不能说「没听清」——后者会让人以为是模型听不懂，
  // 而实际情况通常是后端异常（如数据库死锁 / 500），本轮模型压根没被调起来。
  appendMessage({ role: 'assistant', text: '抱歉，刚才点单服务出了点问题，请再试一次～' })
}

/** 数量增减：翻译成一句指令交给对话链路，由模型改写服务端草稿。 */
function handleAdjust(item: AiDraftItem, delta: number): void {
  const next = item.quantity + delta
  if (next < 1) {
    handleRemove(item)
    return
  }
  void send(`把「${item.productName}」改成 ${next} 杯`)
}

/** 行内删除：二次确认后翻译成指令。 */
function handleRemove(item: AiDraftItem): void {
  uni.showModal({
    title: '删除这一项',
    content: `确定不要「${item.productName}」了吗？`,
    success: (res) => {
      if (res.confirm) {
        void send(`不要「${item.productName}」了`)
      }
    }
  })
}

/**
 * 立即支付：confirm-order 转正式订单 → 立刻连发 pay 完成支付闭环 → 跳订单详情页。
 *
 * AI 侧没有支付工具，这一步必须由用户点按触发（SRS 约束三原则第三条）。
 */
async function handlePay(): Promise<void> {
  const currentSessionId = sessionId.value
  if (!currentSessionId || paying.value) {
    return
  }
  paying.value = true
  try {
    const confirmed = await confirmAiOrder(currentSessionId)
    const paid = await payOrder(confirmed.orderId)
    uni.showToast({ title: `支付成功，取餐码 ${paid.pickupCode}`, icon: 'none' })
    setTimeout(() => {
      uni.navigateTo({ url: `/pages/order-detail/index?id=${confirmed.orderId}` })
    }, 800)
  } catch (error) {
    if (error instanceof ApiError && error.code === CODE_DRAFT_EMPTY) {
      appendMessage({ role: 'assistant', text: '草稿单已经空了，重新点一杯吧～' })
    }
    // 其它错误（1002 商品下架 / 1006 暂停接单 / 1004 状态冲突）已由 request 层直显
  } finally {
    paying.value = false
  }
}
</script>

<template>
  <view class="ai-page">
    <!-- 1008 降级横幅（AC-13）：模型不可用时引导回菜单手动点单 -->
    <view v-if="fallbackText" class="fallback-banner">
      <text class="fallback-text">{{ fallbackText }}</text>
      <view class="fallback-btn" @tap="goMenu">去菜单点单</view>
    </view>

    <scroll-view class="msg-list" scroll-y :scroll-into-view="lastAnchor" :scroll-with-animation="true">
      <view v-for="message in messages" :id="`msg-${message.id}`" :key="message.id" class="msg-item">
        <MessageBubble :role="message.role" :text="message.text" />
        <DraftCard
          v-if="message.draft"
          :draft="message.draft"
          :busy="sending || paying"
          @change="handleAdjust"
          @remove="handleRemove"
          @pay="handlePay"
        />
      </view>
      <view v-if="sending" class="typing">小茶正在想…</view>
    </scroll-view>

    <!-- 冷启动示例指令：说一句就能点单 -->
    <view v-if="showExamples" class="examples">
      <view v-for="example in EXAMPLES" :key="example" class="example-chip" @tap="send(example)">
        {{ example }}
      </view>
    </view>

    <view class="input-bar">
      <input
        v-model="input"
        class="input"
        placeholder="说出你想喝的，如「来杯珍珠奶茶少冰半糖」"
        confirm-type="send"
        @confirm="send()"
      />
      <view class="send-btn" :class="{ disabled: sending }" @tap="send()">发送</view>
    </view>
    <view class="voice-hint">可按键盘上的麦克风说话，语音会自动转成文字</view>
  </view>
</template>

<style scoped>
.ai-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f6f8;
}

.fallback-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 24rpx;
  background: #fdf6ec;
  border-bottom: 1rpx solid #f5dab1;
}

.fallback-text {
  flex: 1;
  font-size: 26rpx;
  color: #e6a23c;
}

.fallback-btn {
  flex-shrink: 0;
  margin-left: 16rpx;
  padding: 10rpx 24rpx;
  border-radius: 28rpx;
  background: #e6a23c;
  color: #fff;
  font-size: 24rpx;
}

.msg-list {
  flex: 1;
  padding: 24rpx;
  box-sizing: border-box;
}

.msg-item {
  margin-bottom: 8rpx;
}

.typing {
  padding: 8rpx 0 24rpx 80rpx;
  font-size: 26rpx;
  color: #909399;
}

.examples {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  padding: 0 24rpx 16rpx;
}

.example-chip {
  padding: 12rpx 20rpx;
  border-radius: 32rpx;
  background: #fff;
  font-size: 26rpx;
  color: #409eff;
}

.input-bar {
  display: flex;
  align-items: center;
  padding: 16rpx 24rpx;
  background: #fff;
  border-top: 1rpx solid #ebeef5;
}

.input {
  flex: 1;
  height: 72rpx;
  padding: 0 24rpx;
  border-radius: 36rpx;
  background: #f5f6f8;
  font-size: 28rpx;
}

.send-btn {
  flex-shrink: 0;
  margin-left: 16rpx;
  padding: 0 32rpx;
  height: 72rpx;
  border-radius: 36rpx;
  background: #409eff;
  color: #fff;
  font-size: 28rpx;
  line-height: 72rpx;
}

.send-btn.disabled {
  background: #a0cfff;
}

.voice-hint {
  padding: 0 24rpx 20rpx;
  background: #fff;
  font-size: 22rpx;
  color: #c0c4cc;
  text-align: center;
}
</style>
