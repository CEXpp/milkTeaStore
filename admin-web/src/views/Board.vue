<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  completeOrder,
  getOrderBoard,
  getOrderChecklist,
  startOrder,
  voidOrder,
  type BoardSlaSettings,
  type OrderBoardResult,
  type OrderChecklist
} from '@/api/order'
import { getQueuePace, type QueuePace } from '@/api/queue'
import { updateSla } from '@/api/shop'
import { useMountOrActivateRefresh } from '@/composables/useMountOrActivateRefresh'
import { useShopStatus } from '@/composables/useShopStatus'
import { playDing, unlockDing } from '@/utils/ding'
import { subscribeBoardEvents, type BoardEventSubscription } from '@/utils/order-events'
import OrderCard from '@/components/board/OrderCard.vue'
import StatBar from '@/components/board/StatBar.vue'
import DailyReportCard from '@/components/board/DailyReportCard.vue'
import ForecastBanner from '@/components/board/ForecastBanner.vue'

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

const { paused, notice, ensureLoaded, setPaused } = useShopStatus()

const board = ref<OrderBoardResult | null>(null)
/** 接单节奏建议（T48）：只读提示——超阈值仅「建议」暂停，系统绝不自动执行 */
const pace = ref<QueuePace | null>(null)

/** SLA 阈值（T52）：随看板响应下发；单独存一份，避免受 board 的差量合并影响 */
const sla = ref<BoardSlaSettings>({ warnSeconds: 300, dangerSeconds: 600 })
/** 爆单预测横幅（T69）：由看板的既有轮询带着刷新，不额外起定时器 */
const forecastBanner = ref<InstanceType<typeof ForecastBanner> | null>(null)
/** 秒级时间戳：统一驱动各卡片的等待时长 mm:ss（卡片不各自起定时器） */
const nowTs = ref(Date.now())
let tickTimer: number | null = null

/** SLA 阈值设置弹窗（T52） */
const slaDialogVisible = ref(false)
const slaSaving = ref(false)
const slaForm = reactive({ warnSeconds: 300, dangerSeconds: 600 })

/** 出餐核对清单（T58）：出餐前逐项勾选，全勾完才允许确认——纯前端防错，不动状态机 */
const checklistVisible = ref(false)
const checklistLoading = ref(false)
const checklistOrderId = ref<number | null>(null)
const checklist = ref<OrderChecklist | null>(null)
const checkedKeys = ref<string[]>([])
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
    // SLA 阈值随响应下发（T52）：单独存一份，商家改阈值后下一次刷新立即生效
    sla.value = data.sla

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
      board.value = {
        pending: pending.list,
        preparing: preparing.list,
        today: data.today,
        // 契约要求该字段存在；预警实际读的是上面单独的 sla ref（不受差量合并影响）
        sla: data.sla
      }
    }
    // 顺带刷新接单节奏（T48）：失败静默，不污染看板主流程
    void loadPace()
    // 顺带刷新爆单预测（T69）：与节奏同频；组件自己选「非正常才展示」，这里不做判断
    void loadForecast()
  } catch {
    // 错误提示已由 request 层直显；下一个轮询周期自动重试
  }
}

/** 爆单预测（T69）：只读建议，刷新失败不打扰店长。 */
async function loadForecast(): Promise<void> {
  await forecastBanner.value?.load()
}

/** 接单节奏建议（T48）：只读展示，刷新失败不打扰店长。 */
async function loadPace(): Promise<void> {
  try {
    pace.value = await getQueuePace()
  } catch {
    pace.value = null
  }
}

/** 秒级心跳（T52）：只更新一个时间戳，各卡片据此自算等待时长，避免每张卡片各起定时器 */
function startTick(): void {
  if (tickTimer !== null) {
    return
  }
  tickTimer = window.setInterval(() => {
    nowTs.value = Date.now()
  }, 1000)
}

function stopTick(): void {
  if (tickTimer !== null) {
    window.clearInterval(tickTimer)
    tickTimer = null
  }
}

/** 打开 SLA 阈值设置（T52）：以当前生效值为初始值 */
function openSlaDialog(): void {
  slaForm.warnSeconds = sla.value.warnSeconds
  slaForm.dangerSeconds = sla.value.dangerSeconds
  slaDialogVisible.value = true
}

