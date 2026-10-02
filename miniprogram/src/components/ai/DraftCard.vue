<script setup lang="ts">
import { computed } from 'vue'
import type { AiDraft, AiDraftItem } from '@/api/ai'
import type { MenuProduct, MenuSpecGroup } from '@/api/menu'

/**
 * 草稿卡片（T33，LLD 3.4 CARD 形态）：逐项展示（名称 / 规格串 / 数量 / 单价）+ 合计 +
 * 行内数量调节与删除 + 「立即支付」。
 *
 * **行内操作走对话框**：后端的草稿只由 AI 工具集改写（T30 的 updateDraftOrder /
 * clearDraftOrder），并没有单独的「改草稿」REST 接口；而 confirm-order 用的正是服务端草稿，
 * 所以这里不能在本地改数量——否则卡片显示与实付金额会不一致。因此 −/+ 与删除都翻译成
 * 一句自然语言交给对话链路，由模型调用工具改写草稿后回传新的 CARD。
 *
 * T71（F09 · W10 前置）在此之上加**规格理解回显与一键纠错**：每个规格组可点开，
 * 选一个即翻译成一句指令改草稿。沿用同一套「翻译成自然语言」的通道——
 * 因此**修正不产生新会话**，也**不新增任何订单入口**（任务卡纪律），
 * 只是把「重说一遍」换成「点一下」。
 */
const props = defineProps<{
  draft: AiDraft
  /** 正在等待后端回传（禁用行内操作，避免重复提交） */
  busy: boolean
  /**
   * 在售商品（用于取规格组与可选项）。**按名称匹配**草稿条目——
   * 与后端 {@code DraftPricer} 的解析基准一致（都是按名字找商品），
   * 故不必给草稿条目加 productId 就能就地改规格。
   */
  products?: MenuProduct[]
}>()

const emit = defineEmits<{
  (event: 'change', item: AiDraftItem, delta: number): void
  (event: 'remove', item: AiDraftItem): void
  (event: 'spec-change', item: AiDraftItem, groupName: string, optionName: string): void
  (event: 'pay'): void
}>()

function optionsText(item: AiDraftItem): string {
  return item.optionNames?.length ? item.optionNames.join(' / ') : '默认规格'
}

/** 名称 → 商品：草稿条目按名字挂到菜单商品上（与后端解析基准一致） */
const productByName = computed(() => {
  const map = new Map<string, MenuProduct>()
  ;(props.products ?? []).forEach((product) => map.set(product.name, product))
  return map
})

/**
 * 把草稿条目当前选中的规格按组还原。
 *
 * <p>草稿只存选项名列表（{@code optionNames}），没存组归属，故按名字反查它属于哪个组——
 * 同一商品内规格项名字唯一，反查是安全的。查不到的（组被停用/改名）归入「其他」，
 * 原样展示但不给纠错入口（无从判断该换成什么）。</p>
 */
function groupedOptions(item: AiDraftItem): Array<{ group: MenuSpecGroup; picked: string[] }> {
  const product = productByName.value.get(item.productName)
  if (!product?.specGroups?.length) {
    return []
  }
  const picked = new Set(item.optionNames ?? [])
  const matched = new Set<string>()

  const groups = product.specGroups
    .map((group) => {
      const hits = (group.options ?? []).filter((option) => picked.has(option.name)).map((option) => option.name)
      hits.forEach((name) => matched.add(name))
      return { group, picked: hits }
    })
    .filter((entry) => entry.picked.length > 0)

  const orphans = (item.optionNames ?? []).filter((name) => !matched.has(name))
  if (orphans.length) {
    groups.push({
      group: { code: 'OTHER', name: '其他', multiSelect: false, options: [] } as MenuSpecGroup,
      picked: orphans
    })
  }
  return groups
}

/** 该条目是否可纠错：拿不到规格组就只读展示，不给「点了没反应」的入口。 */
function editable(item: AiDraftItem): boolean {
  return Boolean(productByName.value.get(item.productName)?.specGroups?.length)
}

/** 打开某规格组的选项列表（复用菜单的规格选项数据，不新增一份配置）。 */
function openSpecPicker(item: AiDraftItem, group: MenuSpecGroup): void {
  if (props.busy || !group.options?.length) {
    return
  }
  const picked = new Set(
    groupedOptions(item).find((entry) => entry.group.code === group.code)?.picked ?? []
  )
  const labels = group.options.map((option) => {
    const mark = picked.has(option.name) ? '✓ ' : ''
    const delta = option.priceDelta && option.priceDelta !== '0.00' ? `（+${option.priceDelta}）` : ''
    return `${mark}${option.name}${delta}`
  })
  uni.showActionSheet({
    itemList: labels,
    success: (res) => {
      const chosen = group.options[res.tapIndex]
      if (chosen && !picked.has(chosen.name)) {
        emit('spec-change', item, group.name, chosen.name)
      }
    },
    fail: () => undefined
  })
}
</script>

