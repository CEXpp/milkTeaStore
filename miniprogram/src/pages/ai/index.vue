<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow, onUnload } from '@dcloudio/uni-app'
import {
  aiChat,
  confirmAiOrder,
  CODE_AI_UNAVAILABLE,
  CODE_DRAFT_EMPTY,
  CODE_RATE_LIMITED,
  type AiDraft,
  type AiDraftItem,
  type AiChatResult
} from '@/api/ai'
import { chatStream, type AiStreamStage } from '@/api/aiStream'
import { isChunkedSupported, type SseHandle } from '@/utils/sse'
import { payOrder } from '@/api/order'
import { getMenu, type MenuProduct } from '@/api/menu'
import { ApiError } from '@/utils/request'
import { buildSpeechText } from '@/utils/speech'
import { useA11yStore } from '@/stores/a11y'
import { useSpeechStore } from '@/stores/speech'
import MessageBubble from '@/components/ai/MessageBubble.vue'
import DraftCard from '@/components/ai/DraftCard.vue'
import StageIndicator from '@/components/ai/StageIndicator.vue'
import IcpFooter from '@/components/IcpFooter.vue'

/**
 * AI 点单页（T33，SRS 5.1 / LLD 3.4 / T45 11.4）：
 * - 聊天式 UI：气泡列表 + 底部输入框；语音靠输入法麦克风（页面提示「按住键盘麦克风说话」）；
 * - TEXT / CARD 两类消息：CARD 在气泡下方追加草稿卡片（逐项 + 合计 + 行内改量/删除 + 立即支付）；
 * - 「立即支付」→ confirm-order → pay 两连发 → 跳订单详情页（付钱永远由用户触发，AI 不代付）；
 * - 异常分支：1008 降级横幅 + 「去菜单点单」；1009 限流软提示；会话被后端判过期时提示重新点单；
 * - 无障碍（T45，SRS 9.6「纯语音取餐」）：语音**输入**侧由系统输入法承担（无需代码），
 *   无障碍模式下页面强化语音提示与字号对比；语音**播报**（TTS）按 LLD 11.4 归属 T70。
 *
 * 行内改量/删除之所以发一句自然语言而不是直接改本地数据：草稿只由后端 AI 工具集改写，
 * 且 confirm-order 用的就是服务端草稿——本地改会让卡片显示与实付金额不一致。
 *
 * ## 本轮改造：流式（先出文字、后出卡）
 * 走 `POST /api/customer/ai/chat/stream`，事件契约见 {@link chatStream}：
 * - 承接话术逐段追加进助手气泡（首字延迟由它决定）；
 * - 阶段提示替换原来的「小茶正在想…」，由后端事件点亮；
 * - 草稿卡到达后挂到气泡下方并触发语音播报；
 * - **任何传输层故障都自动回退非流式接口**（`aiChat`），顾客不会因此点不了单。
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

const a11y = useA11yStore()
/** 语音播报开关（T70）：输出侧 TTS，不涉及输入侧 ASR（见 utils/speech.ts 的边界说明） */
const speech = useSpeechStore()
/** 在售商品（扁平）：规格纠错用（T71） */
const menuProducts = ref<MenuProduct[]>([])

const messages = ref<ChatMessage[]>([])
const input = ref('')
const sessionId = ref<string | null>(null)
const sending = ref(false)
const paying = ref(false)
const fallbackText = ref<string | null>(null)
/** 当前决策阶段（流式事件驱动；null 表示不在等待中） */
const stage = ref<AiStreamStage | null>(null)
/** scroll-view 的锚点（最后一条消息的 id），用于自动滚到底部 */
const lastAnchor = ref('')

/** 当前流式通道（页面卸载或重新提问时断开） */
let streamHandle: SseHandle | null = null
let sequence = 0

/** 只有欢迎语时展示示例指令，用户开口后收起 */
const showExamples = computed(() => messages.value.filter((item) => item.role === 'user').length === 0)

onShow(() => {
  // 语音播报能力探测（T70）：进页探一次即可，不在每轮对话里重复探测
  speech.init()
  // 规格纠错需要「有哪些规格可选」，进页拉一次菜单即可
  void loadMenuForSpec()
  if (messages.value.length === 0) {
    appendMessage({ role: 'assistant', text: WELCOME })
  }
})