/**
 * 保存 SLA 阈值（T52）：写入 shop_config 后由后端随看板下发，
 * 故保存后立即刷新一次看板，预警即刻按新阈值显示（验收项「阈值改动后预警即时生效」）。
 */
async function saveSla(): Promise<void> {
  if (slaForm.dangerSeconds <= slaForm.warnSeconds) {
    ElMessage.warning('转红阈值必须大于转黄阈值')
    return
  }
  slaSaving.value = true
  try {
    sla.value = await updateSla({
      warnSeconds: slaForm.warnSeconds,
      dangerSeconds: slaForm.dangerSeconds
    })
    slaDialogVisible.value = false
    ElMessage.success('SLA 阈值已更新')
    void refreshBoard()
  } catch {
    // 错误提示已由 request 层直显（如 1001 阈值不合法）
  } finally {
    slaSaving.value = false
  }
}

/**
 * 「去暂停接单」（T48）：把建议引导到**人工动作**上，并做二次确认。
 *
 * 任务卡验收项「任何情况下不自动暂停接单」在此落地：系统只给建议 + 引导，
 * 真正的暂停必须由店长在确认框里点「暂停接单」才会发生（复用 4.6 的同一开关，
 * 因此不产生任何新的「系统拒单」记录，也不影响 6.5 统计口径）。
 */
function handleSuggestPause(): void {
  ElMessageBox.confirm(
    `当前队列 ${pace.value?.totalCups ?? 0} 杯，此刻新单预计等待 ${pace.value?.etaMinutes ?? 0} 分钟。是否暂停接单？`,
    '接单节奏建议',
    { confirmButtonText: '暂停接单', cancelButtonText: '先不暂停', type: 'warning' }
  )
    .then(() => {
      void setPaused(true)
    })
    .catch(() => {
      // 店长选择不暂停：尊重决定，本轮不再打扰
    })
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

/**
 * 清单扁平行（T58）：每个订单项的每个规格一行，逐项可勾。
 *
 * memberTag（T64/W14）：团单时带上成员标识，出餐喊「003 王工」；
 * 非团单为 null，模板里不渲染 —— 单人单界面与引入团单前完全一致（零干扰）。
 */
const checklistLines = computed(() => {
  const lines: Array<{ key: string; text: string; memberTag: string | null }> = []
  checklist.value?.items.forEach((item, itemIndex) => {
    item.options.forEach((option, optionIndex) => {
      lines.push({
        key: `${itemIndex}-${optionIndex}`,
        text: `${item.productName} ×${item.quantity} · ${option.groupName}：${option.optionName}`,
        memberTag: item.memberTag
      })
    })
  })
  return lines
})

/**
 * 制作指引（T60，W22）：按商品分组的当前描述，插在该商品规格行之上。
 *
 * 刻意与规格行分开呈现：规格是「本单要做什么」（快照，逐项打勾核对），
 * 描述是「一直怎么做」（当前 SOP，说明性文本，不参与勾选）。
 */
const checklistGuides = computed(() =>
  (checklist.value?.items ?? [])
    .map((item, itemIndex) => ({
      key: `guide-${itemIndex}`,
      productName: `${item.productName} ×${item.quantity}`,
      description: item.description
    }))
    .filter((guide) => Boolean(guide.description))
)

/** 全部勾完才允许确认出餐；无规格明细时视为已勾完，避免无谓卡死 */
const checklistAllChecked = computed(
  () => checklistLines.value.length === 0 || checkedKeys.value.length >= checklistLines.value.length
)

/**
 * 出餐（T58）：先拉核对清单并弹窗逐项勾选，全部勾完才允许确认。
 *
 * 说明：这只是**前端防错交互**——后端出餐仍按 6.1 原规则校验，状态机规则一字未改
 * （任务卡「确认不是状态迁移的前置条件」）。
 */
async function handleComplete(orderId: number): Promise<void> {
  if (checklistLoading.value) {
    return
  }
  checklistLoading.value = true
  try {
    checklist.value = await getOrderChecklist(orderId)
    checklistOrderId.value = orderId
    checkedKeys.value = []
    checklistVisible.value = true
  } catch {
    // 拉取失败已由 request 层提示；此处不直接出餐，避免跳过核对
  } finally {
    checklistLoading.value = false
  }
}

/** 确认出餐：走原有动作通道（含 busy 防抖 + 成功后刷新看板） */
function confirmComplete(): void {
  const orderId = checklistOrderId.value
  checklistVisible.value = false
  checklist.value = null
  checklistOrderId.value = null
  if (orderId === null) {
    return
  }
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
  // 秒级心跳：驱动 SLA 等待时长刷新（T52）
  startTick()
})

