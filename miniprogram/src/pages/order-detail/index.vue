<script setup lang="ts">
import { computed, ref } from 'vue'
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import {
  arriveOrder,
  getOrderDetail,
  getOrderStatus,
  getOrderTimeline,
  updateOrderEta,
  type OrderDetail,
  type OrderTimeline
} from '@/api/order'
import { getQueueEstimate, type QueueEstimate } from '@/api/queue'
import {
  subscribeOrderEvents,
  type OrderEventSubscription,
  type OrderStatusEvent
} from '@/utils/order-events'
import { isActiveStatus, isTerminalStatus, statusLabel, STATUS_TYPE } from '@/utils/order-status'
import { issueDelegate, revokeDelegate } from '@/api/delegate'
import { useA11yStore } from '@/stores/a11y'
import { vibratePickupReady } from '@/utils/pickup-remind'

/**
 * 订单详情（取餐码页，T27，LLD 8.2 / 4.4 / T43 11.1 / T45 11.4）：
 * - 大号取餐码 + 状态时间线（下单 / 支付 / 制作 / 出餐，未发生的事件置灰）；
 * - 实时通道（T43）：优先订阅 SSE `/api/customer/orders/events?orderId=`，收到事件立即应用载荷并
 *   补拉一次轻量状态（补齐 seq），出口与轮询一致；SSE 不可用时回落 3 秒轮询 `/{id}/status`，二者互斥；
 * - 连接建立时后端会补发一条当前状态快照，重连后自动对齐服务端真值；
 * - COMPLETED / CLOSED / VOIDED 为终态：关闭实时通道并展示终态文案（COMPLETED → 请取餐）；
 * - 页面切后台（onHide）关闭实时通道省流量，切回（onShow）立即刷新一次并重新建连；
 * - 无障碍（T45，LLD 11.4「双信道取餐提醒」）：开启后进入 COMPLETED 时追加**震动**信道，
 *   并把取餐码与状态放大加黑；视觉/触觉/微信订阅消息三信道同源（都由 PICKUP_READY 驱动），
 *   呈现层分叉而业务契约不变。
 */

const POLL_INTERVAL_MS = 3000

const a11y = useA11yStore()

const orderId = ref<number | null>(null)
const order = ref<OrderDetail | null>(null)
const status = ref<string>('')
const pickupCode = ref<string | null>(null)
const seq = ref<number | null>(null)
const loading = ref(true)

/** 队列预估（T49）：来自 T46 内核的真实队列聚合，前端不做本地估算 */
const estimate = ref<QueueEstimate | null>(null)
/** 生命周期时间轴（T50，W03）：六时间戳 + 每段耗时 + 同渠道同日中位数 */
const orderTimeline = ref<OrderTimeline | null>(null)
/** 已等待秒数（每秒刷新，用于「已等待 mm:ss」） */
const waitedSeconds = ref(0)
/** 「我到店还需」快捷项（T51）：与后端 ArrivalService.ALLOWED_ETA_MINUTES 一致 */
const ETA_OPTIONS = [3, 5, 10]

/** 到店申报时间（申报后展示，避免重复点击） */
const arrivedAt = ref<string | null>(null)
const arriveBusy = ref(false)
/** 我申报的预计到店时长（T51，「我将到」）：3/5/10 或 null（未申报 / 已撤销） */
const myEta = ref<number | null>(null)
const etaBusy = ref(false)
/** 秒级计时器（仅进行中订单需要） */
let tickTimer: number | null = null

let pollTimer: number | null = null
/** SSE 订阅句柄（T43）；为 null 表示当前走轮询回落通道 */
let subscription: OrderEventSubscription | null = null
/** 是否已回落到轮询（防止降级回调重复启动定时器） */
let usingPolling = false

onLoad((query) => {
  const id = Number(query?.id)
  orderId.value = Number.isFinite(id) && id > 0 ? id : null
})

onShow(() => {
  if (!orderId.value) {
    loading.value = false
    return
  }
  void start()
  startTick()
})

