<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getStatsSummary, getStatsTrend, type StatsSummary, type StatsTrendItem } from '@/api/stats'
import type { OrderSource } from '@/api/order'
import { useMountOrActivateRefresh } from '@/composables/useMountOrActivateRefresh'
import AdminPageHeader from '@/components/AdminPageHeader.vue'
import StatBar from '@/components/board/StatBar.vue'
import TrendChart from '@/components/stats/TrendChart.vue'
import RankTable from '@/components/stats/RankTable.vue'
import OrderFlow from '@/components/stats/OrderFlow.vue'

/**
 * 账台统计页（T35，LLD 3.5.4）：
 * - 概览四卡（营业额 / 订单数 / 杯数 / 退款额）+ 渠道分布小卡；
 * - 近 7 日趋势（ECharts 双轴）；
 * - 商品销量排行（自带今日 / 近 7 日切换）；
 * - 按日订单流水明细（跟随页面日期，含异常单与作废原因）。
 *
 * 口径以后端为准（T34 已把口径常量化在 StatsMapper）：营业额为「有效已支付」、
 * 订单数含作废、退款额单列。本页只做展示，不做二次计算。
 */

defineOptions({ name: 'Stats' })

/** 趋势固定看近 7 日（施工卡「近 7 日趋势」） */
const TREND_DAYS = 7

const SOURCE_LABEL: Record<OrderSource, string> = {
  MINI_PROGRAM: '小程序',
  AI: 'AI',
  COUNTER: '柜台'
}

/** 渠道卡片配色（小程序 / AI / 柜台） */
const SOURCE_TONE: Record<string, 'brand' | 'success' | 'warning'> = {
  MINI_PROGRAM: 'brand',
  AI: 'warning',
  COUNTER: 'success'
}

/** 本地「今天」yyyy-MM-dd：用本地时区拼装，避免 toISOString 的 UTC 偏移串日 */
function todayLocal(): string {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

const date = ref(todayLocal())
const summary = ref<StatsSummary | null>(null)
const trend = ref<StatsTrendItem[]>([])
const loading = ref(false)

/** 适配看板 KPI 卡：字段名对齐 BoardTodaySummary（amount ← totalAmount） */
const todayMetric = computed(() =>
  summary.value
    ? {
        orderCount: summary.value.orderCount,
        amount: summary.value.totalAmount,
        cupCount: summary.value.cupCount,
        refundAmount: summary.value.refundAmount
      }
    : null
)

/** 渠道占比条：以单量最大值为基准，仅用于视觉长度，不改变数值口径 */
const channelMax = computed(() =>
  Math.max(1, ...(summary.value?.channel ?? []).map((item) => item.orderCount))
)

async function loadSummary(): Promise<void> {
  loading.value = true
  try {
    summary.value = await getStatsSummary({ date: date.value })
  } catch {
    // 错误提示已由 request 层直显；保留上一次结果，避免页面闪空
  } finally {
    loading.value = false
  }
}

async function loadTrend(): Promise<void> {
  try {
    trend.value = await getStatsTrend({ days: TREND_DAYS })
  } catch {
    // 同上
  }
}

function refresh(): void {
  void loadSummary()
  void loadTrend()
}

// 挂载时与 keep-alive 重新激活时各取数一次，保持「进入即取数」的行为
useMountOrActivateRefresh(refresh)
watch(date, () => void loadSummary())
</script>

<template>
  <div class="stats-page">
    <AdminPageHeader title="账台统计" :subtitle="`统计日期 ${summary?.date ?? date}`">
      <el-date-picker
        v-model="date"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="选择统计日期"
        size="small"
        :clearable="false"
        class="date-picker"
      />
      <el-button size="small" type="primary" plain :icon="Refresh" :loading="loading" @click="refresh">
        刷新
      </el-button>
    </AdminPageHeader>

    <StatBar :today="todayMetric" />

    <div class="stats-grid">
      <el-card shadow="never" class="channel-card">
        <template #header>
          <div class="card-head">
            <span class="card-title">渠道分布</span>
            <span class="card-sub">{{ summary?.date ?? date }}</span>
          </div>
        </template>
        <el-empty v-if="!(summary?.channel.length)" description="该日各渠道均无成交" :image-size="60" class="app-empty" />
        <div v-else class="channel-list">
          <div
            v-for="item in summary?.channel ?? []"
            :key="item.source"
            class="channel-item"
            :class="`tone-${SOURCE_TONE[item.source] ?? 'brand'}`"
          >
            <div class="channel-top">
              <span class="channel-name">{{ SOURCE_LABEL[item.source] ?? item.source }}</span>
              <span class="channel-count">{{ item.orderCount }} 单</span>
            </div>
            <div class="channel-bar">
              <span class="bar-fill" :style="{ width: `${(item.orderCount / channelMax) * 100}%` }" />
            </div>
            <div class="channel-amount">{{ item.amount }} 元</div>
          </div>
        </div>
      </el-card>

      <el-card shadow="never" class="trend-card">
        <template #header>
          <div class="card-head">
            <span class="card-title">近 {{ TREND_DAYS }} 日趋势</span>
            <span class="card-sub">左轴营业额 · 右轴订单数</span>
          </div>
        </template>
        <TrendChart :items="trend" />
      </el-card>
    </div>

    <div class="bottom-grid">
      <RankTable />
      <OrderFlow :date="date" />
    </div>
  </div>
</template>

<style scoped>
.stats-page {
  display: flex;
  flex-direction: column;
  gap: var(--gap-4);
}

.date-picker {
  width: 150px;
}

.stats-grid {
  display: grid;
  grid-template-columns: minmax(280px, 360px) minmax(0, 1fr);
  gap: var(--gap-4);
  align-items: start;
}

@media (max-width: 1280px) {
  .stats-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap-3);
}

.card-title {
  font-size: var(--fs-h2);
  font-weight: 600;
  color: var(--text-1);
}

.card-sub {
  font-size: var(--fs-xs);
  color: var(--text-3);
}

.channel-list {
  display: flex;
  flex-direction: column;
  gap: var(--gap-3);
}

.channel-item {
  padding: 12px 14px;
  border-radius: var(--radius-md);
  background: var(--bg-subtle);
  border: 1px solid var(--border);
}

.channel-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.channel-name {
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--text-1);
}

.channel-count {
  font-size: var(--fs-xs);
  color: var(--text-2);
}

.channel-bar {
  height: 6px;
  margin: 8px 0 6px;
  overflow: hidden;
  background: #e9edf5;
  border-radius: var(--radius-pill);
}

.bar-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  transition: width var(--dur-base) var(--ease-out);
}

.tone-brand .bar-fill {
  background: linear-gradient(90deg, var(--brand-400) 0%, var(--brand-600) 100%);
}

.tone-success .bar-fill {
  background: linear-gradient(90deg, #4ec38c 0%, var(--c-success) 100%);
}

.tone-warning .bar-fill {
  background: linear-gradient(90deg, #f0c274 0%, var(--c-warning) 100%);
}

.channel-amount {
  font-size: 17px;
  font-weight: 600;
  color: var(--text-1);
  font-variant-numeric: tabular-nums;
}

.trend-card {
  min-width: 0;
}

.bottom-grid {
  display: grid;
  grid-template-columns: minmax(320px, 460px) minmax(0, 1fr);
  gap: var(--gap-4);
  align-items: start;
}

@media (max-width: 1280px) {
  .bottom-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
