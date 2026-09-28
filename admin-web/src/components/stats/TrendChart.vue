<script setup lang="ts">
import { onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
 *
 * 性能：resize 走 ResizeObserver + requestAnimationFrame 合并（比 window.resize 更准、更省）；
 * 数据监听改为引用比较（父级每次请求都会换数组引用），不再做 deep 遍历。
 */

echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const props = withDefaults(
  defineProps<{
    /** 逐日趋势（按日期升序，无单日为 0） */
    items: StatsTrendItem[]
  }>(),
  { items: () => [] }
)

/** 图表配色（与蓝灰设计体系一致） */
const AXIS_LABEL = { color: '#6b7280', fontSize: 12 }
const SPLIT_LINE = { lineStyle: { color: '#eef1f5', type: 'dashed' as const } }

const container = ref<HTMLDivElement | null>(null)
let chart: ECharts | null = null
let observer: ResizeObserver | null = null
let rafId: number | null = null

/** 金额用「分」换算后再落到图上，避免浮点误差；展示单位是元。 */
function buildOption(items: StatsTrendItem[]): EChartsCoreOption {
  return {
    color: ['#4b5bd6', '#e0a33c'],
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#e5e9f0',
      borderWidth: 1,
      padding: [10, 14],
      textStyle: { color: '#1f2937', fontSize: 12 },
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(75,91,214,0.06)' } }
    },
    legend: { data: ['营业额(元)', '订单数'], top: 4, textStyle: { color: '#6b7280', fontSize: 12 } },
    grid: { left: 56, right: 56, top: 44, bottom: 28 },
    xAxis: {
      type: 'category',
      // 横轴只留 MM-DD，7 天不会挤
      data: items.map((item) => item.date.slice(5)),
      axisLabel: AXIS_LABEL,
      axisLine: { lineStyle: { color: '#e5e9f0' } },
      axisTick: { show: false }
    },
    yAxis: [
      {
        type: 'value',
        name: '营业额(元)',
        minInterval: 0,
        axisLabel: AXIS_LABEL,
        nameTextStyle: { color: '#9ca3af', fontSize: 11 },
        splitLine: SPLIT_LINE
      },
      {
        type: 'value',
        name: '订单数',
        minInterval: 1,
        axisLabel: AXIS_LABEL,
        nameTextStyle: { color: '#9ca3af', fontSize: 11 },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '营业额(元)',
        type: 'bar',
        barMaxWidth: 28,
        itemStyle: { color: '#4b5bd6', borderRadius: [6, 6, 0, 0] },
        data: items.map((item) => toCents(item.amount) / 100)
      },
      {
        name: '订单数',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbolSize: 6,
        itemStyle: { color: '#e0a33c' },
        lineStyle: { width: 2.5, color: '#e0a33c' },
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

/** 尺寸变化合并到一帧内处理，避免连续 resize 造成的抖动与重排 */
function scheduleResize(): void {
  if (rafId !== null) return
  rafId = requestAnimationFrame(() => {
    rafId = null
    chart?.resize()
  })
}

onMounted(() => {
  render()
  if (container.value && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(scheduleResize)
    observer.observe(container.value)
  } else {
    window.addEventListener('resize', scheduleResize)
  }
})

// 统计页被 keep-alive 缓存期间 DOM 脱离文档，重新激活后补一次 resize 以恢复正确画布尺寸
onActivated(() => {
  requestAnimationFrame(() => chart?.resize())
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  window.removeEventListener('resize', scheduleResize)
  if (rafId !== null) {
    cancelAnimationFrame(rafId)
    rafId = null
  }
  chart?.dispose()
  chart = null
})

// 父级每次请求都会替换数组引用，引用比较即可感知变化（无需 deep 遍历）
watch(() => props.items, render)
</script>

<template>
  <div ref="container" class="trend-chart" />
</template>

<style scoped>
.trend-chart {
  width: 100%;
  height: 300px;
}
</style>