onHide(() => {
  closeRealtime()
  stopTick()
})

onUnload(() => {
  closeRealtime()
  stopTick()
})

/** 进入页面：拉详情 + 首次状态 + 队列预估，随后按需启动实时通道 */
async function start(): Promise<void> {
  await loadDetail()
  await poll()
  await loadEstimate()
  void loadTimeline()
  refreshWaited()
  if (isActiveStatus(status.value)) {
    startRealtime()
  }
}

/** @param silent true = 状态变化时静默补拉，不闪加载态 */
async function loadDetail(silent = false): Promise<void> {
  if (!silent) {
    loading.value = true
  }
  try {
    const detail = await getOrderDetail(orderId.value as number)
    order.value = detail
    status.value = detail.status
    pickupCode.value = detail.pickupCode
    myEta.value = detail.etaMinutes
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    if (!silent) {
      loading.value = false
    }
  }
}

/**
 * 状态统一应用入口（SSE 与轮询共用，保证两通道出口一致）：
 * 状态有变化时补拉一次详情（拿制作 / 出餐时间），终态关闭实时通道。
 *
 * @param next       新状态（null 表示本次不更新）
 * @param nextPickup 新取餐码（undefined 表示本次不更新）
 * @param nextSeq    排队序号（仅轮询通道提供）
 */
async function applyStatus(
  next: string | null,
  nextPickup: string | null | undefined,
  nextSeq?: number | null
): Promise<void> {
  const previous = status.value
  if (next) {
    status.value = next
  }
  if (nextPickup !== undefined) {
    pickupCode.value = nextPickup
  }
  if (typeof nextSeq === 'number') {
    seq.value = nextSeq
  }
  if (previous !== status.value) {
    await loadDetail(true)
    // 状态变了，队列位置、预计等待与时间轴都会跟着变（T49/T50）；失败静默，不影响主状态展示
    void loadEstimate()
    void loadTimeline()
    refreshWaited()
  }
  // 双信道提醒（T45）：刚刚进入「请取餐」时追加震动信道（仅无障碍模式；订阅消息由 T44 后端推送）。
  // 判定「刚刚进入」而非「当前是 COMPLETED」，避免每次重进页面都震一次。
  if (a11y.enabled && status.value === 'COMPLETED' && previous !== 'COMPLETED') {
    vibratePickupReady()
  }
  if (isTerminalStatus(status.value)) {
    closeRealtime()
  }
}

/** 轮询轻量状态（回落通道）：status + pickupCode + seq 一次取回 */
async function poll(): Promise<void> {
  if (!orderId.value) return
  try {
    const result = await getOrderStatus(orderId.value)
    await applyStatus(result.status, result.pickupCode, result.seq)
  } catch {
    // 网络抖动不打断：下一轮继续（错误 toast 已由 request 层给出）
  }
}

/** SSE 事件（增强通道）：先应用事件载荷（立即反馈），再补拉一次轻量状态以补齐 seq */
async function handleEvent(event: OrderStatusEvent): Promise<void> {
  await applyStatus(event.status, event.pickupCode)
  await poll()
}

/** 建立实时通道（T43）：优先 SSE；不支持或中断时回落 3 秒轮询 */
function startRealtime(): void {
  if (!orderId.value) return
  closeRealtime()
  subscription = subscribeOrderEvents(orderId.value, {
    onEvent: (event) => void handleEvent(event),
    onFallback: () => {
      if (usingPolling) return
      usingPolling = true
      subscription = null
      startPolling()
    }
  })
  if (!subscription) {
    // 宿主不支持流式响应：直接回落轮询
    usingPolling = true
    startPolling()
  }
}

/** 关闭实时通道（SSE 与轮询一并停掉） */
function closeRealtime(): void {
  subscription?.close()
  subscription = null
  usingPolling = false
  stopPolling()
}

function startPolling(): void {
  if (pollTimer !== null) return
  pollTimer = setInterval(() => void poll(), POLL_INTERVAL_MS) as unknown as number
}

