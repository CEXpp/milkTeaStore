<script setup lang="ts">
import { computed, ref } from 'vue'
import { onReachBottom, onShow } from '@dcloudio/uni-app'
import { getActiveOrders, getOrderHistory, getOrderStatus, type OrderListItem } from '@/api/order'
import { statusLabel, STATUS_TYPE } from '@/utils/order-status'

/**
 * 订单 Tab（T27，LLD 8.2）：
 * - 上区：进行中订单卡（状态徽标 + 排队序；排队序取自轮询轻量接口的 seq）；
 * - 下区：历史订单分页，触底加载（每页 10 条，创建时间倒序）；
 * - 每次回到本页（onShow）刷新一次，实现「关小程序再进，进行中订单仍在」。
 */

/** 历史分页每页条数 */
const PAGE_SIZE = 10

interface ActiveOrder extends OrderListItem {
  /** 排队序号（仅 PAID 单需要，取不到时为 null） */
  seq: number | null
}

const activeOrders = ref<ActiveOrder[]>([])
const history = ref<OrderListItem[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const refreshing = ref(false)

const hasMore = computed(() => history.value.length < total.value)
const hasAny = computed(() => activeOrders.value.length > 0 || history.value.length > 0)

/** 进行中列表 + 逐单补齐排队序（单店量级下进行中订单数很小，逐个查可接受） */
async function loadActive(): Promise<void> {
  const list = await getActiveOrders()
  const enriched = await Promise.all(
    list.map(async (order) => {
      if (order.status !== 'PAID') {
        return { ...order, seq: null }
      }
      try {
        const status = await getOrderStatus(order.id)
        return { ...order, seq: status.seq }
      } catch {
        return { ...order, seq: null }
      }
    })
  )
  activeOrders.value = enriched
}

/** 历史分页：reset=true 重新从第一页加载 */
async function loadHistory(reset: boolean): Promise<void> {
  if (loading.value) return
  if (!reset && !hasMore.value) return
  loading.value = true
  try {
    const result = await getOrderHistory(reset ? 1 : page.value, PAGE_SIZE)
    if (reset) {
      page.value = 1
      history.value = result.list
    } else {
      history.value = [...history.value, ...result.list]
    }
    total.value = result.total
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    loading.value = false
  }
}

async function refresh(): Promise<void> {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await Promise.all([loadActive(), loadHistory(true)])
  } finally {
    refreshing.value = false
  }
}

onShow(() => {
  void refresh()
})

// 触底加载下一页历史订单
onReachBottom(() => {
  if (!hasMore.value || loading.value) return
  page.value += 1
  void loadHistory(false)
})

function openDetail(order: OrderListItem): void {
  uni.navigateTo({ url: `/pages/order-detail/index?id=${order.id}` })
}

function goMenu(): void {
  uni.switchTab({ url: '/pages/menu/index' })
}

function badgeClass(status: string): string {
  return `badge badge-${STATUS_TYPE[status] ?? 'info'}`
}
</script>

<template>
  <view class="order-page">
    <view class="section">
      <view class="section-title">进行中</view>
      <view v-if="!activeOrders.length" class="section-empty">暂无进行中订单</view>
      <view
        v-for="order in activeOrders"
        :key="order.id"
        class="order-card"
        @click="openDetail(order)"
      >
        <view class="card-head">
          <view class="card-code">
            <text v-if="order.pickupCode" class="code-text">{{ order.pickupCode }}</text>
            <text v-else class="code-text code-pending">--</text>
          </view>
          <view :class="badgeClass(order.status)">{{ statusLabel(order.status, order.seq) }}</view>
        </view>
        <view class="card-items">
          <view v-for="(line, index) in order.items" :key="index" class="item-line">{{ line }}</view>
        </view>
        <view class="card-foot">
          <text class="foot-time">{{ order.createdAt }}</text>
          <text class="foot-amount">￥{{ order.totalAmount }}</text>
        </view>
      </view>
    </view>

    <view class="section">
      <view class="section-title">历史订单</view>
      <view v-if="!history.length" class="section-empty">暂无历史订单</view>
      <view v-for="order in history" :key="order.id" class="order-card" @click="openDetail(order)">
        <view class="card-head">
          <view class="card-code">
            <text class="code-text">{{ order.pickupCode ?? '--' }}</text>
          </view>
          <view :class="badgeClass(order.status)">{{ statusLabel(order.status) }}</view>
        </view>
        <view class="card-items">
          <view v-for="(line, index) in order.items" :key="index" class="item-line">{{ line }}</view>
        </view>
        <view class="card-foot">
          <text class="foot-time">{{ order.createdAt }}</text>
          <text class="foot-amount">￥{{ order.totalAmount }}</text>
        </view>
      </view>

      <view v-if="history.length" class="list-foot">
        <text v-if="loading">加载中…</text>
        <text v-else-if="hasMore">上拉加载更多</text>
        <text v-else>没有更多了</text>
      </view>
    </view>

    <view v-if="!hasAny && !loading" class="empty-box">
      <view class="empty-icon">单</view>
      <view class="empty-text">暂无订单</view>
      <view class="empty-sub" @click="goMenu">去菜单挑一杯</view>
    </view>
  </view>
</template>

<style scoped>
.order-page {
  min-height: 100vh;
  padding: 20rpx;
}

.section {
  margin-bottom: 28rpx;
}

.section-title {
  margin-bottom: 16rpx;
  font-size: 28rpx;
  font-weight: 600;
  color: #303133;
}

.section-empty {
  padding: 32rpx;
  font-size: 26rpx;
  color: #c0c4cc;
  text-align: center;
  background: #fff;
  border-radius: 16rpx;
}

.order-card {
  padding: 24rpx;
  margin-bottom: 16rpx;
  background: #fff;
  border-radius: 16rpx;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-code {
  display: flex;
  align-items: baseline;
}

.code-text {
  font-size: 40rpx;
  font-weight: 700;
  color: #409eff;
  font-family: 'Consolas', 'Menlo', monospace;
}

.code-pending {
  color: #c0c4cc;
}

.badge {
  padding: 6rpx 20rpx;
  font-size: 24rpx;
  border-radius: 999rpx;
}

.badge-primary {
  color: #409eff;
  background: #ecf5ff;
}

.badge-success {
  color: #07c160;
  background: #e8f8ef;
}

.badge-warning {
  color: #e6a23c;
  background: #fdf6ec;
}

.badge-info {
  color: #909399;
  background: #f4f4f5;
}

.badge-danger {
  color: #f56c6c;
  background: #fef0f0;
}

.card-items {
  margin-top: 16rpx;
}

.item-line {
  font-size: 26rpx;
  color: #606266;
  line-height: 1.7;
}

.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16rpx;
}

.foot-time {
  font-size: 22rpx;
  color: #c0c4cc;
}

.foot-amount {
  font-size: 28rpx;
  font-weight: 600;
  color: #f56c6c;
}

.list-foot {
  padding: 24rpx;
  font-size: 24rpx;
  color: #c0c4cc;
  text-align: center;
}
</style>
