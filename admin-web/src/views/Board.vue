<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { completeOrder, getOrderBoard, startOrder, voidOrder, type OrderBoardResult } from '@/api/order'
import { useMountOrActivateRefresh } from '@/composables/useMountOrActivateRefresh'
import { useShopStatus } from '@/composables/useShopStatus'
import { playDing, unlockDing } from '@/utils/ding'
import OrderCard from '@/components/board/OrderCard.vue'
import StatBar from '@/components/board/StatBar.vue'

/**
 * 订单看板（T19，LLD 3.5 / 7.3）：
 * - 今日概览四数 + 双分区（待制作 / 制作中）；
 * - 3 秒轮询 board 接口：比对前后 pending 的 orderId 差集，新增时播提示音并高亮 30 秒；
 * - 行内操作：开始制作 / 出餐（成功后立即刷新、卡片迁移分区）；作废二次确认且必填原因（仅 PAID 卡片）；
 * - visibilitychange：页面切后台暂停轮询省流量，切回立即刷新一次并恢复；
 * - 暂停接单状态由 useShopStatus 提供（开关在顶栏），本页只展示黄条横幅。
 *
 * 性能：轮询结果做「同内容复用旧对象引用」的差量合并，字段未变的卡片不触发重渲染；
 * 高亮到期在每轮轮询中统一清理，取代原先逐条 setTimeout。
 */

defineOptions({ name: 'Board' })

const POLL_INTERVAL_MS = 3000
/** 新单高亮时长（任务卡：新卡片高亮 30 秒） */
const HIGHLIGHT_DURATION_MS = 30_000

const { paused, notice, ensureLoaded } = useShopStatus()

const board = ref<OrderBoardResult | null>(null)
const highlightedIds = ref<number[]>([])
const busyIds = ref<number[]>([])

let pollTimer: number | null = null
/** 上一轮 pending 的 orderId 集合；null 表示首轮加载（首屏不播提示音、不高亮） */
let knownPendingIds: Set<number> | null = null
/** orderId → 高亮到期时间戳（毫秒） */
const highlightUntil = new Map<number, number>()
/** 上一轮订单对象缓存：用于字段未变时复用引用，避免整列卡片重渲染 */
const pendingCache = new Map<number, OrderBoardResult['pending'][number]>()
const preparingCache = new Map<number, OrderBoardResult['preparing'][number]>()
/** 页面是否处于激活态（keep-alive 下缓存时不再轮询） */
let active = true

/**
 * 订单对象全字段浅比较（items 数组逐项比对）。
 * 覆盖 minutesWaiting / paidAt / startedAt 等所有字段，
 * 避免「某字段已变却复用了旧对象引用」造成的漏更新。
 */
function shallowEqual(a: object, b: object): boolean {
  const left = a as Record<string, unknown>
  const right = b as Record<string, unknown>
  const keys = Object.keys(left)
  if (keys.length !== Object.keys(right).length) return false
  for (const key of keys) {
    const leftValue = left[key]
    const rightValue = right[key]
    if (Array.isArray(leftValue) && Array.isArray(rightValue)) {
      if (leftValue.length !== rightValue.length || leftValue.some((value, index) => value !== rightValue[index])) {
        return false
      }
      continue
    }
    if (leftValue !== rightValue) return false
  }
  return true
}

/**
 * 差量合并：内容完全一致的订单沿用上一次的对象引用，并返回本轮是否发生变化。
 * 引用不变时 Vue 在 patch 阶段判定 props 未变，卡片组件不会重渲染；
 * 任一字段变化则生成新引用，保证界面一定刷新。
 */
function mergePending(list: OrderBoardResult['pending']): {
  list: OrderBoardResult['pending']
  changed: boolean
} {
  let changed = false
  const merged = list.map((next) => {
    const prev = pendingCache.get(next.orderId)
    if (prev && shallowEqual(prev, next)) return prev
    pendingCache.set(next.orderId, next)
    changed = true
    return next
  })
  for (const id of [...pendingCache.keys()]) {
    if (!list.some((order) => order.orderId === id)) {
      pendingCache.delete(id)
      changed = true
    }
  }
  return { list: merged, changed }
}

