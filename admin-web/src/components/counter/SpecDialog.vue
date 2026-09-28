<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { MenuProduct, MenuSpecGroup } from '@/api/menu'
import { formatCents, toCents } from '@/utils/money'

/**
 * 规格选择弹窗（T20，LLD 7.3 SpecSelector）：
 * - 单选组 pill 互斥（组内恰选 1 项，不允许取消——默认选中第一项，收银员回车即完成）；
 * - 加料多选 0..n；
 * - 实时合计 =（基础价 + Σ 价差）× 数量（分运算，仅展示；实付以后端计价为准）；
 * - 回车 = 加入草稿，Esc 取消（全程键盘可操作）。
 */
const props = defineProps<{
  product: MenuProduct | null
  visible: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'confirm', payload: { optionIds: number[]; quantity: number }): void
}>()

/** 后端 quantity 边界（LLD：1..20） */
const MIN_QTY = 1
const MAX_QTY = 20

const selectedIds = ref<number[]>([])
const quantity = ref(MIN_QTY)

// 打开弹窗（或切换商品）时重置选择：单选组默认选第一项
watch(
  () => [props.visible, props.product] as const,
  ([visible, product]) => {
    if (!visible || !product) return
    const defaults: number[] = []
    for (const group of product.specGroups) {
      if (!group.multiSelect && group.options.length > 0) {
        defaults.push(group.options[0].id)
      }
    }
    selectedIds.value = defaults
    quantity.value = MIN_QTY
  }
)

function isSelected(optionId: number): boolean {
  return selectedIds.value.includes(optionId)
}

/** 点选规格项：多选组切换；单选组组内互斥替换（保证恰选 1 项）。 */
function toggleOption(group: MenuSpecGroup, optionId: number): void {
  if (group.multiSelect) {
    selectedIds.value = isSelected(optionId)
      ? selectedIds.value.filter((id) => id !== optionId)
      : [...selectedIds.value, optionId]
    return
  }
  const groupOptionIds = group.options.map((option) => option.id)
  selectedIds.value = [...selectedIds.value.filter((id) => !groupOptionIds.includes(id)), optionId]
}

const totalCents = computed(() => {
  const product = props.product
  if (!product) return 0
  let cents = toCents(product.basePrice)
  for (const group of product.specGroups) {
    for (const option of group.options) {
      if (isSelected(option.id)) cents += toCents(option.priceDelta)
    }
  }
  return cents * quantity.value
})

function handleConfirm(): void {
  if (!props.product) return
  emit('confirm', { optionIds: [...selectedIds.value], quantity: quantity.value })
  emit('update:visible', false)
}

/** 弹窗内任意位置回车 = 加入草稿（收银键盘流） */
function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter') {
    event.preventDefault()
    handleConfirm()
  }
}
</script>

<template>
  <el-dialog
    :model-value="props.visible"
    :title="props.product?.name ?? '选择规格'"
    width="520px"
    append-to-body
    @update:model-value="emit('update:visible', $event)"
  >
    <div v-if="props.product" class="spec-dialog" @keydown="handleKeydown">
      <div class="product-brief">
        <span class="brief-name">{{ props.product.name }}</span>
        <span class="brief-price">￥{{ props.product.basePrice }}</span>
      </div>

      <div v-for="group in props.product.specGroups" :key="group.code" class="spec-group">
        <div class="spec-group-name">
          {{ group.name }}
          <span v-if="!group.multiSelect" class="required">*</span>
          <span class="group-mode">{{ group.multiSelect ? '可多选' : '单选' }}</span>
        </div>
        <div class="spec-options">
          <button
            v-for="option in group.options"
            :key="option.id"
            type="button"
            class="spec-pill"
            :class="{ active: isSelected(option.id) }"
            @click="toggleOption(group, option.id)"
          >
            {{ option.name }}
            <span v-if="option.priceDelta !== '0.00'" class="delta">+{{ option.priceDelta }}</span>
          </button>
        </div>
      </div>

      <div class="spec-group qty-group">
        <div class="spec-group-name">数量</div>
        <el-input-number v-model="quantity" :min="MIN_QTY" :max="MAX_QTY" />
      </div>
    </div>

    <template #footer>
      <div class="spec-footer">
        <span class="spec-total">合计 <b>￥{{ formatCents(totalCents) }}</b></span>
        <span class="spec-actions">
          <el-button @click="emit('update:visible', false)">取消 (Esc)</el-button>
          <el-button type="primary" @click="handleConfirm">加入草稿 (Enter)</el-button>
        </span>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.product-brief {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 10px 14px;
  margin-bottom: var(--gap-4);
  background: var(--brand-050);
  border-radius: var(--radius-md);
}

.brief-name {
  font-size: var(--fs-h2);
  font-weight: 600;
  color: var(--text-1);
}

.brief-price {
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--c-danger);
}

.spec-group + .spec-group {
  margin-top: var(--gap-4);
}

.spec-group-name {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: var(--gap-2);
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-2);
}

.required {
  color: var(--c-danger);
}

.group-mode {
  padding: 1px 8px;
  font-size: 11px;
  font-weight: 500;
  color: var(--text-3);
  background: var(--bg-subtle);
  border-radius: var(--radius-pill);
}

.spec-options {
  display: flex;
  flex-wrap: wrap;
  gap: var(--gap-2);
}

.spec-pill {
  padding: 7px 16px;
  font-size: var(--fs-body);
  color: var(--text-1);
  background: var(--bg-subtle);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-pill);
  cursor: pointer;
  font-family: inherit;
  transition:
    color var(--dur-fast) var(--ease-out),
    background-color var(--dur-fast) var(--ease-out),
    border-color var(--dur-fast) var(--ease-out),
    transform var(--dur-fast) var(--ease-out);
}

.spec-pill:hover {
  color: var(--brand-500);
  border-color: var(--brand-400);
  background: var(--brand-050);
}

.spec-pill:active {
  transform: scale(0.97);
}

.spec-pill.active {
  color: #fff;
  background: linear-gradient(135deg, var(--brand-400) 0%, var(--brand-600) 100%);
  border-color: transparent;
  box-shadow: 0 4px 12px rgba(75, 91, 214, 0.28);
}

.spec-pill .delta {
  margin-left: 4px;
  font-size: var(--fs-xs);
  opacity: 0.85;
}

.qty-group {
  padding-top: var(--gap-3);
  border-top: 1px dashed var(--border);
}

.spec-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.spec-total {
  font-size: var(--fs-body);
  color: var(--text-2);
}

.spec-total b {
  font-size: 20px;
  color: var(--c-danger);
  font-variant-numeric: tabular-nums;
}

.spec-actions {
  display: flex;
  gap: var(--gap-2);
}
</style>
