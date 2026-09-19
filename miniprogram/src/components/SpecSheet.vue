<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { MenuProduct } from '@/api/menu'
import { formatCents, toCents } from '@/utils/money'
import { MAX_QUANTITY } from '@/stores/cart'

/**
 * 规格选择半屏（T25，LLD 8.2）：
 * - 单选组（杯型/温度/甜度）pill 互斥，默认选中首项（与后端「单选组恰选 1 项」校验对齐）；
 * - 多选组（加料）可 0..n 项；
 * - 实时算价：基础价 + Σ价差（本地按分累加，**仅供展示**；下单金额仍以后端计价为准，LLD 4.5）。
 */
const props = defineProps<{
  visible: boolean
  product: MenuProduct | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'add', payload: { optionIds: number[]; quantity: number; specText: string; unitCents: number }): void
}>()

/** 各规格组已选选项 id：key = 组 code（CUP_SIZE / TEMPERATURE / SWEETNESS / TOPPING） */
const selected = ref<Record<string, number[]>>({})
const quantity = ref(1)

/** 打开时重置选择：单选组默认首项，多选组默认不选 */
watch(
  () => props.visible,
  (opened) => {
    if (!opened) return
    quantity.value = 1
    const next: Record<string, number[]> = {}
    for (const group of props.product?.specGroups ?? []) {
      next[group.code] = group.multiSelect ? [] : group.options.length ? [group.options[0].id] : []
    }
    selected.value = next
  },
  { immediate: true }
)

/** 已选选项（展平，用于算价与提交） */
const selectedOptions = computed(() => {
  const product = props.product
  if (!product) return []
  const result: Array<{ id: number; name: string; priceDelta: string }> = []
  for (const group of product.specGroups) {
    const ids = selected.value[group.code] ?? []
    for (const option of group.options) {
      if (ids.includes(option.id)) {
        result.push(option)
      }
    }
  }
  return result
})

/** 单杯价（分）＝ 基础价 + Σ价差 */
const unitCents = computed(
  () => toCents(props.product?.basePrice) + selectedOptions.value.reduce((sum, o) => sum + toCents(o.priceDelta), 0)
)

/** 合计（分） */
const totalCents = computed(() => unitCents.value * quantity.value)

/** 规格文本（如「大杯/少冰/珍珠」） */
const specText = computed(() => selectedOptions.value.map((option) => option.name).join('/'))

/** 单选组是否都已选择（后端 1003 校验的前端前置拦截） */
const ready = computed(() =>
  (props.product?.specGroups ?? []).every(
    (group) => group.multiSelect || (selected.value[group.code] ?? []).length === 1 || group.options.length === 0
  )
)

function isSelected(code: string, optionId: number): boolean {
  return (selected.value[code] ?? []).includes(optionId)
}

/** 点选规格项：单选组互斥，多选组切换 */
function toggle(code: string, optionId: number, multiSelect: boolean): void {
  if (multiSelect) {
    const current = selected.value[code] ?? []
    selected.value = {
      ...selected.value,
      [code]: current.includes(optionId) ? current.filter((id) => id !== optionId) : [...current, optionId]
    }
    return
  }
  selected.value = { ...selected.value, [code]: [optionId] }
}

function changeQty(delta: number): void {
  quantity.value = Math.max(1, Math.min(MAX_QUANTITY, quantity.value + delta))
}

function close(): void {
  emit('update:visible', false)
}

function confirm(): void {
  if (!ready.value || !props.product) return
  emit('add', {
    optionIds: selectedOptions.value.map((option) => option.id),
    quantity: quantity.value,
    specText: specText.value,
    unitCents: unitCents.value
  })
  close()
}
</script>

