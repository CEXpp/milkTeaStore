<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { getStatsRanking, type StatsRankingItem } from '@/api/stats'

/**
 * 商品销量排行（T35）：range 切换「今日 / 近 7 日」，按件数降序取前 10。
 *
 * 数据自己拉（含区间切换），页面只负责摆放——统计页里唯一带自己筛选条件的表格。
 * 后端按「商品 + 商品名快照」分组（T34 口径），商品改名后历史单会以旧名单独成行，
 * 这是快照规则的正常表现，不做前端归并。
 */

const RANGE_OPTIONS = [
  { label: '今日', value: 'today' },
  { label: '近 7 日', value: '7d' }
] as const

const TOP = 10

const range = ref<'today' | '7d'>('today')
const rows = ref<StatsRankingItem[]>([])
const loading = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await getStatsRanking({ range: range.value, top: TOP })
  } catch {
    // 错误提示已由 request 层直显，这里保留上一次数据
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(range, load)
</script>

<template>
  <el-card shadow="never" class="rank-card">
    <template #header>
      <div class="card-head">
        <span class="card-title">商品销量排行</span>
        <el-radio-group v-model="range" size="small">
          <el-radio-button v-for="option in RANGE_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
      </div>
    </template>

    <el-table :data="rows" v-loading="loading" size="small" empty-text="该区间暂无销量数据">
      <el-table-column type="index" label="#" width="52" />
      <el-table-column prop="productName" label="商品" min-width="140" show-overflow-tooltip />
      <el-table-column prop="cupCount" label="件数" width="90" />
      <el-table-column prop="amount" label="金额(元)" width="110" />
    </el-table>
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
</style>
