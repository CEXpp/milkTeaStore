<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { generateDailyReport, getDailyReport, type DailyReport } from '@/api/daily-report'
import { useShopStatus } from '@/composables/useShopStatus'

/**
 * 每日经营日报卡（T68，W08）：挂在看板顶部。
 *
 * 呈现纪律：**先给 AI 的口语结论，再把算好的数字摊开**。数字来自服务端算好的
 * `metrics`（与账台统计同源），因此店长随时能核对——这是「日报中每个数字可由统计接口
 * 复算」在界面上的兑现方式。
 *
 * 三种状态都要如实呈现，不允许「编一个看起来正常的日报」：
 * 1. 未生成 → 提示打烊后生成，并提供手动生成入口；
 * 2. 已生成无异常 → 明确写「今日无异常」；
 * 3. 已生成但 AI 不可用（degraded）→ 只给数据版，并说明 AI 暂不可用。
 */

const report = ref<DailyReport | null>(null)
const loading = ref(false)
const generating = ref(false)
const loaded = ref(false)

/** 复用门店状态：打烊时刻与营业时间相关，文案里给店长一致的预期 */
const { paused } = useShopStatus()

function todayLocal(): string {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  try {
    report.value = await getDailyReport(todayLocal())
  } catch {
    // 错误提示已由 request 层直显
  } finally {
    loading.value = false
    loaded.value = true
  }
}

