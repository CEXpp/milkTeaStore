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
    :model-value="visible"
    :title="product?.name ?? '选择规格'"
    width="520px"
    append-to-body
    @update:model-value="emit('update:visible', $event)"
  >
    <div v-if="product" class="spec-dialog" @keydown="handleKeydown">
      <div v-for="group in product.specGroups" :key="group.code" class="spec-group">
        <div class="spec-group-name">
          {{ group.name }}<span v-if="!group.multiSelect" class="required">*</span>
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

      <div class="spec-group">
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
.spec-group + .spec-group {
  margin-top: 14px;
}

.spec-group-name {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
}

.required {
  margin-left: 2px;
  color: #f56c6c;
}

.spec-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.spec-pill {
  padding: 6px 16px;
  font-size: 14px;
  color: #303133;
  background: #f5f7fa;
  border: 1px solid #dcdfe6;
  border-radius: 18px;
  cursor: pointer;
  transition: all 0.2s;
  font-family: inherit;
}

.spec-pill:hover {
  border-color: #409eff;
  color: #409eff;
}

.spec-pill.active {
  color: #fff;
  background: #409eff;
  border-color: #409eff;
}

.spec-pill .delta {
  margin-left: 4px;
  font-size: 12px;
  opacity: 0.85;
}

.spec-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.spec-total {
  font-size: 14px;
  color: #606266;
}

.spec-total b {
  font-size: 20px;
  color: #f56c6c;
}
</style>
