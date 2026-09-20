<script setup lang="ts">
import { computed } from 'vue'
import type { BoardPendingOrder, BoardPreparingOrder } from '@/api/order'

/**
 * 看板订单卡片（T19，LLD 7.3）：取餐码大字 + 渠道标签 + 商品摘要 + 合计 + 等待/制作分钟数。
 * pending 卡片提供「开始制作 / 作废」，preparing 卡片提供「出餐」；新单高亮由父级传入。
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
  padding: 12px 14px;
  margin-bottom: 12px;
  background: #fff;
  border: 2px solid #e4e7ed;
  border-radius: 8px;
  transition: border-color 0.3s, box-shadow 0.3s;
}

/* 新单高亮 30 秒：橙色描边 + 呼吸动画，一眼可见 */
.order-card.is-new {
  border-color: #e6a23c;
  box-shadow: 0 0 0 1px #e6a23c;
  animation: new-order-pulse 1s ease-in-out infinite alternate;
}

@keyframes new-order-pulse {
  from {
    box-shadow: 0 0 0 1px #e6a23c;
  }
  to {
    box-shadow: 0 0 14px 2px rgba(230, 162, 60, 0.75);
  }
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.pickup-code {
  font-size: 40px;
  font-weight: 700;
  line-height: 1.1;
  color: #303133;
  font-family: 'Consolas', 'Menlo', monospace;
}

.source-tag {
  padding: 2px 10px;
  font-size: 12px;
  color: #409eff;
  background: #ecf5ff;
  border-radius: 10px;
}

.is-preparing .source-tag {
  color: #67c23a;
  background: #f0f9eb;
}

.card-items {
  margin: 8px 0;
  font-size: 14px;
  color: #606266;
}

.item-line + .item-line {
  margin-top: 2px;
}

.card-foot {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}

.amount {
  font-size: 18px;
  font-weight: 600;
  color: #f56c6c;
}

.minutes {
  font-size: 13px;
  color: #909399;
}

.card-actions {
  display: flex;
  gap: 8px;
}

.card-actions .el-button {
  flex: 1;
  margin-left: 0;
}
</style>
