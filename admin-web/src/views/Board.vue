<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { completeOrder, getOrderBoard, startOrder, voidOrder, type OrderBoardResult } from '@/api/order'
import { useMountOrActivateRefresh } from '@/composables/useMountOrActivateRefresh'
import { useShopStatus } from '@/composables/useShopStatus'
import { playDing, unlockDing } from '@/utils/ding'
import { subscribeBoardEvents, type BoardEventSubscription } from '@/utils/order-events'
import OrderCard from '@/components/board/OrderCard.vue'
import StatBar from '@/components/board/StatBar.vue'

/**
 * 订单看板（T19，LLD 3.5 / 7.3）：
 * - 今日概览四数 + 双分区（待制作 / 制作中）；
 * - 实时通道（T43，LLD 11.1）：优先订阅 SSE `/api/admin/board/events`，收到事件即刷新一次看板
 *   （新单提醒延迟由推送决定，远优于 5 秒要求）；SSE 不可用时自动回落 3 秒全量轮询，二者互斥；
 * - 新单判定沿用轮询时代的差集逻辑：比对前后 pending 的 orderId 差集，新增时播提示音并高亮 30 秒；
 * - 行内操作：开始制作 / 出餐（成功后立即刷新、卡片迁移分区）；作废二次确认且必填原因（仅 PAID 卡片）；
 * - visibilitychange：页面切后台关闭实时通道省流量，切回立即刷新一次并重新建连；
 * - 暂停接单状态由 useShopStatus 提供（开关在顶栏），本页只展示黄条横幅。
 *
 * 性能：拉取结果做「同内容复用旧对象引用」的差量合并，字段未变的卡片不触发重渲染；
 * 高亮到期由单一定时器按最近到期时间清理（见 schedulePrune）——SSE 下刷新由事件驱动，
 * 不能只依赖刷新触发清理。
 *
 * keep-alive：看板被路由缓存（meta.keepAlive），退出/进入走 onDeactivated / onActivated，
 * 实时通道随之关闭 / 重建；缓存期间不接收后台推送。
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
/** SSE 订阅句柄（T43）；为 null 表示当前走轮询回落通道 */
let sseSub: BoardEventSubscription | null = null
/** 是否已回落到轮询（防止 SSE 重复触发降级导致定时器叠加） */
let usingPolling = false
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

/** 新单高亮 30 秒（到期由单一定时器 + 每轮刷新双重清理）。 */
function markHighlighted(ids: number[], now: number): void {
  for (const id of ids) highlightUntil.set(id, now + HIGHLIGHT_DURATION_MS)
  highlightedIds.value = [...highlightUntil.keys()]
  schedulePrune()
}

/** 高亮到期清理定时器：SSE 下刷新由事件驱动，不能只靠刷新触发清理（单一定时器，不逐条 setTimeout） */
let pruneTimer: number | null = null

/** 按「最近到期时间」排一次清理；到期后重新排下一次。 */
function schedulePrune(): void {
  if (pruneTimer !== null || highlightUntil.size === 0) return
  const now = Date.now()
  const nearest = Math.min(...highlightUntil.values())
  pruneTimer = window.setTimeout(() => {
    pruneTimer = null
    pruneHighlight(Date.now())
    schedulePrune()
  }, Math.max(nearest - now, 0) + 16)
}

/** 清理高亮到期定时器（卸载时调用）。 */
function stopPrune(): void {
  if (pruneTimer !== null) {
    window.clearTimeout(pruneTimer)
    pruneTimer = null
  }
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

/**
 * 建立实时通道（T43）：先尝试 SSE；判定不可用时由回调切换到 3 秒轮询，二者互斥。
 * 幂等：已持通道（SSE 或已回落轮询）则直接返回——onMounted 与 onActivated 都会调用，
 * 否则首屏会「建连 → 关闭 → 再建连」白发一次请求。
 */
function startRealtime(): void {
  if (sseSub !== null || usingPolling) return
  sseSub = subscribeBoardEvents({
    onEvent: () => {
      // 任意事件都刷新一次看板：新单提示音 / 高亮由 refreshBoard 的差集逻辑统一处理
      void refreshBoard()
    },
    onFallback: () => {
      if (usingPolling) return
      usingPolling = true
      sseSub = null
      startPolling()
    }
  })
}

/** 关闭实时通道（SSE 与轮询一并停掉） */
function closeRealtime(): void {
  sseSub?.close()
  sseSub = null
  usingPolling = false
  stopPolling()
}

/** 页面切后台关闭实时通道，切回立即刷新并重新建连（LLD 7.2）。 */
function handleVisibilityChange(): void {
  if (!active) return
  if (document.hidden) {
    closeRealtime()
  } else {
    void refreshBoard()
    startRealtime()
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
  // SSE 优先（代替 main 的 startPolling）：不可用时由 onFallback 切到 3 秒轮询
  startRealtime()
})

onActivated(() => {
  active = true
  startRealtime()
})

onDeactivated(() => {
  active = false
  closeRealtime()
})

onBeforeUnmount(() => {
  active = false
  closeRealtime()
  stopPrune()
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
