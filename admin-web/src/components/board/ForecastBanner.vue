<script setup lang="ts">
import { computed, ref } from 'vue'
import { getDemandForecast, type DemandForecast } from '@/api/forecast'

/**
 * 爆单预测横幅（T69，W09 · D3b）。
 *
 * 与 T48「动态接单节奏」刻意分开：那个说的是**现在**队列里压了多少杯（实测），
 * 这个说的是**接下来一段时间**可能会来多少单（预测）。混在一处会让店长把
 * 「预测」当成「已经发生的拥堵」，久而久之就不再相信预警了。
 *
 * **横幅里没有「一键暂停」按钮**：T48 有「去暂停接单」是因为那是店长的决定；
 * 这里若也放一个按钮，等于把预测直接变成了动作入口。暂停永远在顶栏开关上。
 */
const forecast = ref<DemandForecast | null>(null)

/** 仅在非正常档展示；预测未启用或正常时整条不出现（正常时刷一条「一切正常」是噪声）。 */
const visible = computed(() => Boolean(forecast.value?.enabled && forecast.value.level !== 'NORMAL'))

/**
 * 失败熔断：预测请求失败后不再重试。
 *
 * <p>本组件的 load 挂在看板 3 秒轮询上（与接单节奏同频）。若后端该接口持续异常，
 * 每 3 秒失败一次就会每 3 秒弹一次「服务器内部错误」——一个<b>非核心的预测功能</b>
 * 用弹窗把店长淹没，比没有预测更糟（他会连真正的出餐异常提示一起忽略）。
 * 故失败一次即停止后续请求：预测不可用就静默不可用，绝不做骚扰式重试。</p>
 */
let loadFailed = false

async function load(): Promise<void> {
  if (loadFailed) return
  try {
    forecast.value = await getDemandForecast()
  } catch {
    // 错误提示已由 request 层直显一次；此后熔断，不再随轮询反复弹窗
    loadFailed = true
  }
}

/** 暴露给看板的刷新钩子：跟看板一起轮询，保持预测与队列节奏同频 */
defineExpose({ load })

load()
</script>

<template>
  <div
    v-if="visible"
    class="forecast-banner"
    :class="`forecast-${forecast!.level.toLowerCase()}`"
  >
    <div class="forecast-main">
      <span class="forecast-tag">{{ forecast!.levelLabel }}</span>
      <span class="forecast-text">
        预计未来 {{ forecast!.windowMinutes }} 分钟约 {{ forecast!.predictedOrders }} 单。
        <template v-if="forecast!.advice">{{ forecast!.advice }}</template>
      </span>
    </div>
    <!-- 依据与「系统不会自动执行」一并说清：让店长知道这个数怎么来的、边界在哪 -->
    <span class="forecast-basis">
      {{ forecast!.basis }}；系统只给建议，暂停接单请在顶栏手动开关。
    </span>
  </div>
</template>

<style scoped>
/* 与 T48 节奏横幅视觉区分：左侧色条 + 无按钮，避免两者被当成同一件事 */
.forecast-banner {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 14px;
  margin-bottom: var(--gap-3);
  background: var(--c-warning-soft);
  border-left: 4px solid var(--c-warning);
  border-radius: var(--radius-md);
}

.forecast-overload {
  background: var(--c-danger-soft);
  border-left-color: var(--c-danger);
}

.forecast-main {
  display: flex;
  align-items: flex-start;
  gap: var(--gap-2);
}

.forecast-tag {
  flex-shrink: 0;
  padding: 1px 10px;
  font-size: var(--fs-xs);
  font-weight: 600;
  color: #b88230;
  background: rgba(255, 255, 255, 0.7);
  border-radius: var(--radius-pill);
}

.forecast-overload .forecast-tag {
  color: var(--c-danger);
}

.forecast-text {
  font-size: var(--fs-sm);
  line-height: 1.6;
  color: var(--text-1);
}

.forecast-basis {
  padding-left: 4px;
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--text-3);
}
</style>
