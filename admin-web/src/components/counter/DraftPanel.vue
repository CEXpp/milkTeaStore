<script setup lang="ts">
import { computed } from 'vue'
import type { DraftLine } from '@/components/counter/types'
import { formatCents } from '@/utils/money'

/**
 * 柜台点单右栏（T20）：草稿列表（增删 / 数量步进）+ 整单备注 + 合计 + 确认收款。
 * 合计为本地展示值（分运算），实付金额以下单接口返回为准。
 * 事件契约与数量上限（1..20）保持原样，仅重做排版与视觉。
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

const totalCents = computed(() => props.lines.reduce((sum, line) => sum + line.unitCents * line.quantity, 0))

const totalCups = computed(() => props.lines.reduce((sum, line) => sum + line.quantity, 0))
</script>

<template>
  <div class="draft-panel">
    <div class="draft-head">
      <span class="draft-title">点单草稿</span>
      <span class="draft-count">{{ totalCups }} 杯</span>
      <el-button v-if="props.lines.length" link type="danger" size="small" @click="emit('clear')">清空</el-button>
    </div>

    <div class="draft-list">
      <el-empty
        v-if="!props.lines.length"
        description="点击左侧商品开始点单"
        :image-size="60"
        class="app-empty"
      />
      <TransitionGroup v-else name="list-item" tag="div" class="draft-lines">
        <div v-for="line in props.lines" :key="line.key" class="draft-line">
          <div class="line-info">
            <div class="line-name">{{ line.productName }}</div>
            <div class="line-spec">{{ line.specText || '默认规格' }}</div>
          </div>
          <div class="line-right">
            <span class="line-amount">￥{{ formatCents(line.unitCents * line.quantity) }}</span>
            <div class="line-controls">
              <button
                type="button"
                class="step-btn"
                :disabled="line.quantity <= 1"
                aria-label="减少数量"
                @click="emit('change-qty', line.key, -1)"
              >
                −
              </button>
              <span class="line-qty">{{ line.quantity }}</span>
              <button
                type="button"
                class="step-btn"
                :disabled="line.quantity >= MAX_QTY"
                aria-label="增加数量"
                @click="emit('change-qty', line.key, 1)"
              >
                +
              </button>
            </div>
          </div>
          <button type="button" class="remove-btn" aria-label="删除该行" @click="emit('remove', line.key)">
            ×
          </button>
        </div>
      </TransitionGroup>
    </div>

    <el-input
      :model-value="props.remark"
      class="draft-remark"
      placeholder="整单备注（选填，如：老客户少放糖）"
      maxlength="50"
      @update:model-value="emit('update:remark', $event)"
    />

    <div class="draft-footer">
      <div class="draft-total">
        <span class="total-label">合计</span>
        <b class="total-value">￥{{ formatCents(totalCents) }}</b>
      </div>
      <el-button
        type="primary"
        size="large"
        class="checkout-btn"
        :loading="props.submitting"
        :disabled="!props.lines.length"
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
  gap: var(--gap-2);
  padding-bottom: var(--gap-3);
  margin-bottom: var(--gap-2);
  border-bottom: 1px solid var(--border);
}

.draft-title {
  font-size: var(--fs-h2);
  font-weight: 600;
  color: var(--text-1);
}

.draft-count {
  margin-right: auto;
  padding: 1px 8px;
  font-size: var(--fs-xs);
  color: var(--brand-600);
  background: var(--brand-050);
  border-radius: var(--radius-pill);
}

.draft-list {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.draft-lines {
  display: flex;
  flex-direction: column;
  gap: var(--gap-2);
}

.draft-line {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--gap-3);
  padding: 10px 30px 10px 12px;
  background: var(--bg-subtle);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  transition:
    border-color var(--dur-fast) var(--ease-out),
    background-color var(--dur-fast) var(--ease-out);
}

.draft-line:hover {
  background: #fff;
  border-color: var(--brand-400);
}

.line-info {
  flex: 1;
  min-width: 0;
}

.line-name {
  font-size: var(--fs-body);
  font-weight: 500;
  color: var(--text-1);
}

.line-spec {
  margin-top: 3px;
  font-size: var(--fs-xs);
  color: var(--text-3);
  word-break: break-all;
}

.line-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  flex-shrink: 0;
}

.line-amount {
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--c-danger);
  font-variant-numeric: tabular-nums;
}

.line-controls {
  display: flex;
  align-items: center;
  gap: var(--gap-1);
}

.step-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  font-size: 15px;
  line-height: 1;
  color: var(--text-2);
  background: #fff;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition:
    color var(--dur-fast) var(--ease-out),
    border-color var(--dur-fast) var(--ease-out),
    background-color var(--dur-fast) var(--ease-out);
}

.step-btn:hover:not(:disabled) {
  color: var(--brand-500);
  border-color: var(--brand-400);
  background: var(--brand-050);
}

.step-btn:disabled {
  color: var(--text-3);
  cursor: not-allowed;
  opacity: 0.6;
}

.line-qty {
  min-width: 24px;
  text-align: center;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.remove-btn {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 20px;
  height: 20px;
  font-size: 15px;
  line-height: 1;
  color: var(--text-3);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  opacity: 0.5;
  transition:
    opacity var(--dur-fast) var(--ease-out),
    color var(--dur-fast) var(--ease-out),
    background-color var(--dur-fast) var(--ease-out);
}

.draft-line:hover .remove-btn {
  opacity: 1;
}

.remove-btn:hover {
  color: var(--c-danger);
  background: var(--c-danger-soft);
}

.draft-remark {
  margin: var(--gap-3) 0 0;
}

.draft-footer {
  padding-top: var(--gap-3);
  margin-top: var(--gap-2);
  border-top: 1px solid var(--border);
}

.draft-total {
  display: flex;
  align-items: baseline;
  gap: var(--gap-2);
  margin-bottom: var(--gap-3);
}

.total-label {
  font-size: var(--fs-body);
  color: var(--text-2);
}

.total-value {
  font-size: 26px;
  font-weight: 700;
  color: var(--c-danger);
  font-variant-numeric: tabular-nums;
}

.checkout-btn {
  width: 100%;
  height: 46px;
  font-size: 15px;
  font-weight: 600;
  border-radius: var(--radius-md);
}
</style>
