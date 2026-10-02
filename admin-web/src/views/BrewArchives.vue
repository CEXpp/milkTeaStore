<script setup lang="ts">
import { ref, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'
import {
  getBrewArchives,
  type BrewArchive,
  type BrewArchiveQuery
} from '@/api/archive'
import type { OrderSource, OrderStatus } from '@/api/order'
import { useMountOrActivateRefresh } from '@/composables/useMountOrActivateRefresh'
import AdminPageHeader from '@/components/AdminPageHeader.vue'

/**
 * 每一杯茶的制作档案页（T59，W21）。
 *
 * 一行 = 一份档案 = 一个订单项（「每一杯」）。默认看今天，可切到区间；
 * 支持按商品名 / 规格 / 状态检索——规格检索用于「这一批珍珠的单都出过什么状况」这类自查。
 *
 * **只读**：本页没有任何编辑控件，后端也不提供修改档案的接口。展开行给的是六个时间戳
 * 与逐行规格快照，用于「任选一杯可还原完整生命周期」。
 *
 * 快照纪律：商品改名改价后，本页仍显示下单时的旧名与旧价——这不是数据延迟，是 6.3
 * 「价格不回溯」在档案上的直接体现，故不加「同步」类按钮误导使用者。
 */

defineOptions({ name: 'BrewArchives' })

const PAGE_SIZE = 20

const STATUS_OPTIONS: Array<{ label: string; value: OrderStatus | '' }> = [
  { label: '全部状态', value: '' },
  { label: '待支付', value: 'PENDING_PAYMENT' },
  { label: '已支付', value: 'PAID' },
  { label: '制作中', value: 'PREPARING' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '超时关闭', value: 'CLOSED' },
  { label: '已作废', value: 'VOIDED' }
]

const STATUS_STYLE: Record<OrderStatus, { label: string; type: 'success' | 'warning' | 'info' | 'danger' | 'primary' }> =
  {
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

/** 日期范围：默认今天；切「全部日期」时不下发日期参数（后端不限日期） */
const RANGE_OPTIONS = [
  { label: '今天', value: 'today' as const },
  { label: '近 7 日', value: '7d' as const },
  { label: '全部日期', value: 'all' as const }
]

function todayLocal(): string {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

function shiftDays(base: string, delta: number): string {
  const date = new Date(`${base}T00:00:00`)
  date.setDate(date.getDate() + delta)
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

const range = ref<(typeof RANGE_OPTIONS)[number]['value']>('today')
const productName = ref('')
const spec = ref('')
const status = ref<OrderStatus | ''>('')
const page = ref(1)
const total = ref(0)
const rows = ref<BrewArchive[]>([])
const loading = ref(false)

/** 日期入参：区间模式传 from/to（后端左闭右开），今天传 date，全部日期则不传。 */
function dateParams(): Pick<BrewArchiveQuery, 'date' | 'from' | 'to'> {
  const today = todayLocal()
  if (range.value === 'today') {
    return { date: today }
  }
  if (range.value === '7d') {
    return { from: shiftDays(today, -6), to: shiftDays(today, 1) }
  }
  return {}
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await getBrewArchives({
      ...dateParams(),
      productName: productName.value.trim() || undefined,
      spec: spec.value.trim() || undefined,
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

function search(): void {
  if (page.value === 1) {
    void load()
    return
  }
  page.value = 1
}

/** 筛选条件变化时回到第 1 页再查，避免停留在越界页码上。 */
function reloadFromFirstPage(): void {
  search()
}

useMountOrActivateRefresh(() => void load())
watch([range, status], reloadFromFirstPage)
watch(page, load)
</script>

<template>
  <section class="archive-page">
    <AdminPageHeader title="制作档案" subtitle="每一杯茶的一生：下单到出餐的六个时间戳与规格快照，只读不可改">
      <el-button type="primary" :icon="Search" @click="search">检索</el-button>
    </AdminPageHeader>

    <el-card shadow="never" class="filter-card">
      <div class="filter-row">
        <el-radio-group v-model="range" size="small">
          <el-radio-button v-for="option in RANGE_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>

        <el-input
          v-model="productName"
          placeholder="商品名（下单时快照）"
          clearable
          size="small"
          class="filter-input"
          @keyup.enter="search"
          @clear="reloadFromFirstPage"
        />

        <el-input
          v-model="spec"
          placeholder="规格 / 加料，如 珍珠"
          clearable
          size="small"
          class="filter-input"
          @keyup.enter="search"
          @clear="reloadFromFirstPage"
        />

        <el-select v-model="status" size="small" class="status-select">
          <el-option v-for="option in STATUS_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </div>
      <p class="filter-hint">
        档案金额与规格均为下单瞬间的快照：商品后续改价改名，历史档案保持原样。
      </p>
    </el-card>

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" size="small" empty-text="该条件下暂无制作档案">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="detail">
              <div class="detail-block">
                <h4>规格快照</h4>
                <el-table :data="row.options" size="small" empty-text="该杯无规格（不下单快照为空数组）">
                  <el-table-column prop="groupName" label="规格组" width="120" />
                  <el-table-column prop="optionName" label="选项" width="140" />
                  <el-table-column label="价差" width="90">
                    <template #default="{ row: option }">{{ option.priceDelta ?? '—' }}</template>
                  </el-table-column>
                </el-table>
              </div>

              <div class="detail-block">
                <h4>生命周期</h4>
                <el-descriptions :column="2" size="small" border>
                  <el-descriptions-item label="下单">{{ row.createdAt }}</el-descriptions-item>
                  <el-descriptions-item label="支付">{{ row.paidAt ?? '—' }}</el-descriptions-item>
                  <el-descriptions-item label="开始制作">{{ row.startedAt ?? '—' }}</el-descriptions-item>
                  <el-descriptions-item label="出餐">{{ row.completedAt ?? '—' }}</el-descriptions-item>
                  <el-descriptions-item label="超时关闭">{{ row.closedAt ?? '—' }}</el-descriptions-item>
                  <el-descriptions-item label="作废">{{ row.voidedAt ?? '—' }}</el-descriptions-item>
                  <el-descriptions-item label="制作耗时">
                    {{ row.prepMinutes === null ? '—' : `${row.prepMinutes} 分钟` }}
                  </el-descriptions-item>
                  <el-descriptions-item label="归属日">{{ row.belongDate }}</el-descriptions-item>
                  <el-descriptions-item label="口味备注" :span="2">
                    {{ row.remark || '—' }}
                    <el-tag v-if="row.remarkTagCount > 0" size="small" type="info" class="tag-gap">
                      {{ row.remarkTagCount }} 项特殊要求
                    </el-tag>
                  </el-descriptions-item>
                  <el-descriptions-item v-if="row.voidReason" label="作废原因" :span="2">
                    <span class="void-reason">{{ row.voidReason }}</span>
                  </el-descriptions-item>
                </el-descriptions>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="取餐码" width="86">
          <template #default="{ row }">{{ row.pickupCode ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="商品（下单快照）" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.productName }}
            <span v-if="row.quantity > 1" class="qty">×{{ row.quantity }}</span>
          </template>
        </el-table-column>
        <el-table-column label="规格" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.optionSummary ?? '无规格' }}</template>
        </el-table-column>
        <el-table-column label="单价 / 金额" width="130">
          <template #default="{ row }">
            <span class="money">{{ row.unitPrice }}</span>
            <span class="amount">{{ row.itemAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="渠道" width="76">
          <template #default="{ row }">{{ SOURCE_LABEL[row.source as OrderSource] ?? row.source }}</template>
        </el-table-column>
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <el-tag :type="STATUS_STYLE[row.status as OrderStatus]?.type ?? 'info'" size="small" disable-transitions>
              {{ STATUS_STYLE[row.status as OrderStatus]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="支付时间" width="160">
          <template #default="{ row }">{{ row.paidAt ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="制作耗时" width="96">
          <template #default="{ row }">
            {{ row.prepMinutes === null ? '—' : `${row.prepMinutes} 分钟` }}
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="PAGE_SIZE"
          :total="total"
          layout="total, prev, pager, next"
          background
          size="small"
        />
      </div>
    </el-card>
  </section>
</template>

<style scoped>
.filter-card {
  margin-bottom: var(--gap-4);
}

.filter-row {
  display: flex;
  align-items: center;
  gap: var(--gap-3);
  flex-wrap: wrap;
}

.filter-input {
  width: 220px;
}

.status-select {
  width: 132px;
}

.filter-hint {
  margin: var(--gap-3) 0 0;
  font-size: var(--fs-xs);
  color: var(--text-3);
}

.qty {
  margin-left: 4px;
  font-size: var(--fs-xs);
  color: var(--text-3);
}

.money {
  color: var(--text-3);
}

.amount {
  margin-left: 6px;
  font-weight: 600;
  color: var(--text-1);
}

.tag-gap {
  margin-left: 8px;
}

.void-reason {
  color: var(--danger, #f56c6c);
}

.detail {
  display: flex;
  flex-direction: column;
  gap: var(--gap-4);
  padding: var(--gap-2) var(--gap-4) var(--gap-4) 48px;
}

.detail-block h4 {
  margin: 0 0 var(--gap-2);
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-2);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--gap-4);
}
</style>