onActivated(() => {
  active = true
  startRealtime()
  startTick()
})

onDeactivated(() => {
  active = false
  closeRealtime()
  stopTick()
})

onBeforeUnmount(() => {
  active = false
  closeRealtime()
  stopPrune()
  stopTick()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  highlightUntil.clear()
  pendingCache.clear()
  preparingCache.clear()
})
</script>

<template>
  <div class="board-page">
    <StatBar :today="board?.today ?? null" />

    <!-- SLA 预警阈值入口（T52）：阈值随看板响应下发，改完下一次刷新即生效 -->
    <div class="board-toolbar">
      <span class="toolbar-tip">
        SLA 预警：{{ sla.warnSeconds }} 秒转黄 / {{ sla.dangerSeconds }} 秒转红并置顶
      </span>
      <el-button link type="primary" size="small" @click="openSlaDialog">修改阈值</el-button>
    </div>

    <!-- 动态接单节奏（T48）：按队列阈值提示压力；超阈值仅「建议」暂停，系统绝不自动执行 -->
    <div
      v-if="pace && pace.level !== 'NORMAL'"
      class="pace-banner"
      :class="`pace-${pace.level.toLowerCase()}`"
    >
      <div class="pace-main">
        <span class="pace-tag">{{ pace.level === 'OVERLOAD' ? '拥挤' : '偏忙' }}</span>
        <span class="pace-text">{{ pace.suggestion }}</span>
      </div>
      <el-button
        v-if="pace.suggestPause && !paused"
        type="danger"
        size="small"
        plain
        @click="handleSuggestPause"
      >
        去暂停接单
      </el-button>
    </div>

    <!-- 爆单预测（T69）：预测「接下来一段时间」的单量，与上面 T48「现在压了多少杯」是两件事，
         故分开展示。横幅里没有「一键暂停」按钮——预测若带动作入口就会被当成已生效。
         刷新钩子交给看板的既有轮询带着跑，不额外起定时器。 -->
    <ForecastBanner ref="forecastBanner" />

    <!-- 每日经营日报（T68）：打烊后生成的口语化日报 + 异常预警；数字与账台统计同源 -->
    <DailyReportCard />

    <!-- 暂停接单黄条横幅（T23）：提示顾客端不可下单，但已下单单据照常流转。
         T62 起 notice 是「营业公告」，店长可自主编辑；暂停原因建议一并写进公告。 -->
    <div v-if="paused" class="pause-banner">
      <span class="banner-dot" />
      <span class="banner-text">
        已暂停接单：顾客端无法新增下单（下单返回 1006）<span v-if="notice">· 当前公告：{{ notice }}</span>
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
          :now-ts="nowTs"
          :warn-seconds="sla.warnSeconds"
          :danger-seconds="sla.dangerSeconds"
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

    <!-- SLA 阈值设置（T52）：写入 shop_config，保存后看板立即按新阈值预警 -->
    <el-dialog v-model="slaDialogVisible" title="SLA 预警阈值" width="380px">
      <el-form label-width="120px">
        <el-form-item label="转黄阈值（秒）">
          <el-input-number v-model="slaForm.warnSeconds" :min="10" :max="86400" :step="30" />
        </el-form-item>
        <el-form-item label="转红阈值（秒）">
          <el-input-number v-model="slaForm.dangerSeconds" :min="10" :max="86400" :step="30" />
        </el-form-item>
        <p class="sla-tip">转红须大于转黄；达到转红即在待制作区置顶。</p>
      </el-form>
      <template #footer>
        <el-button @click="slaDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="slaSaving" @click="saveSla">保存</el-button>
      </template>
    </el-dialog>

    <!-- 出餐核对清单（T58）：逐项勾选，全勾完才可确认。纯前端防错，后端状态机规则不变 -->
    <el-dialog v-model="checklistVisible" title="出餐核对" width="440px">
      <div v-if="checklist" class="checklist">
        <div class="checklist-head">
          <span>取餐码 <strong>{{ checklist.pickupCode }}</strong></span>
          <span v-if="checklist.remarkTagCount > 0" class="special-tag">
            本单有 {{ checklist.remarkTagCount }} 项特殊要求
          </span>
        </div>
        <p v-if="checklist.remark" class="checklist-remark">备注：{{ checklist.remark }}</p>
        <!-- 制作指引（T60）：店长的商品描述，出餐时按它做；无描述的商品不显示 -->
        <div v-if="checklistGuides.length" class="checklist-guides">
          <div v-for="guide in checklistGuides" :key="guide.key" class="guide-item">
            <span class="guide-title">{{ guide.productName }}</span>
            <span class="guide-text">{{ guide.description }}</span>
          </div>
        </div>
        <el-checkbox-group v-model="checkedKeys" class="checklist-body">
          <el-checkbox v-for="line in checklistLines" :key="line.key" :label="line.key">
            <!-- 团单成员标识（T64/W14）：出餐喊「003 王工」。非团单为 null 就不渲染 -->
            <span v-if="line.memberTag" class="checklist-member">{{ line.memberTag }}</span>
            {{ line.text }}
          </el-checkbox>
        </el-checkbox-group>
        <p v-if="!checklistLines.length" class="checklist-empty">该单无规格明细</p>
      </div>
      <template #footer>
        <el-button @click="checklistVisible = false">取消</el-button>
        <el-button type="success" :disabled="!checklistAllChecked" @click="confirmComplete">
          确认出餐
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.board-page {
  display: flex;
  flex-direction: column;
  gap: var(--gap-4);
}

