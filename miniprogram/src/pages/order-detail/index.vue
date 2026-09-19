<script setup lang="ts">
import { computed, ref } from 'vue'
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { getOrderDetail, getOrderStatus, type OrderDetail } from '@/api/order'
import { isActiveStatus, isTerminalStatus, statusLabel, STATUS_TYPE } from '@/utils/order-status'

/**
 * 订单详情（取餐码页，T27，LLD 8.2 / 4.4）：
 * - 大号取餐码 + 状态时间线（下单 / 支付 / 制作 / 出餐，未发生的事件置灰）；
 * - 进行中每 3 秒轮询 GET /orders/{id}/status 轻量接口；
 * - COMPLETED / CLOSED / VOIDED 为终态：停止轮询并展示终态文案（COMPLETED → 请取餐）；
 * - 页面切后台（onHide）暂停轮询省流量，切回（onShow）立即刷新一次并恢复。
 */

const POLL_INTERVAL_MS = 3000

const orderId = ref<number | null>(null)
const order = ref<OrderDetail | null>(null)
const status = ref<string>('')
const pickupCode = ref<string | null>(null)
const seq = ref<number | null>(null)
const loading = ref(true)

let pollTimer: number | null = null

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

onHide(() => stopPolling())
onUnload(() => stopPolling())

/** 进入页面：拉详情 + 首次状态，随后按需启动轮询 */
async function start(): Promise<void> {
  await loadDetail()
  await poll()
  if (isActiveStatus(status.value)) {
    startPolling()
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

/** 轮询轻量状态：状态有变化时补拉一次详情（拿制作 / 出餐时间），终态停表 */
async function poll(): Promise<void> {
  if (!orderId.value) return
  try {
    const result = await getOrderStatus(orderId.value)
    const previous = status.value
    status.value = result.status
    pickupCode.value = result.pickupCode
    seq.value = result.seq
    if (previous !== result.status) {
      await loadDetail(true)
    }
    if (isTerminalStatus(result.status)) {
      stopPolling()
    }
  } catch {
    // 网络抖动不打断：下一轮继续（错误 toast 已由 request 层给出）
  }
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
  <view class="detail-page">
    <view v-if="loading" class="page-tip">订单加载中…</view>

    <template v-else-if="order">
      <view class="code-card">
        <view class="code-label">取餐码</view>
        <view class="code-value">{{ pickupCode ?? '--' }}</view>
        <view class="code-status" :class="`status-${badgeType}`">{{ statusText }}</view>
        <view v-if="active" class="code-tip">每 3 秒自动刷新制作进度</view>
        <view v-else-if="terminalTip" class="code-tip">{{ terminalTip }}</view>
      </view>

      <view class="card">
        <view class="card-title">订单状态</view>
        <view v-for="(node, index) in timeline" :key="index" class="timeline-node">
          <view class="node-dot" :class="{ 'node-dot-done': node.done }"></view>
          <view class="node-body">
            <view class="node-label" :class="{ 'node-label-active': node.done }">{{ node.label }}</view>
            <view class="node-time">{{ node.time ?? '—' }}</view>
          </view>
        </view>
      </view>

      <view class="card">
        <view class="card-title">订单信息</view>
        <view class="info-line"><text class="info-label">订单号</text><text>{{ order.orderNo }}</text></view>
        <view class="info-line"><text class="info-label">下单时间</text><text>{{ order.createdAt }}</text></view>
        <view v-if="order.remark" class="info-line"><text class="info-label">备注</text><text>{{ order.remark }}</text></view>
        <view class="info-line">
          <text class="info-label">实付金额</text>
          <text class="info-amount">￥{{ order.totalAmount }}</text>
        </view>
        <view v-for="(item, index) in order.items" :key="index" class="goods-line">
          <view class="goods-main">
            <text class="goods-name">{{ item.productName }}</text>
            <text class="goods-qty">x{{ item.quantity }}</text>
          </view>
          <view v-if="item.options.length" class="goods-spec">
            {{ item.options.map((option) => option.optionName).join('/') }}
          </view>
          <view class="goods-amount">￥{{ item.itemAmount }}</view>
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