function stopPolling(): void {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 队列预估（T49）：失败静默——预估只是预期管理，缺失也不影响订单状态展示。 */
async function loadEstimate(): Promise<void> {
  if (!orderId.value) {
    return
  }
  try {
    estimate.value = await getQueueEstimate(orderId.value)
  } catch {
    estimate.value = null
  }
}

/** 生命周期时间轴（T50）：失败静默——时间轴是阅读性增强，缺失不影响主状态展示。 */
async function loadTimeline(): Promise<void> {
  if (!orderId.value) {
    return
  }
  try {
    orderTimeline.value = await getOrderTimeline(orderId.value)
  } catch {
    orderTimeline.value = null
  }
}

/** 已等待时长（从支付时刻起算）；非队列内订单归零。 */
function refreshWaited(): void {
  const startAt = parseDateTime(order.value?.paidAt ?? null)
  waitedSeconds.value = startAt === null ? 0 : Math.max(0, Math.floor((Date.now() - startAt) / 1000))
}

/**
 * 解析后端时间串（yyyy-MM-dd HH:mm:ss）。
 * 注意：iOS 的 Date.parse 不接受「空格分隔」的格式，须替换为 ISO 的 'T'。
 */
function parseDateTime(text: string | null): number | null {
  if (!text) {
    return null
  }
  const parsed = Date.parse(text.replace(' ', 'T'))
  return Number.isNaN(parsed) ? null : parsed
}

function startTick(): void {
  if (tickTimer !== null) {
    return
  }
  tickTimer = setInterval(refreshWaited, 1000) as unknown as number
}

function stopTick(): void {
  if (tickTimer !== null) {
    clearInterval(tickTimer)
    tickTimer = null
  }
}

/**
 * 申报「我已到店」（T49 到店握手）。
 *
 * 只写一个信号：看板据此给卡片打「已到店」标记，**不改变队列排序**（验收项「不强制改排序」），
 * 店长可据此优先处理、也可完全无视。重复申报幂等，不报错。
 */
async function markArrived(): Promise<void> {
  if (!orderId.value || arriveBusy.value || arrivedAt.value) {
    return
  }
  arriveBusy.value = true
  try {
    const result = await arriveOrder(orderId.value)
    arrivedAt.value = result.arrivedAt
    uni.showToast({ title: '已告知商家你已到店', icon: 'none' })
  } catch {
    // 错误提示已由 request 层 toast 直显（如 1004 当前状态不可申报）
  } finally {
    arriveBusy.value = false
  }
}

/**
 * 申报 / 修改 / 撤销「我到店还需 X 分钟」（T51，W02「我将到」）。
 *
 * 与「我已到店」同属到店信号，但两者在看板上的待遇不同：本信号**参与**建议制作顺序
 * （到达近的优先），而「我已到店」只打标识、不参与排序。传 null 即撤销。
 */
async function setEta(minutes: number | null): Promise<void> {
  if (!orderId.value || etaBusy.value) {
    return
  }
  etaBusy.value = true
  try {
    const result = await updateOrderEta(orderId.value, minutes)
    myEta.value = result.etaMinutes
    uni.showToast({
      title: minutes === null ? '已撤销到店时间' : `已告知商家约 ${minutes} 分钟后到店`,
      icon: 'none'
    })
  } catch {
    // 错误提示已由 request 层 toast 直显（如 1001 时长不合法 / 1004 状态不可申报）
  } finally {
    etaBusy.value = false
  }
}

/** 状态展示文案：PAID →「排队中第 N 位」、PREPARING →「制作中」、COMPLETED →「请取餐」 */
const statusText = computed(() => statusLabel(status.value, seq.value))

const badgeType = computed(() => STATUS_TYPE[status.value] ?? 'info')

const active = computed(() => isActiveStatus(status.value))

/** 可申报到店的状态（与后端 ArrivalService.ARRIVABLE_STATUSES 一致） */
const arrivable = computed(() => status.value === 'PAID' || status.value === 'PREPARING')

/**
 * 可转赠取餐凭证的状态（T65，W15）：与后端 PICKABLE_STATUSES 一致
 * （PAID / PREPARING / COMPLETED）。
 *
 * 待支付与终态（关闭 / 作废）不给入口——前者还没付款，后者已无餐可取。
 */
const delegatable = computed(() => ['PAID', 'PREPARING', 'COMPLETED'].includes(status.value))

/** 当前有效的委托令牌（仅签发那一刻存在；刷新页面即失去，故提示用户保存） */
const delegateToken = ref('')
const delegateBusy = ref(false)

/**
 * 签发代取凭证（T65）：限时 + 一次性 + 可撤销 + 不可转赠。
 *
 * 令牌只在此刻返回一次，**页面刷新后就再也拿不到**，所以签发后必须让用户当场复制/转发。
 */
async function createDelegate(): Promise<void> {
  if (!orderId.value || delegateBusy.value) return
  delegateBusy.value = true
  try {
    delegateToken.value = await issueDelegate(orderId.value, { minutes: 120 })
    uni.showToast({ title: '已生成代取凭证', icon: 'success' })
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    delegateBusy.value = false
  }
}

/** 复制代取链接：发给要代取的人 */
function copyDelegateLink(): void {
  if (!delegateToken.value) return
  // 用完整路径（含 token）而非仅 token —— 代取人多半是直接点开链接
  uni.setClipboardData({
    data: delegateToken.value,
    success: () => uni.showToast({ title: '凭证口令已复制', icon: 'none' })
  })
}

/** 撤销代取凭证：原主始终是权限终点，撤销后对方立即失效 */
async function cancelDelegate(): Promise<void> {
  if (!orderId.value || delegateBusy.value) return
  delegateBusy.value = true
  try {
    await revokeDelegate(orderId.value)
    delegateToken.value = ''
    uni.showToast({ title: '已收回代取凭证', icon: 'none' })
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    delegateBusy.value = false
  }
}

/** 已等待 mm:ss（秒级刷新） */
const waitedText = computed(() => {
  const mm = Math.floor(waitedSeconds.value / 60)
  const ss = waitedSeconds.value % 60
  return `${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`
})

/** 预计还需区间：诚实标注不确定性，不做精确承诺（W19）；不在队列中时不展示 */
const estimateText = computed(() => {
  const current = estimate.value
  if (!current || current.position === 0) {
    return ''
  }
  return `${current.etaLow}–${current.etaHigh} 分钟`
})

/** 队列位置（1 起；null 与 0 均不展示） */
const positionText = computed(() => {
  const current = estimate.value
  if (!current || !current.position) {
    return ''
  }
  return `第 ${current.position} 位`
})

/**
 * 时间轴节点（T50，W03）：只渲染**已发生**的节点。
 *
 * 这样异常单会自然跳过没走到的环节——超时关闭单只显示「已下单 → 已关闭」，
 * 而不是摆出一串永远灰着的「已支付 / 制作中」，那反而误导顾客。
 */
const timelineNodes = computed(() => (orderTimeline.value?.nodes ?? []).filter((node) => node.done))

/** 与今日同渠道中位数的对比文案；无样本或本单未完成时返回空（不展示对比行）。 */
const medianText = computed(() => {
  const data = orderTimeline.value
  if (
    !data ||
    data.myPrepMinutes === null ||
    data.medianPrepMinutes === null ||
    data.medianSampleCount === 0
  ) {
    return ''
  }
  const diff = data.myPrepMinutes - data.medianPrepMinutes
  const suffix = `（今日同渠道 ${data.medianSampleCount} 单）`
  if (diff === 0) {
    return `本单制作 ${data.myPrepMinutes} 分钟，与今日同渠道中位数持平${suffix}`
  }
  return `本单制作 ${data.myPrepMinutes} 分钟，${diff > 0 ? '慢于' : '快于'}今日同渠道中位数 ${data.medianPrepMinutes} 分钟${suffix}`
})

/** 单段耗时文案：不足 60 秒只显示秒；整分不显示「0 秒」。 */
function formatDuration(seconds: number): string {
  if (seconds < 60) {
    return `${seconds} 秒`
  }
  const mm = Math.floor(seconds / 60)
  const ss = seconds % 60
  return ss === 0 ? `${mm} 分` : `${mm} 分 ${ss} 秒`
}

/** 终态补充说明（关闭 / 作废原因） */
const terminalTip = computed(() => {
  if (status.value === 'CLOSED') return '订单已超时关闭，请重新下单'
  if (status.value === 'VOIDED') return order.value?.voidReason ? `订单已作废：${order.value.voidReason}` : '订单已作废'
  if (status.value === 'COMPLETED') return '已出餐，请凭取餐码到柜台取餐'
  return ''
})

function goMenu(): void {
  uni.switchTab({ url: '/pages/menu/index' })
}

function goOrders(): void {
  uni.switchTab({ url: '/pages/order/index' })
}

/** 待支付：跳回支付确认页继续支付 */
function continuePay(): void {
  if (!orderId.value) return
  uni.navigateTo({ url: `/pages/pay-confirm/index?id=${orderId.value}` })
}
</script>

<template>
  <view class="detail-page" :class="{ 'a11y-mode': a11y.enabled }">
    <view v-if="loading" class="page-tip">订单加载中…</view>

    <template v-else-if="order">
      <!-- 无障碍取餐提醒横幅（T45 视觉信道）：与震动、微信订阅消息同源，均由 COMPLETED 触发 -->
      <view v-if="a11y.enabled && status === 'COMPLETED'" class="a11y-pickup-banner">
        <text class="a11y-pickup-title">请到柜台取餐</text>
        <text class="a11y-pickup-code">{{ pickupCode ?? '--' }}</text>
        <text class="a11y-pickup-tip">已同时以手机震动与微信消息提醒</text>
      </view>

      <view class="code-card">
        <view class="code-label a11y-md a11y-dim">取餐码</view>
        <view class="code-value a11y-code">{{ pickupCode ?? '--' }}</view>
        <view class="code-status a11y-lg" :class="`status-${badgeType}`">{{ statusText }}</view>
        <view v-if="active" class="code-tip a11y-sm a11y-dim">制作进度实时同步（连接异常时自动切换为轮询）</view>
        <view v-else-if="terminalTip" class="code-tip a11y-sm a11y-dim">{{ terminalTip }}</view>
      </view>

      <!-- 等待预估与到店握手（T49，W19）：诚实标注波动区间 + 「我已到店」信号 -->
      <view v-if="arrivable" class="card">
        <view class="card-title a11y-md">等待预估</view>
        <view class="eta-line">
          <text class="eta-key a11y-sm a11y-dim">已等待</text>
          <text class="eta-val a11y-md">{{ waitedText }}</text>
        </view>
        <view v-if="positionText" class="eta-line">
          <text class="eta-key a11y-sm a11y-dim">队列位置</text>
          <text class="eta-val a11y-md">{{ positionText }}</text>
        </view>
        <view v-if="estimateText" class="eta-line">
          <text class="eta-key a11y-sm a11y-dim">预计还需</text>
          <text class="eta-val a11y-md">{{ estimateText }}</text>
        </view>
        <view class="eta-note a11y-sm a11y-dim">
          预估随队列实时变化，区间用于表达不确定性，不做精确承诺
        </view>

        <!-- 到店预约（T51，W02「我将到」）：只影响看板的建议制作顺序，不改变订单状态与统计 -->
        <view class="eta-pick">
          <text class="eta-key a11y-sm a11y-dim">我到店还需</text>
          <view class="eta-chips">
            <view
              v-for="option in ETA_OPTIONS"
              :key="option"
              class="eta-chip a11y-sm"
              :class="{ 'eta-chip-active': myEta === option }"
              @click="setEta(option)"
            >
              {{ option }} 分钟
            </view>
            <view v-if="myEta !== null" class="eta-chip eta-chip-clear a11y-sm" @click="setEta(null)">
              撤销
            </view>
          </view>
        </view>

        <view class="arrive-btn a11y-md" :class="{ 'arrive-btn-done': !!arrivedAt }" @click="markArrived">
          {{ arrivedAt ? '已告知商家你已到店' : arriveBusy ? '提交中…' : '我已到店' }}
        </view>
      </view>

      <!-- 订单生命周期时间轴（T50，W03）：六时间戳 + 每段耗时 + 同渠道同日中位数对比 -->
      <view v-if="timelineNodes.length" class="card">
        <view class="card-title a11y-md">订单时间轴</view>
        <scroll-view scroll-x class="tl-scroll" :show-scrollbar="false">
          <view class="tl-track">
            <view v-for="(node, index) in timelineNodes" :key="node.key" class="tl-node">
              <view class="tl-head">
                <view class="tl-dot" />
                <view v-if="index < timelineNodes.length - 1" class="tl-line" />
              </view>
              <view class="tl-body">
                <text class="tl-label a11y-sm">{{ node.label }}</text>
                <text class="tl-time a11y-sm a11y-dim">{{ node.time ? node.time.slice(11, 16) : '—' }}</text>
                <text v-if="node.durationSeconds !== null" class="tl-dur a11y-dim">
                  用时 {{ formatDuration(node.durationSeconds) }}
                </text>
              </view>
            </view>
          </view>
        </scroll-view>
        <view v-if="medianText" class="tl-compare a11y-sm">{{ medianText }}</view>
      </view>

      <view class="card">
        <view class="card-title a11y-md">订单信息</view>
        <view class="info-line a11y-md"><text class="info-label a11y-dim">订单号</text><text>{{ order.orderNo }}</text></view>
        <view class="info-line a11y-md"><text class="info-label a11y-dim">下单时间</text><text>{{ order.createdAt }}</text></view>
        <view v-if="order.remark" class="info-line a11y-md"><text class="info-label a11y-dim">备注</text><text>{{ order.remark }}</text></view>
        <view class="info-line a11y-md">
          <text class="info-label a11y-dim">实付金额</text>
          <text class="info-amount">￥{{ order.totalAmount }}</text>
        </view>
        <view v-for="(item, index) in order.items" :key="index" class="goods-line">
          <view class="goods-main">
            <text class="goods-name a11y-md">{{ item.productName }}</text>
            <text class="goods-qty a11y-sm a11y-dim">x{{ item.quantity }}</text>
          </view>
          <view v-if="item.options.length" class="goods-spec a11y-sm a11y-dim">
            {{ item.options.map((option) => option.optionName).join('/') }}
          </view>
          <view class="goods-amount a11y-sm">￥{{ item.itemAmount }}</view>
        </view>
      </view>

      <!-- 取餐凭证转赠（T65，W15）：限时 + 一次性 + 可撤销 + 不可转赠。
           本区块是 SRS 9.2「不可跨 openid 读取」的唯一例外，故文案里把四条约定讲清 ——
           让下单人明白自己随时能收回，也明白对方打开一次就失效。 -->
      <view v-if="delegatable" class="card">
        <view class="card-title a11y-md">让朋友代取</view>

        <template v-if="!delegateToken">
          <view class="delegate-note a11y-sm a11y-dim">
            生成一个限时 2 小时的代取凭证。对方打开一次即失效，你随时可以收回。
          </view>
          <view class="delegate-btn a11y-md" @click="createDelegate">
            {{ delegateBusy ? '生成中…' : '生成代取凭证' }}
          </view>
        </template>

        <template v-else>
          <view class="delegate-note a11y-sm">
            把下面这串口令发给对方，ta 打开后即可看到取餐码（看不到金额）。
            刷新本页后此口令不再显示，请先复制。
          </view>
          <view class="delegate-token" @click="copyDelegateLink">{{ delegateToken }}</view>
          <view class="delegate-actions">
            <view class="delegate-btn delegate-btn-ghost a11y-sm" @click="copyDelegateLink">复制口令</view>
            <view class="delegate-btn delegate-btn-ghost a11y-sm" @click="cancelDelegate">收回凭证</view>
          </view>
        </template>

        <view v-if="delegateToken" class="delegate-note a11y-sm a11y-dim">
          凭证有效期为 2 小时；对方使用后自动作废，不可二次转赠。
        </view>
      </view>

      <view class="footer-actions">
        <view v-if="status === 'PENDING_PAYMENT'" class="action-btn action-primary" @click="continuePay">继续支付</view>
        <view class="action-btn" @click="goMenu">再点一杯</view>
        <view class="action-btn" @click="goOrders">我的订单</view>
      </view>
    </template>

    <view v-else class="empty-box">
      <view class="empty-icon">!</view>
      <view class="empty-text">订单加载失败</view>
      <view class="empty-sub" @click="goOrders">返回订单列表</view>
    </view>
  </view>
</template>

<style scoped>
.detail-page {
  min-height: 100vh;
  padding: 24rpx;
}

/* 无障碍取餐提醒横幅（T45）：视觉信道的高对比强化块——黑底、高亮取餐码、最大字号。
   与震动（触觉）、微信订阅消息（离线）共同构成「双信道取餐提醒」，三者同源同触发。 */
.a11y-pickup-banner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
  padding: 40rpx 32rpx;
  margin-bottom: 24rpx;
  text-align: center;
  background: #000000;
  border-radius: 20rpx;
}

