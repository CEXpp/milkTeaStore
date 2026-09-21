<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { getStatsOrders, type StatsOrderItem } from '@/api/stats'
import type { OrderSource, OrderStatus } from '@/api/order'

/**
 * 按日订单流水明细（T35）：供商家逐笔对账，含待支付 / 超时关闭 / 作废等异常单，
 * 作废原因直接展示，不做隐藏（施工卡「状态标签+作废原因展示」）。
 *
 * 归属日按「下单时间」由后端筛选（T34 口径说明）：超时关闭单没有支付时间，
 * 若按支付时间筛就永远看不到异常单，故明细与概览的口径刻意不同。
 */

const props = defineProps<{
  /** 归属日 yyyy-MM-dd（由页面日期选择器传入） */
  date: string
}>()

const PAGE_SIZE = 20

/** 订单状态下拉项（值为空表示不过滤） */
const STATUS_OPTIONS: Array<{ label: string; value: OrderStatus | '' }> = [
  { label: '全部状态', value: '' },
  { label: '待支付', value: 'PENDING_PAYMENT' },
  { label: '已支付', value: 'PAID' },
  { label: '制作中', value: 'PREPARING' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '超时关闭', value: 'CLOSED' },
  { label: '已作废', value: 'VOIDED' }
]

/** 状态 → 展示文案与标签色（异常单给醒目色，便于一眼扫到） */
const STATUS_STYLE: Record<OrderStatus, { label: string; type: 'success' | 'warning' | 'info' | 'danger' | 'primary' }> = {
  PENDING_PAYMENT: { label: '待支付', type: 'info' },
  PAID: { label: '已支付', type: 'primary' },
  PREPARING: { label: '制作中', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' },
  CLOSED: { label: '超时关闭', type: 'info' },
  VOIDED: { label: '已作废', type: 'danger' }
}

const SOURCE_LABEL: Record<OrderSource, string> = {
  MINI_PROGRAM: '小程序',
  AI: 'AI',
  COUNTER: '柜台'
}

const status = ref<OrderStatus | ''>('')
const page = ref(1)
const total = ref(0)
const rows = ref<StatsOrderItem[]>([])
const loading = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await getStatsOrders({
      date: props.date,
      status: status.value || undefined,
      page: page.value,
      size: PAGE_SIZE
    })
    rows.value = result.list
    total.value = result.total
  } catch {
    // 错误提示已由 request 层直显
  } finally {
    loading.value = false
  }
}

/** 日期或状态变化时回到第 1 页再查，避免停留在越界页码上。 */
function reloadFromFirstPage(): void {
  if (page.value === 1) {
    void load()
    return
  }
  page.value = 1
}

onMounted(load)
watch(() => props.date, reloadFromFirstPage)
watch(status, reloadFromFirstPage)
watch(page, load)
</script>

<template>
  <el-card shadow="never" class="flow-card">
    <template #header>
      <div class="card-head">
        <span class="card-title">订单流水明细</span>
        <el-select v-model="status" size="small" class="status-select">
          <el-option v-for="option in STATUS_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </div>
    </template>

    <el-table :data="rows" v-loading="loading" size="small" empty-text="该日暂无订单流水">
      <el-table-column prop="orderNo" label="订单号" width="130" />
      <el-table-column label="渠道" width="80">
        <template #default="{ row }">{{ SOURCE_LABEL[row.source as OrderSource] ?? row.source }}</template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag :type="STATUS_STYLE[row.status as OrderStatus]?.type ?? 'info'" size="small" disable-transitions>
            {{ STATUS_STYLE[row.status as OrderStatus]?.label ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="pickupCode" label="取餐码" width="82">
        <template #default="{ row }">{{ row.pickupCode ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="totalAmount" label="金额(元)" width="96" />
      <el-table-column prop="createdAt" label="下单时间" width="160" />
      <el-table-column label="支付时间" width="160">
        <template #default="{ row }">{{ row.paidAt ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="作废原因" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.voidReason ?? '—' }}</template>
      </el-table-column>
    </el-table>

    <div class="flow-pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="PAGE_SIZE"
        :total="total"
        layout="total, prev, pager, next"
        background
        small
      />
    </div>
  </el-card>
</template>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-title {
  font-weight: 600;
}

.status-select {
  width: 132px;
}

.flow-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
