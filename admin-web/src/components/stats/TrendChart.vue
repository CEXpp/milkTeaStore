<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ECharts, EChartsCoreOption } from 'echarts/core'
import type { StatsTrendItem } from '@/api/stats'
import { toCents } from '@/utils/money'

/**
 * 近 N 日趋势（T35，施工卡「ECharts 折线双轴或简版柱状」）：
 * 左轴营业额（柱）、右轴订单数（折线），双轴对比能同时看出「单量」与「客单价」的变化。
 *
 * ECharts 按需引入（施工卡要求）：只注册用到的图表与组件，避免整包体积。
 * 后端已把无单日补成 0（T34），因此日期轴天然连续，空数据日也照常渲染。
 */

echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const props = withDefaults(
  defineProps<{
    /** 逐日趋势（按日期升序，无单日为 0） */
    items: StatsTrendItem[]
  }>(),
  { items: () => [] }
)

const container = ref<HTMLDivElement | null>(null)
let chart: ECharts | null = null

/** 金额用「分」换算后再落到图上，避免浮点误差；展示单位是元。 */
function buildOption(items: StatsTrendItem[]): EChartsCoreOption {
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['营业额(元)', '订单数'] },
    grid: { left: 56, right: 56, top: 44, bottom: 28 },
    xAxis: {
      type: 'category',
      // 横轴只留 MM-DD，7 天不会挤
      data: items.map((item) => item.date.slice(5))
    },
    yAxis: [
      { type: 'value', name: '营业额(元)', minInterval: 0 },
      { type: 'value', name: '订单数', minInterval: 1 }
    ],
    series: [
      {
        name: '营业额(元)',
        type: 'bar',
        barMaxWidth: 28,
        data: items.map((item) => toCents(item.amount) / 100)
      },
      {
        name: '订单数',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        data: items.map((item) => item.orderCount)
      }
    ]
  }
}

function render(): void {
  if (!container.value) return
  chart ??= echarts.init(container.value)
  chart.setOption(buildOption(props.items), true)
}

function handleResize(): void {
  chart?.resize()
}

onMounted(() => {
  render()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
  chart = null
})

watch(() => props.items, render, { deep: true })
</script>

<template>
  <div ref="container" class="trend-chart" />
</template>

<style scoped>
.trend-chart {
  width: 100%;
  height: 280px;
}
</style>
