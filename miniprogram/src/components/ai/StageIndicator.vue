<script setup lang="ts">
import { computed } from 'vue'
import type { AiStreamStage } from '@/api/aiStream'

/**
 * 决策阶段提示（替换原先那句「小茶正在想…」）。
 *
 * <p><b>为什么给阶段而不是给一句「思考中」</b>：并行流式的等待时间是可解释的
 * （理解需求 → 选品 → 算价），把这三步显式点亮，等待就从「卡住了」变成「在推进」。
 * 这是把「响应更快」之外的体验补上：当总耗时确实变长时（模型侧抖动），
 * 用户看到的是进度而不是空白。</p>
 *
 * <p>阶段由后端事件驱动，前端只负责点亮——不做本地计时器模拟进度，
 * 那会在后端已经算完时还假装「正在算价」。</p>
 */

const props = defineProps<{ stage: AiStreamStage | null }>()

const STEPS: Array<{ key: AiStreamStage; text: string }> = [
  { key: 'UNDERSTAND', text: '正在理解你的口味' },
  { key: 'PICK', text: '正在挑最合适的一杯' },
  { key: 'PRICE', text: '正在算价' }
]

/** 当前进行到第几步（-1 表示尚未收到任何阶段事件） */
const currentIndex = computed(() => STEPS.findIndex((step) => step.key === props.stage))
</script>

<template>
  <view class="stage">
    <view
      v-for="(step, index) in STEPS"
      :key="step.key"
      class="stage-step"
      :class="{ 'stage-step-on': index <= currentIndex, 'stage-step-active': index === currentIndex }"
    >
      <view class="stage-dot" />
      <text class="stage-text a11y-sm">{{ step.text }}</text>
    </view>
  </view>
</template>

<style scoped>
.stage {
  display: flex;
  flex-direction: column;
  gap: 10rpx;
  padding: 12rpx 0 24rpx 80rpx;
}

.stage-step {
  display: flex;
  align-items: center;
  gap: 12rpx;
  opacity: 0.35;
  transition: opacity 200ms var(--ease);
}

/* 已走过的步骤保持可见（已完成的灰、当前的暖棕），未到的仍淡化 */
.stage-step-on {
  opacity: 1;
}

.stage-dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 50%;
  background: var(--c-border);
  flex: none;
}

.stage-step-on .stage-dot {
  background: var(--c-primary-light);
}

.stage-step-active .stage-dot {
  background: var(--c-primary);
  animation: stage-pulse 1.2s var(--ease) infinite;
}

.stage-text {
  font-size: 24rpx;
  color: var(--c-text-sub);
}

.stage-step-active .stage-text {
  color: var(--c-primary-dark);
  font-weight: 600;
}

@keyframes stage-pulse {
  0% {
    transform: scale(1);
    opacity: 1;
  }
  60% {
    transform: scale(1.5);
    opacity: 0.5;
  }
  100% {
    transform: scale(1);
    opacity: 1;
  }
}

/* 无障碍模式下退化为静态：微动效是锦上添花，晃动会干扰部分用户 */
:global(.a11y-mode) .stage-dot {
  animation: none !important;
}
</style>
