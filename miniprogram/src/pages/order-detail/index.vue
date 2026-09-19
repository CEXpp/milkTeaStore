<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'

/**
 * 订单详情页（T24 骨架 / 空态）：大号取餐码 + 状态时间线 + 3 秒轮询在 T27 落地。
 */
const orderId = ref<number | null>(null)

onLoad((query) => {
  const id = Number(query?.id)
  orderId.value = Number.isFinite(id) && id > 0 ? id : null
})
</script>

<template>
  <view class="page-skeleton">
    <view class="page-title">订单详情</view>
    <view class="empty-box">
      <view class="empty-icon">码</view>
      <view class="empty-text">取餐码与制作进度</view>
      <view class="empty-sub">
        订单号：{{ orderId ?? '—' }}（取餐码、状态时间线与实时轮询在 T27 落地）
      </view>
    </view>
  </view>
</template>
