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
  /** 当前时间戳（毫秒）：由看板每秒下发一次，避免每张卡片各起一个定时器（T52） */
  nowTs?: number
  /** SLA 转黄阈值（秒，T52）；仅待制作卡片有意义 */
  warnSeconds?: number
  /** SLA 转红阈值（秒，T52）；达到即置顶（排序在后端，前端只负责变色） */
  dangerSeconds?: number
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

/** 到店握手标记（T49）：只有待制作卡片带这个信号；仅用于提示，不改变卡片顺序 */
const arrived = computed(() => props.mode === 'pending' && (props.order as BoardPendingOrder).arrived === true)

/** 到店预约标识（T51「我将到」）：仅待制作卡片有；该信号由后端纳入建议排序 */
const etaMinutes = computed(() =>
  props.mode === 'pending' ? (props.order as BoardPendingOrder).etaMinutes : null
)

/** 已等待秒数（T52）：由父级下发的 nowTs 驱动，父级每秒更新一次 */
const waitedSeconds = computed(() => {
  if (props.mode !== 'pending' || props.nowTs === undefined) {
    return 0
  }
  const startAt = parseDateTime((props.order as BoardPendingOrder).paidAt)
  return startAt === null ? 0 : Math.max(0, Math.floor((props.nowTs - startAt) / 1000))
})

/** 等待时长 mm:ss（任务卡要求的最小展示口径） */
const waitedText = computed(() => {
  const mm = Math.floor(waitedSeconds.value / 60)
  const ss = waitedSeconds.value % 60
  return `${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`
})

/** SLA 档位（T52）：超 danger 转红、超 warn 转黄，其余正常 */
const slaLevel = computed(() => {
  if (props.mode !== 'pending') {
    return 'normal'
  }
  if (props.dangerSeconds !== undefined && waitedSeconds.value >= props.dangerSeconds) {
    return 'danger'
  }
  if (props.warnSeconds !== undefined && waitedSeconds.value >= props.warnSeconds) {
    return 'warn'
  }
  return 'normal'
})

const minutesText = computed(() => {
  if (props.mode === 'pending') {
    return `等待 ${waitedText.value}`
  }
  return `制作 ${(props.order as BoardPreparingOrder).minutesPreparing} 分钟`
})

/**
 * 解析后端时间串（yyyy-MM-dd HH:mm:ss）。
 * iOS 的 Date.parse 不接受「空格分隔」的格式，须替换为 ISO 的 'T'（同 T49 订单详情页处理）。
 */
function parseDateTime(text: string | null | undefined): number | null {
  if (!text) {
    return null
  }
  const parsed = Date.parse(text.replace(' ', 'T'))
  return Number.isNaN(parsed) ? null : parsed
}
</script>

<template>
  <div class="order-card" :class="{ 'is-new': highlighted, 'is-preparing': mode === 'preparing' }">
    <div class="card-head">
      <span class="pickup-code">{{ order.pickupCode }}</span>
      <span class="head-tags">
        <!-- 到店预约（T51）：顾客告知还有多久到店；该信号参与后端建议排序（到达近的优先） -->
        <span v-if="etaMinutes !== null" class="eta-tag" title="顾客申报的预计到店时间">
          约 {{ etaMinutes }} 分钟到
        </span>
        <!-- 到店握手（T49）：顾客已在店等餐。仅提示——排序不变、统计不变，店长可优先处理也可无视 -->
        <span v-if="arrived" class="arrived-tag" title="顾客已申报到店">已到店</span>
        <span class="source-tag">{{ sourceLabel }}</span>
      </span>
    </div>

    <div class="card-items">
      <div v-for="(line, index) in order.items" :key="index" class="item-line">{{ line }}</div>
    </div>

    <div class="card-foot">
      <span class="amount">￥{{ order.totalAmount }}</span>
      <span class="minutes" :class="`sla-${slaLevel}`">{{ minutesText }}</span>
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

/* SLA 分级（T52）：正常=次要色；转黄=警示；转红=危险且加粗。置顶由后端排序负责 */
.minutes.sla-normal {
  color: var(--text-2);
}

.minutes.sla-warn {
  font-weight: 600;
  color: #b88230;
}

.minutes.sla-danger {
  font-weight: 700;
  color: var(--c-danger);
}

.head-tags {
  display: flex;
  align-items: center;
  gap: var(--gap-2);
}

/* 到店预约标识（T51）：告知店长顾客还有多久到；该信号参与后端建议排序 */
.eta-tag {
  padding: 2px 10px;
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--brand-600);
  background: var(--brand-050);
  border-radius: var(--radius-pill);
}

/* 到店握手标记（T49）：醒目提示顾客已在店等餐；不改变任何排序与统计口径 */
.arrived-tag {
  padding: 2px 10px;
  font-size: var(--fs-xs);
  font-weight: 600;
  color: #fff;
  background: var(--c-danger);
  border-radius: var(--radius-pill);
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
