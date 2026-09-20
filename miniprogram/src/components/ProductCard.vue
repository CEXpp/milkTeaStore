<script setup lang="ts">
import type { MenuProduct } from '@/api/menu'

/**
 * 菜单商品卡片（T25，LLD 8.2）：图 / 名 / 描述 / 价格（「起」）。
 * 点击卡片或右下角加号 → 打开规格选择半屏。
 */
const props = defineProps<{
  product: MenuProduct
}>()

const emit = defineEmits<{
  (e: 'select', product: MenuProduct): void
}>()

function open(): void {
  emit('select', props.product)
}
</script>

<template>
  <view class="product-card" @click="open">
    <image v-if="product.imageUrl" class="thumb" :src="product.imageUrl" mode="aspectFill" />
    <view v-else class="thumb thumb-empty">暂无图</view>

    <view class="info">
      <view class="name">{{ product.name }}</view>
      <view v-if="product.description" class="desc">{{ product.description }}</view>
      <view class="price-row">
        <text class="price">￥{{ product.basePrice }}</text>
        <text class="from">起</text>
      </view>
    </view>

    <view class="add-btn" @click.stop="open">＋</view>
  </view>
</template>

<style scoped>
.product-card {
  display: flex;
  align-items: center;
  padding: 20rpx;
  margin-bottom: 16rpx;
  background: #fff;
  border-radius: 16rpx;
}

.thumb {
  width: 160rpx;
  height: 160rpx;
  border-radius: 12rpx;
  background: #f5f6f8;
  flex-shrink: 0;
}

.thumb-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24rpx;
  color: #c0c4cc;
}

.info {
  flex: 1;
  min-width: 0;
  padding: 0 16rpx;
}

.name {
  font-size: 30rpx;
  font-weight: 600;
  color: #303133;
}

.desc {
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.price-row {
  display: flex;
  align-items: baseline;
  margin-top: 12rpx;
}

.price {
  font-size: 32rpx;
  font-weight: 600;
  color: #f56c6c;
}

.from {
  margin-left: 4rpx;
  font-size: 22rpx;
  color: #c0c4cc;
}

.add-btn {
  width: 56rpx;
  height: 56rpx;
  line-height: 52rpx;
  text-align: center;
  font-size: 36rpx;
  color: #fff;
  background: #409eff;
  border-radius: 50%;
  flex-shrink: 0;
}
</style>