onUnload(() => {
  // 离开页面就断开流：否则后台还在收事件，回来时会看到它继续往旧气泡里写字
  streamHandle?.abort()
  streamHandle = null
})

/** 在售商品（扁平化）：供草稿卡按名称反查规格组与可选项（T71）。失败静默，只影响纠错入口。 */
async function loadMenuForSpec(): Promise<void> {
  if (menuProducts.value.length) {
    return
  }
  try {
    const menu = await getMenu()
    menuProducts.value = menu.categories.flatMap((category) => category.products)
  } catch {
    menuProducts.value = []
  }
}

function appendMessage(message: Omit<ChatMessage, 'id'>): number {
  sequence += 1
  const id = sequence
  messages.value.push({ id, ...message })
  lastAnchor.value = `msg-${id}`
  return id
}

/**
 * 按 id 就地更新消息。
 *
 * <p>必须「按 id 找回数组里的那一项」再改：`ref([])` 里存的是深层响应式代理，
 * 直接改 push 进去的那个原始对象不会触发视图更新。</p>
 */
function patchMessage(id: number, patch: Partial<ChatMessage>): void {
  const target = messages.value.find((item) => item.id === id)
  if (target) {
    Object.assign(target, patch)
  }
}

function removeMessage(id: number): void {
  const index = messages.value.findIndex((item) => item.id === id)
  if (index >= 0) {
    messages.value.splice(index, 1)
  }
}

function mention(id: number): void {
  lastAnchor.value = `msg-${id}`
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
  stage.value = null
  const previousSessionId = sessionId.value
  // 助手气泡先建出来：流式增量要往里追加，否则第一段文字到达时才「凭空插入一条消息」
  const replyId = appendMessage({ role: 'assistant', text: '' })

  try {
    if (isChunkedSupported()) {
      await sendStreaming(previousSessionId, content, replyId)
    } else {
      // 基础库不支持分块接收：直接走非流式接口，行为与升级前逐字一致
      await sendOnce(previousSessionId, content, replyId)
    }
  } finally {
    sending.value = false
    stage.value = null
    streamHandle = null
    // 一个事件都没来（例如连接建立后被立刻断开）时不留空气泡
    const reply = messages.value.find((item) => item.id === replyId)
    if (reply && !reply.text && !reply.draft) {
      removeMessage(replyId)
    }
  }
}

/** 流式路径：事件驱动地填充助手气泡；传输层故障自动回退非流式。 */
async function sendStreaming(
  previousSessionId: string | null,
  content: string,
  replyId: number
): Promise<void> {
  let accumulated = ''
  let receivedAnyEvent = false
  let transportFailed = false

  try {
    const handle = await chatStream(previousSessionId, content, {
      onStage: (value) => {
        receivedAnyEvent = true
        stage.value = value
      },
      onDelta: (text) => {
        receivedAnyEvent = true
        accumulated += text
        patchMessage(replyId, { text: accumulated })
        mention(replyId)
      },
      onCard: (draft) => {
        receivedAnyEvent = true
        patchMessage(replyId, { draft })
        mention(replyId)
        // 播报内容与卡片同源（都取后端 draft），不会出现「念的和显示的不一样」
        speech.say(buildSpeechText(draft))
      },
      onDone: (payload) => {
        receivedAnyEvent = true
        applySession(payload.sessionId, previousSessionId)
      },
      onError: (payload) => {
        receivedAnyEvent = true
        applyStreamError(payload.code, payload.fallbackText)
      },
      onInterrupted: () => {
        transportFailed = true
      },
      onTransportError: () => {
        transportFailed = true
      }
    })
    streamHandle = handle
    // 等这一轮真正说完，再放开输入框
    await handle.completed
  } catch (error) {
    transportFailed = true
    console.error('流式通道建立失败', error)
  }

  if (!transportFailed) {
    return
  }

  // 传输层故障：一个事件都没收到时才回退（同一句话重发一次是可接受的代价），
  // 已经收到半截回答再重发会让模型把同一句话看两遍——那时只提示，不重试。
  if (!receivedAnyEvent) {
    stage.value = null
    await sendOnce(previousSessionId, content, replyId)
    return
  }
  appendMessage({ role: 'assistant', text: '刚才连接断了一下，麻烦再说一次～' })
}

