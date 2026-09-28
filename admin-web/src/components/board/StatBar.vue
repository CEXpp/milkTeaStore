<script setup lang="ts">
import { computed } from 'vue'
import { CoffeeCup, RefreshLeft, Tickets, Wallet } from '@element-plus/icons-vue'
import type { Component } from 'vue'
import type { BoardTodaySummary } from '@/api/order'

/**
 * 看板今日概览（T19）：营业额 / 订单数 / 杯数 / 退款额 四数（口径 SRS 6.5）。
 * 数据随看板 3 秒轮询刷新；退款额 > 0 时以警示色提示。
 * 展示为 KPI 卡组，字段与口径完全沿用原实现。
 */
const props = defineProps<{
  today: BoardTodaySummary | null
}>()

interface Metric {
  key: string
  label: string
  value: string
  icon: Component
  tone: 'brand' | 'success' | 'warning'
}

const hasRefund = computed(() => (props.today?.refundAmount ?? '0.00') !== '0.00')

const metrics = computed<Metric[]>(() => [
  {
    key: 'amount',
    label: '营业额',
    value: props.today?.amount ?? '0.00',
    icon: Wallet,
    tone: 'brand'
  },
  {
    key: 'orderCount',
    label: '订单数',
    value: String(props.today?.orderCount ?? 0),
    icon: Tickets,
    tone: 'success'
  },
  {
    key: 'cupCount',
    label: '售出杯数',
    value: String(props.today?.cupCount ?? 0),
    icon: CoffeeCup,
    tone: 'success'
  },
  {
    key: 'refundAmount',
    label: '退款额',
    value: props.today?.refundAmount ?? '0.00',
    icon: RefreshLeft,
    tone: hasRefund.value ? 'warning' : 'success'
  }
])
</script>

<template>
  <div class="stat-bar">
    <div v-for="metric in metrics" :key="metric.key" class="metric-card" :class="`tone-${metric.tone}`">
      <span class="metric-icon">
        <el-icon :size="18"><component :is="metric.icon" /></el-icon>
      </span>
      <div class="metric-body">
        <span class="metric-label">{{ metric.label }}</span>
        <span class="metric-value">{{ metric.value }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stat-bar {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--gap-4);
}

@media (max-width: 1180px) {
  .stat-bar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.metric-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--gap-3);
  padding: 14px 16px;
  overflow: hidden;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  transition:
    transform var(--dur-base) var(--ease-out),
    box-shadow var(--dur-base) var(--ease-out);
}

.metric-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-hover);
}

/* 左侧色条：区分指标语义 */
.metric-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 16px;
  bottom: 16px;
  width: 3px;
  border-radius: var(--radius-pill);
}

.tone-brand::before {
  background: linear-gradient(180deg, var(--brand-400) 0%, var(--brand-600) 100%);
}

.tone-success::before {
  background: linear-gradient(180deg, #4ec38c 0%, var(--c-success) 100%);
}

.tone-warning::before {
  background: linear-gradient(180deg, #f0c274 0%, var(--c-warning) 100%);
}

.metric-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  border-radius: var(--radius-md);
}

.tone-brand .metric-icon {
  color: var(--brand-500);
  background: var(--brand-050);
}

.tone-success .metric-icon {
  color: var(--c-success);
  background: var(--c-success-soft);
}

.tone-warning .metric-icon {
  color: #b88230;
  background: var(--c-warning-soft);
}

.metric-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.metric-label {
  font-size: var(--fs-xs);
  color: var(--text-2);
}

.metric-value {
  margin-top: 2px;
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: 0.2px;
  color: var(--text-1);
}

.tone-warning .metric-value {
  color: #b88230;
}
</style>
