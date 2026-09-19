<script setup lang="ts">
import { computed } from 'vue'
import type { DraftLine } from '@/components/counter/types'
import { formatCents } from '@/utils/money'

/**
 * 柜台点单右栏（T20）：草稿列表（增删 / 数量步进）+ 整单备注 + 合计 + 确认收款。
 * 合计为本地展示值（分运算），实付金额以下单接口返回为准。
 */
const props = defineProps<{
  lines: DraftLine[]
  remark: string
  submitting?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:remark', value: string): void
  (e: 'change-qty', key: string, delta: number): void
  (e: 'remove', key: string): void
  (e: 'clear'): void
  (e: 'checkout'): void
}>()

const MAX_QTY = 20

const totalCents = computed(() =>
  props.lines.reduce((sum, line) => sum + line.unitCents * line.quantity, 0)
)

const totalCups = computed(() => props.lines.reduce((sum, line) => sum + line.quantity, 0))
</script>

<template>
  <div class="draft-panel">
    <div class="draft-head">
      <span class="draft-title">点单草稿</span>
      <span class="draft-count">{{ totalCups }} 杯</span>
      <el-button v-if="lines.length" link type="danger" @click="emit('clear')">清空</el-button>
    </div>

    <div class="draft-list">
      <el-empty v-if="!lines.length" description="点击左侧商品开始点单" :image-size="60" />
      <div v-for="line in lines" :key="line.key" class="draft-line">
        <div class="line-info">
          <div class="line-name">{{ line.productName }}</div>
          <div class="line-spec">{{ line.specText || '默认规格' }}</div>
        </div>
        <div class="line-controls">
          <el-button
            size="small"
            circle
            :disabled="line.quantity <= 1"
            @click="emit('change-qty', line.key, -1)"
          >
            −
          </el-button>
          <span class="line-qty">{{ line.quantity }}</span>
          <el-button
            size="small"
            circle
            :disabled="line.quantity >= MAX_QTY"
            @click="emit('change-qty', line.key, 1)"
          >
            +
          </el-button>
        </div>
        <div class="line-amount">￥{{ formatCents(line.unitCents * line.quantity) }}</div>
        <el-button size="small" link type="danger" @click="emit('remove', line.key)">删除</el-button>
      </div>
    </div>

    <el-input
      :model-value="remark"
      class="draft-remark"
      placeholder="整单备注（选填，如：老客户少放糖）"
      maxlength="50"
      @update:model-value="emit('update:remark', $event)"
    />

    <div class="draft-footer">
      <div class="draft-total">
        合计 <b>￥{{ formatCents(totalCents) }}</b>
      </div>
      <el-button
        type="primary"
        size="large"
        class="checkout-btn"
        :loading="submitting"
        :disabled="!lines.length"
        @click="emit('checkout')"
      >
        确认收款（Ctrl+Enter）
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.draft-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 320px;
}

.draft-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.draft-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.draft-count {
  font-size: 12px;
  color: #909399;
}

.draft-list {
  flex: 1;
  overflow-y: auto;
  max-height: calc(100vh - 320px);
}

.draft-line {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed #ebeef5;
}

.line-info {
  flex: 1;
  min-width: 0;
}

.line-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.line-spec {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
  word-break: break-all;
}

.line-controls {
  display: flex;
  align-items: center;
  gap: 4px;
}

.line-qty {
  min-width: 22px;
  text-align: center;
  font-weight: 600;
}

.line-amount {
  min-width: 76px;
  text-align: right;
  font-size: 14px;
  font-weight: 600;
  color: #f56c6c;
}

.draft-remark {
  margin: 10px 0;
}

.draft-footer {
  padding-top: 10px;
  border-top: 1px solid #ebeef5;
}

.draft-total {
  margin-bottom: 8px;
  font-size: 15px;
  color: #606266;
}

.draft-total b {
  font-size: 24px;
  color: #f56c6c;
}

.checkout-btn {
  width: 100%;
}
</style>