/** 手动生成：与定时任务完全同一条链路，幂等（重跑覆盖同一行） */
async function generate(): Promise<void> {
  if (generating.value) return
  try {
    await ElMessageBox.confirm(
      '将按当前数据重新生成今日日报。今天已经有日报的话会被覆盖（历史日报的快照特性不变，仍是同一天同一份）。',
      '重新生成日报',
      { type: 'info', confirmButtonText: '生成', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  generating.value = true
  try {
    report.value = await generateDailyReport(todayLocal())
    ElMessage.success('日报已生成')
  } catch {
    // 错误提示已由 request 层直显
  } finally {
    generating.value = false
  }
}

/** 变化幅度的展示文案与颜色：跌用红、涨用绿、无基准用灰 */
const revenueTrend = computed(() => {
  const change = report.value?.metrics.revenueChangePercent
  if (change === null || change === undefined) {
    return { text: '无可比基准', tone: 'none' as const }
  }
  const sign = change > 0 ? '+' : ''
  return {
    text: `较近 7 日同期 ${sign}${change}%`,
    tone: change < 0 ? ('down' as const) : ('up' as const)
  }
})

/** 进入看板即拉一次当日日报（未生成时展示提示，不现场造一份） */
load()
</script>

<template>
  <el-card shadow="never" class="report-card" v-loading="loading">
    <template #header>
      <div class="card-head">
        <div class="head-left">
          <span class="card-title">每日经营日报</span>
          <span v-if="report" class="head-date">{{ report.date }} · 生成于 {{ report.generatedAt }}</span>
        </div>
        <el-button size="small" :icon="Refresh" :loading="generating" @click="generate">
          {{ report ? '重新生成' : '立即生成' }}
        </el-button>
      </div>
    </template>

    <!-- 未生成：如实说明，不现场编一份 -->
    <div v-if="loaded && !report" class="empty-state">
      <p class="empty-title">今日日报还未生成</p>
      <p class="empty-hint">
        日报在每天打烊后自动生成；也可以点右上角「立即生成」按当前数据先看一眼。
      </p>
    </div>

    <template v-else-if="report">
      <!-- AI 表述层：降级时只给数据版说明，绝不编结论 -->
      <div v-if="report.degraded" class="narrative degraded">
        AI 日报暂不可用，以下为当日数据（口径与「账台统计」页一致）。
      </div>
      <p v-else-if="report.narrative" class="narrative">{{ report.narrative }}</p>

      <!-- 无异常时明确写出来（验收项「不编造」） -->
      <div v-if="report.noAnomaly" class="no-anomaly">今日无异常</div>
      <div v-else class="anomalies">
        <div v-for="anomaly in report.anomalies" :key="anomaly.code" class="anomaly-item">
          <span class="anomaly-label">{{ anomaly.label }}</span>
          <span class="anomaly-detail">{{ anomaly.detail }}</span>
        </div>
      </div>

      <!-- 算好的数字：与账台统计同源，店长可逐项核对 -->
      <div class="metrics">
        <div class="metric">
          <span class="metric-key">营业额</span>
          <span class="metric-val">￥{{ report.metrics.revenue }}</span>
          <span class="metric-sub" :class="`tone-${revenueTrend.tone}`">{{ revenueTrend.text }}</span>
        </div>
        <div class="metric">
          <span class="metric-key">订单数</span>
          <span class="metric-val">{{ report.metrics.orderCount }} 单</span>
          <span class="metric-sub">近 7 日均 {{ report.metrics.avgOrderCount }} 单</span>
        </div>
        <div class="metric">
          <span class="metric-key">杯数</span>
          <span class="metric-val">{{ report.metrics.cupCount }} 杯</span>
          <span class="metric-sub">近 7 日均 {{ report.metrics.avgCupCount }} 杯</span>
        </div>
        <div class="metric">
          <span class="metric-key">退款额</span>
          <span class="metric-val">￥{{ report.metrics.refundAmount }}</span>
          <span class="metric-sub">退款率 {{ report.metrics.refundRatioPercent }}%</span>
        </div>
      </div>

      <div class="tables">
        <div v-if="report.metrics.channels.length" class="table-block">
          <span class="table-title">渠道结构</span>
          <span v-for="channel in report.metrics.channels" :key="channel.source" class="table-row">
            {{ channel.source }} {{ channel.orderCount }} 单（{{ channel.sharePercent }}%）
          </span>
        </div>
        <div v-if="report.metrics.topProducts.length" class="table-block">
          <span class="table-title">TOP 商品</span>
          <span v-for="product in report.metrics.topProducts" :key="product.productName" class="table-row">
            {{ product.productName }} {{ product.cupCount }} 杯
          </span>
        </div>
      </div>

      <p class="foot-hint">
        {{ paused ? '当前处于暂停接单状态；' : '' }}以上数字与「账台统计」页同源，可逐项核对。
      </p>
    </template>
  </el-card>
</template>

<style scoped>
.report-card {
  margin-bottom: var(--gap-4);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap-3);
}

.head-left {
  display: flex;
  align-items: baseline;
  gap: var(--gap-2);
  min-width: 0;
}

.card-title {
  font-size: var(--fs-h2);
  font-weight: 600;
  color: var(--text-1);
}

.head-date {
  font-size: var(--fs-xs);
  color: var(--text-3);
}

.empty-state {
  padding: var(--gap-4) 0;
  text-align: center;
}

.empty-title {
  margin: 0 0 var(--gap-2);
  font-size: var(--fs-sm);
  color: var(--text-2);
}

.empty-hint {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-3);
}

/* AI 结论文案：是「表述」不是「数据」，故用正文样式而非指标样式 */
.narrative {
  margin: 0 0 var(--gap-3);
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: var(--text-1);
  white-space: pre-wrap;
}

.narrative.degraded {
  color: #b88230;
}

.no-anomaly {
  display: inline-block;
  padding: 2px 12px;
  margin-bottom: var(--gap-3);
  font-size: var(--fs-xs);
  color: var(--c-success);
  background: var(--c-success-soft);
  border-radius: var(--radius-pill);
}

.anomalies {
  display: flex;
  flex-direction: column;
  gap: var(--gap-2);
  margin-bottom: var(--gap-3);
}

.anomaly-item {
  display: flex;
  gap: var(--gap-2);
  padding: 8px 12px;
  font-size: var(--fs-xs);
  line-height: 1.7;
  background: var(--c-warning-soft);
  border-radius: var(--radius-md);
}

.anomaly-label {
  flex-shrink: 0;
  font-weight: 600;
  color: #b88230;
}

.anomaly-detail {
  color: var(--text-2);
}

.metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--gap-3);
  margin-bottom: var(--gap-3);
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  background: var(--bg-subtle);
  border-radius: var(--radius-md);
}

.metric-key {
  font-size: var(--fs-xs);
  color: var(--text-3);
}

.metric-val {
  font-size: var(--fs-h2);
  font-weight: 600;
  color: var(--text-1);
}

.metric-sub {
  font-size: var(--fs-xs);
  color: var(--text-3);
}

.tone-down {
  color: var(--c-danger);
}

.tone-up {
  color: var(--c-success);
}

.tables {
  display: flex;
  flex-wrap: wrap;
  gap: var(--gap-5);
}

.table-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.table-title {
  margin-bottom: 4px;
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--text-2);
}

.table-row {
  font-size: var(--fs-xs);
  color: var(--text-3);
}

.foot-hint {
  margin: var(--gap-3) 0 0;
  font-size: var(--fs-xs);
  color: var(--text-3);
}
</style>
