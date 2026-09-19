<script setup lang="ts">
import { useCartStore } from '@/stores/cart'

/**
 * 购物车页（T24 骨架 / 空态）：数量步进、备注、结算与 Mock 支付在 T26 落地。
 * 此处仅展示 store 已接通的本地合计（购物车 store 为 T24 交付物）。
 */
const cart = useCartStore()
</script>

<template>
  <view class="page-skeleton">
    <view class="page-title">购物车</view>
    <view v-if="cart.isEmpty" class="empty-box">
      <view class="empty-icon">车</view>
      <view class="empty-text">购物车是空的</view>
      <view class="empty-sub">返回菜单挑选商品后加入购物车</view>
    </view>
    <view v-else class="page-tip">
      共 {{ cart.totalQuantity }} 杯，本地合计 ￥{{ cart.totalAmount }}（结算流程 T26 落地）
    </view>
  </view>
</template>
