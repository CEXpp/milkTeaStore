<script setup lang="ts">
import { computed } from 'vue'
import type { BoardPendingOrder, BoardPreparingOrder } from '@/api/order'

/**
 * 看板订单卡片（T19，LLD 7.3）：取餐码大字 + 渠道标签 + 商品摘要 + 合计 + 等待/制作分钟数。
 * pending 卡片提供「开始制作 / 作废」，preparing 卡片提供「出餐」；新单高亮由父级传入。
 * 高亮动画走伪元素 opacity（合成层），避免 box-shadow 逐帧重绘。
 */
const props = defineProps<{
  order: BoardPendingOrder | BoardPreparingOrder
  mode: 'pending' | 'preparing'
  /** 新单到达后高亮 30 秒（LLD 7.3） */
  highlighted?: boolean
  /** 行内操作进行中（防重复点击） */
  busy?: boolean
}>()

const emit = defineEmits<{
  (e: 'start', orderId: number): void
  (e: 'complete', orderId: number): void
  (e: 'void', orderId: number): void
}>()

/** 渠道标签（LLD 3.5：小程序 / AI / 柜台） */
const SOURCE_LABELS: Record<string, string> = {
  MINI_PROGRAM: '小程序',
  AI: 'AI 点单',
  COUNTER: '柜台'
}

const sourceLabel = computed(() => SOURCE_LABELS[props.order.source] ?? props.order.source)

const minutesText = computed(() => {
  if (props.mode === 'pending') {
    return `等待 ${(props.order as BoardPendingOrder).minutesWaiting} 分钟`
  }
  return `制作 ${(props.order as BoardPreparingOrder).minutesPreparing} 分钟`
})
</script>

<template>
  <div class="order-card" :class="{ 'is-new': highlighted, 'is-preparing': mode === 'preparing' }">
    <div class="card-head">
      <span class="pickup-code">{{ order.pickupCode }}</span>
      <span class="source-tag">{{ sourceLabel }}</span>
    </div>

    <div class="card-items">
      <div v-for="(line, index) in order.items" :key="index" class="item-line">{{ line }}</div>
    </div>

    <div class="card-foot">
      <span class="amount">￥{{ order.totalAmount }}</span>
      <span class="minutes">{{ minutesText }}</span>
    </div>

    <div class="card-actions">
      <template v-if="mode === 'pending'">
        <el-button type="primary" size="large" :loading="busy" @click="emit('start', order.orderId)">
          开始制作
        </el-button>
        <el-button type="danger" plain size="large" :disabled="busy" @click="emit('void', order.orderId)">
          作废
        </el-button>
      </template>
      <el-button
        v-else
        type="success"
        size="large"
        class="complete-btn"
        :loading="busy"
        @click="emit('complete', order.orderId)"
      >
        出 餐
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.order-card {
  position: relative;
  padding: 14px 16px;
  margin-bottom: var(--gap-3);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  transition:
    transform var(--dur-base) var(--ease-out),
    box-shadow var(--dur-base) var(--ease-out),
    border-color var(--dur-base) var(--ease-out);
}

.order-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-hover);
}

/* 新单高亮 30 秒：伪元素呼吸（只动画 opacity，不触发重绘） */
.order-card.is-new {
  border-color: var(--c-warning);
}

.order-card.is-new::after {
  content: '';
  position: absolute;
  inset: -1px;
  border: 2px solid var(--c-warning);
  border-radius: inherit;
  pointer-events: none;
  animation: new-order-pulse 1.1s ease-in-out infinite alternate;
  will-change: opacity;
}

@keyframes new-order-pulse {
  from {
    opacity: 0.35;
  }
  to {
    opacity: 1;
  }
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap-2);
}

.pickup-code {
  font-size: 40px;
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: 1px;
  color: var(--text-1);
  font-family: 'Consolas', 'Menlo', monospace;
}

.source-tag {
  padding: 3px 10px;
  font-size: var(--fs-xs);
  font-weight: 500;
  color: var(--brand-600);
  background: var(--brand-050);
  border-radius: var(--radius-pill);
}

.is-preparing .source-tag {
  color: #268356;
  background: var(--c-success-soft);
}

.card-items {
  margin: 10px 0 12px;
  padding: 8px 10px;
  font-size: var(--fs-body);
  color: var(--text-2);
  background: var(--bg-subtle);
  border-radius: var(--radius-md);
}

.item-line + .item-line {
  margin-top: 2px;
}

.card-foot {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12px;
}

.amount {
  font-size: 19px;
  font-weight: 700;
  color: var(--c-danger);
}

.minutes {
  font-size: var(--fs-sm);
  color: var(--text-3);
}

.card-actions {
  display: flex;
  gap: var(--gap-2);
}

.card-actions .el-button {
  flex: 1;
  margin-left: 0;
}

.card-actions :deep(.el-button) {
  border-radius: var(--radius-md);
  font-weight: 500;
}
</style>