.a11y-pickup-title {
  font-size: 48rpx;
  font-weight: 700;
  color: #ffffff;
}

.a11y-pickup-code {
  font-size: 132rpx;
  font-weight: 800;
  letter-spacing: 8rpx;
  color: #ffd400;
}

.a11y-pickup-tip {
  font-size: 28rpx;
  color: #ffffff;
}

/* 等待预估与到店握手（T49）：只读展示 + 一个可选的人工信号 */
.eta-line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 10rpx 0;
}

.eta-key {
  color: #909399;
}

.eta-val {
  font-weight: 600;
}

.eta-note {
  margin-top: 10rpx;
  line-height: 1.6;
  color: #909399;
}

/* 到店预约快捷项（T51）：3 / 5 / 10 分钟，可改可撤 */
.eta-pick {
  margin-top: 20rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid #f0f2f5;
}

.eta-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}

.eta-chip {
  padding: 10rpx 26rpx;
  color: #409eff;
  background: #ecf5ff;
  border-radius: 999rpx;
}

.eta-chip-active {
  color: #fff;
  background: #409eff;
}

.eta-chip-clear {
  color: #909399;
  background: #f4f4f5;
}

.arrive-btn {
  margin-top: 24rpx;
  height: 84rpx;
  line-height: 84rpx;
  font-size: 30rpx;
  text-align: center;
  color: #fff;
  background: #409eff;
  border-radius: 999rpx;
}

