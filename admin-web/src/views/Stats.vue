<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { getStatsSummary, getStatsTrend, type StatsSummary, type StatsTrendItem } from '@/api/stats'
import type { OrderSource } from '@/api/order'
import AdminPageHeader from '@/components/AdminPageHeader.vue'
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

/** 趋势固定看近 7 日（施工卡「近 7 日趋势」） */
const TREND_DAYS = 7

const SOURCE_LABEL: Record<OrderSource, string> = {
  MINI_PROGRAM: '小程序',
  AI: 'AI',
  COUNTER: '柜台'
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

onMounted(refresh)
watch(date, () => void loadSummary())
</script>

<template>
  <div class="stats-page">
    <AdminPageHeader title="账台统计">
      <el-date-picker
        v-model="date"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="选择统计日期"
        size="small"
        :clearable="false"
      />
      <el-button size="small" type="primary" plain :loading="loading" @click="refresh">刷新</el-button>
    </AdminPageHeader>

    <el-row :gutter="12" class="overview">
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="metric-card">
          <div class="metric-label">营业额(元)</div>
          <div class="metric-value primary">{{ summary?.totalAmount ?? '0.00' }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="metric-card">
          <div class="metric-label">订单数</div>
          <div class="metric-value">{{ summary?.orderCount ?? 0 }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="metric-card">
          <div class="metric-label">售出杯数</div>
          <div class="metric-value">{{ summary?.cupCount ?? 0 }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="metric-card">
          <div class="metric-label">退款额(元)</div>
          <div class="metric-value danger">{{ summary?.refundAmount ?? '0.00' }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="channel-card">
      <template #header>
        <div class="card-head">
          <span class="card-title">渠道分布</span>
          <span class="card-sub">{{ summary?.date ?? date }}</span>
        </div>
      </template>
      <el-empty v-if="!(summary?.channel.length)" description="该日各渠道均无成交" :image-size="60" />
      <div v-else class="channel-list">
        <div v-for="item in summary?.channel ?? []" :key="item.source" class="channel-item">
          <div class="channel-name">{{ SOURCE_LABEL[item.source] ?? item.source }}</div>
          <div class="channel-count">{{ item.orderCount }} 单</div>
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

    <div class="bottom-grid">
      <RankTable />
      <OrderFlow :date="date" />
    </div>
  </div>
</template>

<style scoped>
.stats-page {
  padding: 16px;
}

.overview {
  margin-bottom: 12px;
}

.metric-card {
  margin-bottom: 12px;
}

.metric-label {
  color: #909399;
  font-size: 13px;
}

.metric-value {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
}

.metric-value.primary {
  color: #409eff;
}

.metric-value.danger {
  color: #f56c6c;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-title {
  font-weight: 600;
}

.card-sub {
  color: #909399;
  font-size: 12px;
}

.channel-card,
.trend-card {
  margin-bottom: 12px;
}

.channel-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.channel-item {
  flex: 1 1 160px;
  padding: 12px;
  border-radius: 6px;
  background: #f5f7fa;
}

.channel-name {
  font-weight: 600;
}

.channel-count {
  margin-top: 4px;
  color: #909399;
  font-size: 13px;
}

.channel-amount {
  margin-top: 2px;
  font-size: 16px;
}

.bottom-grid {
  display: grid;
  gap: 12px;
}
</style>
