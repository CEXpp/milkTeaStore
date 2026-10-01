<script setup lang="ts">
import { computed, ref } from 'vue'
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { getOrderDetail, getOrderStatus, type OrderDetail } from '@/api/order'
import {
  subscribeOrderEvents,
  type OrderEventSubscription,
  type OrderStatusEvent
} from '@/utils/order-events'
import { isActiveStatus, isTerminalStatus, statusLabel, STATUS_TYPE } from '@/utils/order-status'
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
})

onHide(() => closeRealtime())
onUnload(() => closeRealtime())

/** 进入页面：拉详情 + 首次状态，随后按需启动实时通道 */
async function start(): Promise<void> {
  await loadDetail()
  await poll()
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

/** 状态展示文案：PAID →「排队中第 N 位」、PREPARING →「制作中」、COMPLETED →「请取餐」 */
const statusText = computed(() => statusLabel(status.value, seq.value))

const badgeType = computed(() => STATUS_TYPE[status.value] ?? 'info')

const active = computed(() => isActiveStatus(status.value))

/** 时间线：下单 → 支付 → 制作 → 出餐（未发生的事件显示为 pending） */
const timeline = computed(() => {
  const detail = order.value
  return [
    { label: '已下单', time: detail?.createdAt ?? null, done: Boolean(detail?.createdAt) },
    { label: '已支付', time: detail?.paidAt ?? null, done: Boolean(detail?.paidAt) },
    { label: '制作中', time: detail?.startedAt ?? null, done: Boolean(detail?.startedAt) },
    {
      label: '已出餐',
      time: detail?.completedAt ?? null,
      done: Boolean(detail?.completedAt)
    }
  ]
})

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

      <view class="card">
        <view class="card-title a11y-md">订单状态</view>
        <view v-for="(node, index) in timeline" :key="index" class="timeline-node">
          <view class="node-dot" :class="{ 'node-dot-done': node.done }"></view>
          <view class="node-body">
            <view class="node-label a11y-md" :class="{ 'node-label-active': node.done }">{{ node.label }}</view>
            <view class="node-time a11y-sm a11y-dim">{{ node.time ?? '—' }}</view>
          </view>
        </view>
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

.timeline-node {
  display: flex;
  align-items: flex-start;
  padding: 10rpx 0;
}

.node-dot {
  width: 20rpx;
  height: 20rpx;
  margin: 8rpx 20rpx 0 0;
  background: #e4e7ed;
  border-radius: 50%;
}

.node-dot-done {
  background: #409eff;
}

.node-label {
  font-size: 26rpx;
  color: #c0c4cc;
}

.node-label-active {
  color: #303133;
}

.node-time {
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #c0c4cc;
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