/* SLA 阈值工具栏（T52）：只读展示当前阈值 + 一个设置入口 */
.board-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap-3);
  padding: 8px 14px;
  font-size: var(--fs-sm);
  color: var(--text-2);
  background: var(--bg-subtle);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.toolbar-tip {
  line-height: 1.6;
}

.sla-tip {
  margin: 0;
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--text-3);
}

/* 出餐核对清单（T58） */
.checklist-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap-2);
  margin-bottom: var(--gap-2);
}

.special-tag {
  padding: 2px 10px;
  font-size: var(--fs-xs);
  font-weight: 600;
  color: #fff;
  background: var(--c-danger);
  border-radius: var(--radius-pill);
}

.checklist-remark {
  margin: 0 0 var(--gap-2);
  font-size: var(--fs-sm);
  color: var(--text-2);
}

/* 制作指引（T60）：与规格勾选区分开——说明性文本，不参与勾选 */
.checklist-guides {
  display: flex;
  flex-direction: column;
  gap: var(--gap-1);
  max-height: 160px;
  margin-bottom: var(--gap-3);
  padding: var(--gap-2);
  overflow-y: auto;
  background: var(--brand-050);
  border-radius: var(--radius-md);
}

.guide-item {
  display: flex;
  gap: var(--gap-2);
  font-size: var(--fs-xs);
  line-height: 1.6;
}

.guide-title {
  flex-shrink: 0;
  font-weight: 600;
  color: var(--text-1);
}

.guide-text {
  color: var(--text-2);
}

.checklist-body {
  display: flex;
  flex-direction: column;
  gap: var(--gap-1);
  max-height: 320px;
  overflow-y: auto;
}

/* 团单成员标识（T64/W14）：靛蓝底衬托，出餐时一眼看到这杯是谁的 */
.checklist-member {
  display: inline-block;
  padding: 1px 8px;
  margin-right: 6px;
  font-size: var(--fs-xs);
  font-weight: 600;
  color: #fff;
  background: var(--brand-500);
  border-radius: var(--radius-pill);
}

.checklist-empty {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--text-3);
}

/* 动态接单节奏横幅（T48）：偏忙=黄、拥挤=红；只提示、不执行 */
.pace-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap-3);
  padding: 12px 16px;
  font-size: var(--fs-sm);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
}

.pace-busy {
  color: #b88230;
  background: var(--c-warning-soft);
  border-color: rgba(224, 163, 60, 0.28);
}

.pace-overload {
  color: #b93027;
  background: var(--c-danger-soft);
  border-color: rgba(224, 87, 79, 0.3);
}

.pace-main {
  display: flex;
  align-items: center;
  gap: var(--gap-2);
  min-width: 0;
}

.pace-tag {
  flex-shrink: 0;
  padding: 2px 10px;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.72);
  border-radius: var(--radius-pill);
}

.pace-text {
  line-height: 1.6;
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
