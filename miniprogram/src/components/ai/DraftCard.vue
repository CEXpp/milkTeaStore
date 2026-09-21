<script setup lang="ts">
import type { AiDraft, AiDraftItem } from '@/api/ai'

/**
 * 草稿卡片（T33，LLD 3.4 CARD 形态）：逐项展示（名称 / 规格串 / 数量 / 单价）+ 合计 +
 * 行内数量调节与删除 + 「立即支付」。
 *
 * **行内操作走对话框**：后端的草稿只由 AI 工具集改写（T30 的 updateDraftOrder /
 * clearDraftOrder），并没有单独的「改草稿」REST 接口；而 confirm-order 用的正是服务端草稿，
 * 所以这里不能在本地改数量——否则卡片显示与实付金额会不一致。因此 −/+ 与删除都翻译成
 * 一句自然语言交给对话链路，由模型调用工具改写草稿后回传新的 CARD。
 */
// 两个 prop 都只在模板里用，故不接收 defineProps 的返回值
defineProps<{
  draft: AiDraft
  /** 正在等待后端回传（禁用行内操作，避免重复提交） */
  busy: boolean
}>()

const emit = defineEmits<{
  (event: 'change', item: AiDraftItem, delta: number): void
  (event: 'remove', item: AiDraftItem): void
  (event: 'pay'): void
}>()

function optionsText(item: AiDraftItem): string {
  return item.optionNames?.length ? item.optionNames.join(' / ') : '默认规格'
}
</script>

<template>
  <view class="draft-card">
    <view class="draft-head">
      <text class="draft-title">当前草稿</text>
      <text class="draft-count">{{ draft.items.length }} 项</text>
    </view>

    <view v-for="item in draft.items" :key="`${item.productName}-${item.optionNames.join('|')}`" class="draft-line">
      <view class="line-main">
        <view class="line-name">{{ item.productName }}</view>
        <view class="line-options">{{ optionsText(item) }}</view>
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