/** 非流式路径：一次性拿完整回复（基础库不支持分块时的正常通道，也是流式故障时的回退）。 */
async function sendOnce(previousSessionId: string | null, content: string, replyId: number): Promise<void> {
  try {
    const result: AiChatResult = await aiChat(previousSessionId, content)
    applySession(result.sessionId, previousSessionId)
    patchMessage(replyId, { text: result.text, draft: result.draft })
    mention(replyId)
    if (result.replyType === 'CARD' && result.draft) {
      speech.say(buildSpeechText(result.draft))
    }
  } catch (error) {
    handleChatError(error)
  }
}

/** 会话标识落地：后端在会话过期时会新建，此时旧草稿已作废，需要提示重新点单。 */
function applySession(nextSessionId: string, previousSessionId: string | null): void {
  if (previousSessionId && nextSessionId !== previousSessionId) {
    uni.showToast({ title: '会话已超时，已重新开始点单', icon: 'none' })
  }
  sessionId.value = nextSessionId
}

/** 流式错误事件 → 既有错误分支（口径与非流式完全一致，避免两条链路两套话术）。 */
function applyStreamError(code: number, fallback: string | null): void {
  if (code === CODE_AI_UNAVAILABLE) {
    fallbackText.value = fallback || DEFAULT_FALLBACK
    return
  }
  if (code === CODE_RATE_LIMITED) {
    appendMessage({ role: 'assistant', text: '问得有点快啦，歇一分钟再聊～' })
    return
  }
  appendMessage({ role: 'assistant', text: '抱歉，刚才点单服务出了点问题，请再试一次～' })
}

/** 对话失败分支：降级（1008）/ 限流（1009）/ 其它（request 层已弹提示，这里补一句保持对话连续）。 */
function handleChatError(error: unknown): void {
  if (error instanceof ApiError) {
    if (error.code === CODE_AI_UNAVAILABLE) {
      applyStreamError(error.code, (error.data as { fallbackText?: string } | undefined)?.fallbackText ?? null)
      return
    }
    applyStreamError(error.code, null)
    return
  }
  if (error instanceof Error) {
    appendMessage({ role: 'assistant', text: '抱歉，刚才点单服务出了点问题，请再试一次～' })
    return
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
 * 规格就地纠正（T71）：点一下规格组选了新值 → 翻译成一句指令。
 *
 * <p>刻意<b>不发新会话</b>：走的就是同一个 chat 通道（sessionId 原样带上），
 * 后端会话里的草稿被工具改写后回传新的 CARD——与行内改数量完全同构。
 * 不新增任何订单入口，也不在本地改草稿（本地改会让卡片金额与服务端草稿不一致）。</p>
 */
function handleSpecChange(item: AiDraftItem, groupName: string, optionName: string): void {
  if (sending.value) {
    return
  }
  void send(`把「${item.productName}」的${groupName}改成${optionName}`)
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
  <view class="ai-page" :class="{ 'a11y-mode': a11y.enabled }">
    <!-- 1008 降级横幅（AC-13）：模型不可用时引导回菜单手动点单 -->
    <view v-if="fallbackText" class="fallback-banner">
      <text class="fallback-text">{{ fallbackText }}</text>
      <view class="fallback-btn tap" @tap="goMenu">去菜单点单</view>
    </view>

    <scroll-view class="msg-list" scroll-y :scroll-into-view="lastAnchor" :scroll-with-animation="true">
      <view v-for="message in messages" :id="`msg-${message.id}`" :key="message.id" class="msg-item">
        <MessageBubble :role="message.role" :text="message.text" />
        <DraftCard
          v-if="message.draft"
          :draft="message.draft"
          :busy="sending || paying"
          :products="menuProducts"
          @change="handleAdjust"
          @remove="handleRemove"
          @spec-change="handleSpecChange"
          @pay="handlePay"
        />
      </view>
      <!-- 阶段提示取代原来那句「小茶正在想…」：等待时间是可解释的，就把它解释出来 -->
      <StageIndicator v-if="sending" :stage="stage" />
    </scroll-view>

    <!-- 冷启动示例指令：说一句就能点单 -->
    <view v-if="showExamples" class="examples">
      <view v-for="example in EXAMPLES" :key="example" class="example-chip tap" @tap="send(example)">
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
      <view class="send-btn tap" :class="{ disabled: sending }" @tap="send()">发送</view>
    </view>
    <view class="voice-hint a11y-sm">
      {{
        a11y.enabled
          ? '无障碍模式：全程可用键盘麦克风语音点单，无需手动打字'
          : '可按键盘上的麦克风说话，语音会自动转成文字'
      }}
    </view>

    <!-- 语音播报开关（T70，输出侧）：只在环境具备 TTS 能力时出现。
         刻意与上面那行「输入侧」提示分开：一个是「你说」，一个是「它念」，
         混在一处会让人以为播报开关能控制麦克风。 -->
    <view v-if="speech.available" class="speech-bar tap" @tap="speech.toggle()">
      <view class="speech-text a11y-sm">
        {{ speech.enabled ? 'AI 解析完成后会念出订单内容' : '语音播报已关闭' }}
      </view>
      <view class="speech-switch" :class="{ 'speech-switch-on': speech.enabled }">
        <view class="speech-switch-dot" />
      </view>
    </view>

    <IcpFooter />
  </view>
</template>

<style scoped>
.ai-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--c-bg);
}

.fallback-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 24rpx;
  background: var(--c-bg-warm);
  border-bottom: 1rpx solid var(--c-border);
}