.arrive-btn-done {
  color: #268356;
  background: #e9f7f0;
}

.code-card {
  padding: 48rpx 32rpx;
  text-align: center;
  background: #fff;
  border-radius: 20rpx;
}

.code-label {
  font-size: 26rpx;
  color: #909399;
  letter-spacing: 4rpx;
}

.code-value {
  margin: 16rpx 0;
  font-size: 132rpx;
  font-weight: 700;
  line-height: 1.1;
  color: #409eff;
  font-family: 'Consolas', 'Menlo', monospace;
}

.code-status {
  display: inline-block;
  padding: 8rpx 28rpx;
  font-size: 28rpx;
  border-radius: 999rpx;
}

.status-primary {
  color: #409eff;
  background: #ecf5ff;
}

.status-success {
  color: #07c160;
  background: #e8f8ef;
}

.status-warning {
  color: #e6a23c;
  background: #fdf6ec;
}

.status-info {
  color: #909399;
  background: #f4f4f5;
}

.status-danger {
  color: #f56c6c;
  background: #fef0f0;
}

.code-tip {
  margin-top: 20rpx;
  font-size: 24rpx;
  color: #c0c4cc;
}

.card {
  margin-top: 20rpx;
  padding: 24rpx;
  background: #fff;
  border-radius: 20rpx;
}