<template>
  <view v-if="visible && product" class="sheet-mask" @click="close">
    <view class="sheet" @click.stop>
      <view class="sheet-header">
        <view class="head-main">
          <view class="sheet-name">{{ product.name }}</view>
          <view class="sheet-price">
            ￥{{ formatCents(unitCents) }}
            <text v-if="specText" class="sheet-spec">{{ specText }}</text>
          </view>
        </view>
        <view class="sheet-close" @click="close">×</view>
      </view>

      <scroll-view scroll-y class="sheet-body">
        <view v-for="group in product.specGroups" :key="group.code" class="spec-group">
          <view class="group-title">
            {{ group.name }}
            <text class="group-tag">{{ group.multiSelect ? '可多选' : '单选' }}</text>
          </view>
          <view class="pill-wrap">
            <view
              v-for="option in group.options"
              :key="option.id"
              :class="['pill', isSelected(group.code, option.id) && 'pill-active']"
              @click="toggle(group.code, option.id, group.multiSelect)"
            >
              {{ option.name }}
              <text v-if="toCents(option.priceDelta) > 0" class="pill-delta">
                +￥{{ option.priceDelta }}
              </text>
            </view>
          </view>
        </view>

        <view v-if="!product.specGroups.length" class="no-spec">该商品无需选择规格</view>
      </scroll-view>

      <view class="sheet-footer">
        <view class="qty">
          <view class="qty-btn" @click="changeQty(-1)">－</view>
          <view class="qty-value">{{ quantity }}</view>
          <view class="qty-btn" @click="changeQty(1)">＋</view>
        </view>
        <view :class="['submit-btn', !ready && 'submit-btn-disabled']" @click="confirm">
          加入购物车 ￥{{ formatCents(totalCents) }}
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 900;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: flex-end;
}

/* #ifdef H5 */
/* H5 形态下 tabBar 是 DOM 覆盖层（小程序里是原生组件不会遮住 fixed 元素），
   给遮罩留出 tabBar 高度，避免底部数量/加购按钮被挡住 */
.sheet-mask {
  padding-bottom: 50px;
}
/* #endif */

.sheet {
  width: 100%;
  max-height: 76vh;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 24rpx 24rpx 0 0;
  animation: sheet-up 0.22s ease-out;
}

@keyframes sheet-up {
  from {
    transform: translateY(100%);
  }
  to {
    transform: translateY(0);
  }
}

.sheet-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 28rpx 28rpx 16rpx;
  border-bottom: 1rpx solid #f0f2f5;
}

.sheet-name {
  font-size: 34rpx;
  font-weight: 600;
}

.sheet-price {
  margin-top: 8rpx;
  font-size: 30rpx;
  font-weight: 600;
  color: #f56c6c;
}

.sheet-spec {
  margin-left: 12rpx;
  font-size: 22rpx;
  font-weight: 400;
  color: #909399;
}

.sheet-close {
  padding: 0 8rpx;
  font-size: 44rpx;
  line-height: 1;
  color: #c0c4cc;
}

.sheet-body {
  flex: 1;
  max-height: 52vh;
  padding: 8rpx 28rpx;
}

.spec-group {
  padding: 16rpx 0;
}

.group-title {
  font-size: 28rpx;
  font-weight: 600;
  color: #303133;
  margin-bottom: 16rpx;
}

.group-tag {
  margin-left: 10rpx;
  font-size: 22rpx;
  font-weight: 400;
  color: #c0c4cc;
}

.pill-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

.pill {
  padding: 12rpx 28rpx;
  font-size: 26rpx;
  color: #606266;
  background: #f5f6f8;
  border: 1rpx solid #f5f6f8;
  border-radius: 999rpx;
}

.pill-active {
  color: #409eff;
  background: #ecf5ff;
  border-color: #409eff;
}

.pill-delta {
  font-size: 22rpx;
  color: #f56c6c;
}

.no-spec {
  padding: 32rpx 0;
  font-size: 26rpx;
  color: #909399;
}

.sheet-footer {
  display: flex;
  align-items: center;
  gap: 24rpx;
  padding: 20rpx 28rpx calc(20rpx + env(safe-area-inset-bottom));
  border-top: 1rpx solid #f0f2f5;
}

.qty {
  display: flex;
  align-items: center;
}

.qty-btn {
  width: 56rpx;
  height: 56rpx;
  line-height: 52rpx;
  text-align: center;
  font-size: 32rpx;
  color: #409eff;
  background: #ecf5ff;
  border-radius: 50%;
}

.qty-value {
  min-width: 68rpx;
  text-align: center;
  font-size: 30rpx;
}

.submit-btn {
  flex: 1;
  height: 80rpx;
  line-height: 80rpx;
  text-align: center;
  font-size: 30rpx;
  color: #fff;
  background: #409eff;
  border-radius: 999rpx;
}

.submit-btn-disabled {
  background: #c0c4cc;
}
</style>