function mergePreparing(list: OrderBoardResult['preparing']): {
  list: OrderBoardResult['preparing']
  changed: boolean
} {
  let changed = false
  const merged = list.map((next) => {
    const prev = preparingCache.get(next.orderId)
    if (prev && shallowEqual(prev, next)) return prev
    preparingCache.set(next.orderId, next)
    changed = true
    return next
  })
  for (const id of [...preparingCache.keys()]) {
    if (!list.some((order) => order.orderId === id)) {
      preparingCache.delete(id)
      changed = true
    }
  }
  return { list: merged, changed }
}

/** 清理到期高亮（每轮轮询统一处理） */
function pruneHighlight(now: number): void {
  if (highlightUntil.size === 0) return
  let changed = false
  for (const [id, until] of highlightUntil) {
    if (until <= now) {
      highlightUntil.delete(id)
      changed = true
    }
  }
  if (changed) highlightedIds.value = [...highlightUntil.keys()]
}

/** 拉取看板：差集判定新单 → 播提示音 + 高亮。 */
async function refreshBoard(): Promise<void> {
  try {
    const data = await getOrderBoard()
    const now = Date.now()
    const pendingIds = new Set(data.pending.map((order) => order.orderId))
    if (knownPendingIds) {
      const fresh = [...pendingIds].filter((id) => !knownPendingIds?.has(id))
      if (fresh.length > 0) {
        playDing()
        markHighlighted(fresh, now)
      }
    }
    knownPendingIds = pendingIds
    pruneHighlight(now)

    const prev = board.value
    const pending = mergePending(data.pending)
    const preparing = mergePreparing(data.preparing)
    const todayChanged =
      prev === null ||
      prev.today.amount !== data.today.amount ||
      prev.today.orderCount !== data.today.orderCount ||
      prev.today.cupCount !== data.today.cupCount ||
      prev.today.refundAmount !== data.today.refundAmount
    // 订单列表与今日概览都没变化时保留原对象引用，卡片与 KPI 卡便不会重渲染
    if (pending.changed || preparing.changed || todayChanged) {
      board.value = { pending: pending.list, preparing: preparing.list, today: data.today }
    }
  } catch {
    // 错误提示已由 request 层直显；下一个轮询周期自动重试
  }
}

/** 新单高亮 30 秒（到期由轮询统一清理）。 */
function markHighlighted(ids: number[], now: number): void {
  for (const id of ids) highlightUntil.set(id, now + HIGHLIGHT_DURATION_MS)
  highlightedIds.value = [...highlightUntil.keys()]
}

function startPolling(): void {
  if (pollTimer !== null) return
  pollTimer = window.setInterval(() => void refreshBoard(), POLL_INTERVAL_MS)
}