.card-title {
  margin-bottom: 16rpx;
  font-size: 28rpx;
  font-weight: 600;
}

/* 横向时间轴（T50，W03）：节点横排、可横向滚动，连线只在相邻节点之间 */
.tl-scroll {
  width: 100%;
}

.tl-track {
  display: flex;
  align-items: flex-start;
  padding: 8rpx 0;
}

.tl-node {
  display: flex;
  flex-direction: column;
  width: 190rpx;
  flex-shrink: 0;
}

.tl-head {
  display: flex;
  align-items: center;
}

.tl-dot {
  width: 20rpx;
  height: 20rpx;
  flex-shrink: 0;
  background: #07c160;
  border-radius: 50%;
}

.tl-line {
  flex: 1;
  height: 2rpx;
  margin: 0 6rpx;
  background: #dcdfe6;
}

.tl-body {
  display: flex;
  flex-direction: column;
  margin-top: 12rpx;
}

.tl-label {
  font-weight: 600;
  color: #303133;
}

.tl-time {
  margin-top: 4rpx;
  color: #909399;
}

.tl-dur {
  margin-top: 4rpx;
  color: #909399;
}

.tl-compare {
  padding-top: 16rpx;
  margin-top: 16rpx;
  line-height: 1.6;
  color: #606266;
  border-top: 1rpx solid #f0f2f5;
}