.fallback-text {
  flex: 1;
  font-size: 26rpx;
  color: var(--c-warning);
}

.fallback-btn {
  flex-shrink: 0;
  margin-left: 16rpx;
  padding: 10rpx 24rpx;
  border-radius: var(--r-pill);
  background: var(--c-warning);
  color: var(--c-text-inverse);
  font-size: 24rpx;
}

.msg-list {
  flex: 1;
  padding: var(--s-card);
  box-sizing: border-box;
}

.msg-item {
  margin-bottom: 8rpx;
}

.examples {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  padding: 0 var(--s-card) 16rpx;
}

.example-chip {
  padding: 12rpx 20rpx;
  border-radius: var(--r-pill);
  background: var(--c-surface);
  font-size: 26rpx;
  color: var(--c-primary);
  box-shadow: var(--shadow-card);
}

/* 底部输入区：奶油底上浮的胶囊输入框，聚焦时描边转主色 */
.input-bar {
  display: flex;
  align-items: center;
  padding: 16rpx var(--s-card);
  background: var(--c-surface);
  border-top: 1rpx solid var(--c-border);
}

.input {
  flex: 1;
  height: 72rpx;
  padding: 0 24rpx;
  border-radius: var(--r-pill);
  background: var(--c-bg);
  border: 2rpx solid transparent;
  font-size: 28rpx;
  color: var(--c-text);
  transition: border-color 160ms var(--ease);
}

.input:focus {
  border-color: var(--c-primary-light);
}

.send-btn {
  flex-shrink: 0;
  margin-left: 16rpx;
  padding: 0 32rpx;
  height: 72rpx;
  border-radius: var(--r-pill);
  background: var(--c-primary);
  color: var(--c-text-inverse);
  font-size: 28rpx;
  line-height: 72rpx;
}

.send-btn.disabled {
  background: var(--c-primary-light);
}

.voice-hint {
  padding: 0 var(--s-card) 20rpx;
  background: var(--c-surface);
  font-size: 22rpx;
  color: var(--c-text-sub);
  text-align: center;
}

/* 语音播报开关（T70）：复用 T45 无障碍开关的视觉语言，让用户一眼认得这是同类开关 */
.speech-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 16rpx var(--s-card) 24rpx;
  background: var(--c-surface);
}

.speech-text {
  color: var(--c-text-sub);
}

.speech-switch {
  display: flex;
  align-items: center;
  width: 84rpx;
  height: 44rpx;
  padding: 4rpx;
  background: var(--c-muted);
  border-radius: 22rpx;
  transition: background-color 0.2s var(--ease);
}

.speech-switch-dot {
  width: 36rpx;
  height: 36rpx;
  background: var(--c-surface);
  border-radius: 50%;
  box-shadow: 0 1rpx 3rpx rgba(61, 50, 41, 0.2);
  transition: transform 0.2s var(--ease);
}

.speech-switch-on {
  background: var(--c-primary);
}

.speech-switch-on .speech-switch-dot {
  transform: translateX(40rpx);
}
</style>