function stopPolling(): void {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 页面切后台暂停轮询，切回立即刷新并恢复（LLD 7.2）。 */
function handleVisibilityChange(): void {
  if (!active) return
  if (document.hidden) {
    stopPolling()
  } else {
    void refreshBoard()
    startPolling()
  }
}

/** 行内操作统一封装：防重复点击（busy）+ 成功后立即刷新（不等 3 秒轮询）。 */
async function runAction(orderId: number, action: () => Promise<unknown>, successMessage: string): Promise<void> {
  if (busyIds.value.includes(orderId)) return
  busyIds.value = [...busyIds.value, orderId]
  try {
    await action()
    ElMessage.success(successMessage)
    await refreshBoard()
  } catch {
    // 失败提示已由 request 层直显（如 1004 状态冲突），此处仅保证 busy 复位
  } finally {
    busyIds.value = busyIds.value.filter((value) => value !== orderId)
  }
}

function handleStart(orderId: number): void {
  void runAction(orderId, () => startOrder(orderId), '已开始制作')
}

function handleComplete(orderId: number): void {
  void runAction(orderId, () => completeOrder(orderId), '已出餐')
}

/** 作废：二次确认弹窗，原因必填（对应后端 void.reason 校验）。 */
function handleVoid(orderId: number): void {
  ElMessageBox.prompt('作废后不可恢复，退款额将计入今日统计。', '作废确认', {
    confirmButtonText: '确认作废',
    cancelButtonText: '取消',
    type: 'warning',
    inputPlaceholder: '请输入作废原因（必填）',
    inputValidator: (value: string) => (value && value.trim() !== '' ? true : '作废原因不能为空')
  })
    .then(({ value }) => {
      void runAction(orderId, () => voidOrder(orderId, { reason: value.trim() }), '已作废')
    })
    .catch(() => {
      // 用户取消
    })
}

const pendingCount = computed(() => board.value?.pending.length ?? 0)
const preparingCount = computed(() => board.value?.preparing.length ?? 0)

// 挂载时与 keep-alive 重新激活时各取数一次（首屏不重复请求）
useMountOrActivateRefresh(() => {
  void ensureLoaded()
  void refreshBoard()
})

onMounted(() => {
  // 解锁 Web Audio 自动播放（新单提示音依赖用户手势后的 AudioContext）
  unlockDing()
  document.addEventListener('visibilitychange', handleVisibilityChange)
  startPolling()
})

onActivated(() => {
  active = true
  startPolling()
})

onDeactivated(() => {
  active = false
  stopPolling()
})

onBeforeUnmount(() => {
  active = false
  stopPolling()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  highlightUntil.clear()
  pendingCache.clear()
  preparingCache.clear()
})
</script>

<template>
  <div class="board-page">
    <StatBar :today="board?.today ?? null" />

    <!-- 暂停接单黄条横幅（T23）：提示顾客端不可下单，但已下单单据照常流转 -->
    <div v-if="paused" class="pause-banner">
      <span class="banner-dot" />
      <span class="banner-text">
        已暂停接单：顾客端无法新增下单（下单返回 1006）<span v-if="notice">· {{ notice }}</span>
        ；进行中订单不受影响，可照常制作与出餐。
      </span>
    </div>

    <main class="board-columns">
      <section class="board-column">
        <div class="column-head">
          <span class="column-title">
            <span class="dot pending" />
            待制作
          </span>
          <span class="column-count">{{ pendingCount }}</span>
        </div>

        <el-empty v-if="!pendingCount" description="暂无待制作订单" :image-size="72" class="app-empty" />
        <OrderCard
          v-for="order in board?.pending ?? []"
          :key="order.orderId"
          :order="order"
          mode="pending"
          :highlighted="highlightedIds.includes(order.orderId)"
          :busy="busyIds.includes(order.orderId)"
          @start="handleStart"
          @void="handleVoid"
        />
      </section>

      <section class="board-column">
        <div class="column-head">
          <span class="column-title">
            <span class="dot preparing" />
            制作中
          </span>
          <span class="column-count preparing">{{ preparingCount }}</span>
        </div>

        <el-empty v-if="!preparingCount" description="暂无制作中订单" :image-size="72" class="app-empty" />
        <OrderCard
          v-for="order in board?.preparing ?? []"
          :key="order.orderId"
          :order="order"
          mode="preparing"
          :busy="busyIds.includes(order.orderId)"
          @complete="handleComplete"
        />
      </section>
    </main>
  </div>
</template>

<style scoped>
.board-page {
  display: flex;
  flex-direction: column;
  gap: var(--gap-4);
}

.pause-banner {
  display: flex;
  align-items: center;
  gap: var(--gap-2);
  padding: 12px 16px;
  font-size: var(--fs-sm);
  color: #b88230;
  background: linear-gradient(90deg, var(--c-warning-soft) 0%, #fffaf0 100%);
  border: 1px solid rgba(224, 163, 60, 0.28);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-xs);
}

.banner-dot {
  width: 8px;
  height: 8px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--c-warning);
  animation: banner-blink 1.6s ease-in-out infinite;
}

@keyframes banner-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}

.banner-text {
  line-height: 1.6;
}

.board-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--gap-4);
  align-items: start;
}

@media (max-width: 900px) {
  .board-columns {
    grid-template-columns: 1fr;
  }
}

.board-column {
  min-height: 260px;
  padding: var(--gap-4);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.column-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap-3);
  padding-bottom: var(--gap-3);
  margin-bottom: var(--gap-3);
  border-bottom: 1px solid var(--border);
}

.column-title {
  display: flex;
  align-items: center;
  gap: var(--gap-2);
  font-size: var(--fs-h2);
  font-weight: 600;
  color: var(--text-1);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot.pending {
  background: var(--c-warning);
  box-shadow: 0 0 0 3px rgba(224, 163, 60, 0.16);
}

.dot.preparing {
  background: var(--c-success);
  box-shadow: 0 0 0 3px rgba(47, 163, 107, 0.16);
}

.column-count {
  min-width: 28px;
  padding: 0 10px;
  font-size: var(--fs-sm);
  font-weight: 600;
  line-height: 24px;
  text-align: center;
  color: #b88230;
  background: var(--c-warning-soft);
  border-radius: var(--radius-pill);
}

.column-count.preparing {
  color: #268356;
  background: var(--c-success-soft);
}
</style>