.info-line {
  display: flex;
  justify-content: space-between;
  padding: 12rpx 0;
  font-size: 26rpx;
  color: #303133;
}

.info-label {
  color: #909399;
}

.info-amount {
  font-weight: 600;
  color: #f56c6c;
}

.goods-line {
  padding: 16rpx 0;
  border-top: 1rpx solid #f5f6f8;
}

.goods-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.goods-name {
  font-size: 28rpx;
  font-weight: 600;
}

.goods-qty {
  font-size: 26rpx;
  color: #909399;
}

.goods-spec {
  margin-top: 6rpx;
  font-size: 24rpx;
  color: #909399;
}

.goods-amount {
  margin-top: 6rpx;
  font-size: 26rpx;
  color: #f56c6c;
  text-align: right;
}

/* 取餐凭证转赠（T65）：说明文字先讲清四条约定，再给动作 */
.delegate-note {
  margin-bottom: 16rpx;
  line-height: 1.6;
}

.delegate-btn {
  height: 80rpx;
  line-height: 80rpx;
  text-align: center;
  color: #fff;
  background: #3b49b8;
  border-radius: 40rpx;
}

.delegate-btn-ghost {
  flex: 1;
  color: #3b49b8;
  background: #eef1fb;
}

.delegate-token {
  padding: 20rpx;
  margin-bottom: 16rpx;
  font-family: monospace;
  font-size: 24rpx;
  color: #3b49b8;
  word-break: break-all;
  background: #f8fafc;
  border: 1rpx dashed #c8cfe8;
  border-radius: 8rpx;
}

.delegate-actions {
  display: flex;
  gap: 20rpx;
  margin-bottom: 16rpx;
}

.footer-actions {
  display: flex;
  gap: 20rpx;
  margin-top: 28rpx;
  padding-bottom: 40rpx;
}

.action-btn {
  flex: 1;
  height: 80rpx;
  line-height: 80rpx;
  text-align: center;
  font-size: 28rpx;
  color: #606266;
  background: #fff;
  border-radius: 999rpx;
}

.action-primary {
  color: #fff;
  background: #409eff;
}
</style>
