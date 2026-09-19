<script setup lang="ts">
import type { BoardTodaySummary } from '@/api/order'

/**
 * 看板顶栏今日概览（T19）：营业额 / 订单数 / 杯数 / 退款额 四数（口径 SRS 6.5）。
 * 数据随看板 3 秒轮询刷新；退款额 > 0 时以警示色提示。
 */
defineProps<{
  today: BoardTodaySummary | null
}>()
</script>

<template>
  <div class="stat-bar">
    <div class="stat-item">
      <span class="stat-label">营业额</span>
      <span class="stat-value amount">{{ today?.amount ?? '0.00' }}</span>
    </div>
    <div class="stat-item">
      <span class="stat-label">订单数</span>
      <span class="stat-value">{{ today?.orderCount ?? 0 }}</span>
    </div>
    <div class="stat-item">
      <span class="stat-label">杯数</span>
      <span class="stat-value">{{ today?.cupCount ?? 0 }}</span>
    </div>
    <div class="stat-item">
      <span class="stat-label">退款额</span>
      <span class="stat-value" :class="{ refund: (today?.refundAmount ?? '0.00') !== '0.00' }">
        {{ today?.refundAmount ?? '0.00' }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.stat-bar {
  display: flex;
  gap: 24px;
}

.stat-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.stat-label {
  font-size: 12px;
  color: #909399;
}

.stat-value {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.stat-value.amount {
  color: #f56c6c;
}

.stat-value.refund {
  color: #e6a23c;
}
</style>
