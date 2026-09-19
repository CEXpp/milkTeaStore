<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { completeOrder, getOrderBoard, startOrder, voidOrder, type OrderBoardResult } from '@/api/order'
import { useAuthStore } from '@/stores/auth'
import { playDing, unlockDing } from '@/utils/ding'
import OrderCard from '@/components/board/OrderCard.vue'
import StatBar from '@/components/board/StatBar.vue'

/**
 * 订单看板（T19，LLD 3.5 / 7.3）：
 * - 双分区（待制作 / 制作中）+ 顶栏今日概览四数；
 * - 3 秒轮询 board 接口：比对前后 pending 的 orderId 差集，新增时播提示音并高亮 30 秒；
 * - 行内操作：开始制作 / 出餐（成功后立即刷新、卡片迁移分区）；作废二次确认且必填原因（仅 PAID 卡片）；
 * - visibilitychange：页面切后台暂停轮询省流量，切回立即刷新一次并恢复。
 */

const POLL_INTERVAL_MS = 3000
/** 新单高亮时长（任务卡：新卡片高亮 30 秒） */
const HIGHLIGHT_DURATION_MS = 30_000

const router = useRouter()
const authStore = useAuthStore()

const board = ref<OrderBoardResult | null>(null)
const highlightedIds = ref<number[]>([])
const busyIds = ref<number[]>([])

let pollTimer: number | null = null
/** 上一轮 pending 的 orderId 集合；null 表示首轮加载（首屏不播提示音、不高亮） */
let knownPendingIds: Set<number> | null = null
const highlightTimers = new Map<number, number>()

/** 拉取看板：差集判定新单 → 播提示音 + 高亮。 */
async function refreshBoard(): Promise<void> {
  try {
    const data = await getOrderBoard()
    const pendingIds = new Set(data.pending.map((order) => order.orderId))
    if (knownPendingIds) {
      const fresh = [...pendingIds].filter((id) => !knownPendingIds?.has(id))
      if (fresh.length > 0) {
        playDing()
        markHighlighted(fresh)
      }
    }
    knownPendingIds = pendingIds
    board.value = data
  } catch {
    // 错误提示已由 request 层直显；下一个轮询周期自动重试
  }
}

/** 新单高亮 30 秒（到期自动移除）。 */
function markHighlighted(ids: number[]): void {
  highlightedIds.value = [...highlightedIds.value, ...ids]
  for (const id of ids) {
    const timer = window.setTimeout(() => {
      highlightedIds.value = highlightedIds.value.filter((value) => value !== id)
      highlightTimers.delete(id)
    }, HIGHLIGHT_DURATION_MS)
    highlightTimers.set(id, timer)
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

/** 页面切后台暂停轮询，切回立即刷新并恢复（LLD 7.2）。 */
function handleVisibilityChange(): void {
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

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
  } catch {
    return
  }
  authStore.logout()
  await router.replace('/login')
}

onMounted(() => {
  // 解锁 Web Audio 自动播放（新单提示音依赖用户手势后的 AudioContext）
  unlockDing()
  void refreshBoard()
  startPolling()
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onBeforeUnmount(() => {
  stopPolling()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  highlightTimers.forEach((timer) => window.clearTimeout(timer))
  highlightTimers.clear()
})
</script>

<template>
  <div class="board-page">
    <header class="board-topbar">
      <div class="topbar-left">
        <span class="board-title">订单看板</span>
        <StatBar :today="board?.today ?? null" />
      </div>
      <div class="topbar-right">
        <el-button class="counter-entry" type="primary" plain @click="router.push('/counter')">柜台点单</el-button>
        <span class="board-user">{{ authStore.nickname || '店长' }}</span>
        <el-button link type="primary" @click="handleLogout">退出登录</el-button>
      </div>
    </header>

    <main class="board-columns">
      <section class="board-column">
        <h2 class="column-title">
          待制作
          <span class="column-count">{{ board?.pending.length ?? 0 }}</span>
        </h2>
        <el-empty v-if="!(board?.pending.length)" description="暂无待制作订单" :image-size="72" />
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
        <h2 class="column-title">
          制作中
          <span class="column-count preparing">{{ board?.preparing.length ?? 0 }}</span>
        </h2>
        <el-empty v-if="!(board?.preparing.length)" description="暂无制作中订单" :image-size="72" />
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
  min-height: 100%;
  padding: 16px;
  box-sizing: border-box;
}

.board-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  padding: 12px 16px;
  margin-bottom: 16px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 32px;
  flex-wrap: wrap;
}

.board-title {
  font-size: 18px;
  font-weight: 600;
}

.topbar-right {
  display: flex;
  align-items: center;
}

.counter-entry {
  margin-right: 12px;
}

.board-user {
  margin-right: 12px;
  color: #606266;
}

.board-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  align-items: start;
}

@media (max-width: 900px) {
  .board-columns {
    grid-template-columns: 1fr;
  }
}

.board-column {
  min-height: 240px;
  padding: 12px;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 8px;
}

.column-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.column-count {
  min-width: 24px;
  padding: 0 8px;
  font-size: 13px;
  font-weight: 600;
  line-height: 22px;
  text-align: center;
  color: #e6a23c;
  background: #fdf6ec;
  border-radius: 11px;
}

.column-count.preparing {
  color: #67c23a;
  background: #f0f9eb;
}
</style>