<template>
  <view class="draft-card">
    <view class="draft-head">
      <text class="draft-title">当前草稿</text>
      <text class="draft-count">{{ draft.items.length }} 项</text>
    </view>

    <view
      v-for="item in draft.items"
      :key="`${item.productName}-${item.optionNames.join('|')}`"
      class="draft-line"
    >
      <view class="line-main">
        <view class="line-name">{{ item.productName }}</view>

        <!-- 规格理解回显（T71）：AI 把听到的摊开给用户看，错了就在下面点一下改 -->
        <view v-if="groupedOptions(item).length" class="line-echo">
          我理解的是：{{ optionsText(item) }}
        </view>

        <!-- 规格组：可点开就地改（复用菜单的规格选项，不新增配置） -->
        <view v-if="editable(item)" class="spec-groups">
          <view
            v-for="entry in groupedOptions(item)"
            :key="entry.group.code"
            class="spec-group"
            :class="{ disabled: busy }"
            @tap="openSpecPicker(item, entry.group)"
          >
            <text class="spec-group-name">{{ entry.group.name }}</text>
            <text class="spec-group-value">{{ entry.picked.join('、') }} ›</text>
          </view>
        </view>
        <view v-else class="line-options">{{ optionsText(item) }}</view>

        <view class="line-price">单价 {{ item.unitPrice }} 元 · 小计 {{ item.itemAmount }} 元</view>
      </view>

      <view class="line-actions">
        <view class="stepper">
          <view class="step-btn" :class="{ disabled: busy }" @tap="!busy && emit('change', item, -1)">−</view>
          <text class="step-value">{{ item.quantity }}</text>
          <view class="step-btn" :class="{ disabled: busy }" @tap="!busy && emit('change', item, 1)">+</view>
        </view>
        <text class="remove-btn" :class="{ disabled: busy }" @tap="!busy && emit('remove', item)">删除</text>
      </view>
    </view>

    <view class="draft-footer">
      <view class="total">
        合计 <text class="total-amount">{{ draft.totalAmount }}</text> 元
      </view>
      <view class="pay-btn" :class="{ disabled: busy }" @tap="!busy && emit('pay')">立即支付</view>
    </view>
  </view>
</template>

<style scoped>
.draft-card {
  margin: 0 0 24rpx 80rpx;
  padding: 20rpx 24rpx;
  border-radius: 16rpx;
  background: #fff;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.06);
}

.draft-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12rpx;
  border-bottom: 1rpx solid #f0f2f5;
}

.draft-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #333;
}

.draft-count {
  font-size: 24rpx;
  color: #909399;
}

.draft-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20rpx 0;
  border-bottom: 1rpx solid #f7f8fa;
}

.line-main {
  flex: 1;
  min-width: 0;
}

.line-name {
  font-size: 30rpx;
  color: #333;
}

.line-options {
  margin-top: 6rpx;
  font-size: 24rpx;
  color: #909399;
}

/* T71 规格理解回显：比规格组醒目一档——这是「AI 听到的」，要让人先看它 */
.line-echo {
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #409eff;
}

/* T71 规格组：整行可点，右侧「›」明示可改；不给「点了没反应」的假入口 */
.spec-groups {
  margin-top: 8rpx;
}

.spec-group {
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 6rpx 0;
}

.spec-group.disabled {
  opacity: 0.5;
}

.spec-group-name {
  flex-shrink: 0;
  font-size: 22rpx;
  color: #909399;
}

.spec-group-value {
  font-size: 24rpx;
  color: #303133;
}

.line-price {
  margin-top: 6rpx;
  font-size: 24rpx;
  color: #606266;
}

.line-actions {
  display: flex;
  align-items: center;
}

.stepper {
  display: flex;
  align-items: center;
}

.step-btn {
  width: 48rpx;
  height: 48rpx;
  border-radius: 50%;
  background: #f0f2f5;
  color: #333;
  font-size: 32rpx;
  line-height: 48rpx;
  text-align: center;
}

.step-btn.disabled {
  color: #c0c4cc;
}

.step-value {
  min-width: 56rpx;
  font-size: 28rpx;
  text-align: center;
}

.remove-btn {
  margin-left: 20rpx;
  font-size: 26rpx;
  color: #f56c6c;
}

.remove-btn.disabled {
  color: #c0c4cc;
}

.draft-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 20rpx;
}

.total {
  font-size: 28rpx;
  color: #333;
}

.total-amount {
  font-size: 36rpx;
  font-weight: 600;
  color: #f56c6c;
}

.pay-btn {
  padding: 12rpx 32rpx;
  border-radius: 32rpx;
  background: #409eff;
  color: #fff;
  font-size: 28rpx;
}

.pay-btn.disabled {
  background: #a0cfff;
}
</style>
