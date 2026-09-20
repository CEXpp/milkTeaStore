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
/** 下一页游标：仅在成功加载后推进（失败/并发时保持不动，避免漏页） */
const nextPage = ref(1)
const loading = ref(false)
const refreshing = ref(false)

/** 请求序号：reset 会作废在途请求的响应，防止旧分页数据回填造成错序 */
let requestSeq = 0

/** 服务端已无更多数据（某次翻页返回空集）：偏移漂移被去重后条数可能永远追不上 total，据此收尾 */
const exhausted = ref(false)

const hasMore = computed(() => !exhausted.value && history.value.length < total.value)
const hasAny = computed(() => activeOrders.value.length > 0 || history.value.length > 0)

/** 进行中列表 + 逐单补齐排队序（单店量级下进行中订单数很小，逐个查可接受） */
async function loadActive(): Promise<void> {
  try {
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
          // 排队序取不到不阻塞列表展示，徽标退化为「排队中」
          return { ...order, seq: null }
        }
      })
    )
    activeOrders.value = enriched
  } catch {
    // 错误提示已由 request 层 toast 直显
  }
}

/**
 * 历史分页加载（偏移分页，创建时间倒序）。
 *
 * <p>游标治理：页码只在**本次请求成功**后推进——请求失败或并发拦截时不推进，
 * 下次触底重试同一页，避免出现「跳过一整页」的分页漂移。</p>
 *
 * <p>重复治理：翻页期间若有新订单插入，偏移分页会把上一页尾部再次返回，
 * 追加时按 orderId 去重，保证列表不出现重复项。</p>
 *
 * @param reset true = 从第 1 页重新加载（会作废在途请求，供 onShow 刷新使用）
 */
async function loadHistory(reset: boolean): Promise<void> {
  if (!reset && (loading.value || !hasMore.value)) return
  const targetPage = reset ? 1 : nextPage.value
  const seq = ++requestSeq
  loading.value = true
  try {
    const result = await getOrderHistory(targetPage, PAGE_SIZE)
    if (seq !== requestSeq) return // 已被更新的请求取代，丢弃本次结果
    if (reset) {
      history.value = result.list
      exhausted.value = false
    } else {
      const seen = new Set(history.value.map((order) => order.id))
      history.value = [...history.value, ...result.list.filter((order) => !seen.has(order.id))]
      if (result.list.length === 0) {
        exhausted.value = true
      }
    }
    total.value = result.total
    nextPage.value = targetPage + 1
  } catch {
    // 失败不推进游标：下次触底重试同一页（错误提示已由 request 层 toast 直显）
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

async function refresh(): Promise<void> {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await Promise.all([loadActive(), loadHistory(true)])
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    refreshing.value = false
  }
}

onShow(() => {
  void refresh()
})

// 触底加载下一页历史订单（页码由 loadHistory 在成功后推进，此处不预增）
onReachBottom(() => {
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
