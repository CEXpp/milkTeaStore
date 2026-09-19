<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { createOrder } from '@/api/order'
import { useCartStore } from '@/stores/cart'
import { ApiError, CODE_SHOP_PAUSED, CODE_PRODUCT_UNAVAILABLE } from '@/utils/request'
import { formatCents } from '@/utils/money'

/**
 * 购物车页（T26，LLD 8.2）：
 * - 列表项：数量步进 / 删除 / 口味备注；合计为本地展示价（后端计价才是事实来源）；
 * - 「去结算」→ POST /api/customer/orders 创建订单（金额以后端返回为准）→ 跳支付确认页；
 * - 失败降级（SRS）：1006 暂停接单 / 1002 商品变动 / 1003 规格非法 → 弹窗说明并引导回菜单刷新。
 */

/** 规格不合法的错误码（LLD 3.2） */
const CODE_SPEC_INVALID = 1003

const cart = useCartStore()
const submitting = ref(false)

// 从菜单页返回购物车时，store 数据仍在；此处无需额外加载
onShow(() => {
  // 预留：如需在结算前校验暂停状态，可在此刷新 shop-status（当前由下单接口的 1006 兜底）
})

/** uni-app input 事件的 value 位于 event.detail.value */
type UniInputEvent = { detail: { value: string } }

function onRemarkInput(key: string, event: Event): void {
  cart.setRemark(key, (event as unknown as UniInputEvent).detail.value)
}

function changeQty(key: string, delta: number): void {
  cart.updateQty(key, delta)
}

function removeLine(key: string): void {
  uni.showModal({
    title: '删除商品',
    content: '确定从购物车移除这一行吗？',
    success: (res) => {
      if (res.confirm) {
        cart.remove(key)
      }
    }
  })
}

function clearCart(): void {
  if (cart.isEmpty) return
  uni.showModal({
    title: '清空购物车',
    content: '确定清空购物车中的全部商品吗？',
    success: (res) => {
      if (res.confirm) {
        cart.clear()
      }
    }
  })
}

function goMenu(): void {
  uni.switchTab({ url: '/pages/menu/index' })
}

function showModal(title: string, content: string): Promise<void> {
  return new Promise((resolve) => {
    uni.showModal({
      title,
      content,
      showCancel: false,
      confirmText: '去菜单看看',
      success: () => resolve()
    })
  })
}

/** 结算失败降级：按错误码给出可执行的下一步（SRS 3 降级语义） */
async function handleFailure(error: unknown): Promise<void> {
  const code = error instanceof ApiError ? error.code : -1
  if (code === CODE_SHOP_PAUSED) {
    await showModal('店铺暂停接单', '商家已暂停接单，暂时无法下单；已下单的订单不受影响。')
    goMenu()
    return
  }
  if (code === CODE_PRODUCT_UNAVAILABLE || code === CODE_SPEC_INVALID) {
    await showModal('商品有变动', '部分商品已下架或规格有调整，请回菜单重新选择。')
    goMenu()
    return
  }
  // 其余错误（网络 / 参数 / 登录）已由 request 层 toast 直显
}

/** 去结算：创建订单（后端算价）→ 支付确认页 */
async function checkout(): Promise<void> {
  if (cart.isEmpty) {
    uni.showToast({ title: '购物车是空的', icon: 'none' })
    return
  }
  if (submitting.value) return
  submitting.value = true
  try {
    const created = await createOrder({
      items: cart.toOrderItems(),
      remark: cart.orderRemark() || undefined
    })
    // 注意：navigateTo 会自行编码 query，此处传原始值避免二次编码
    uni.navigateTo({ url: `/pages/pay-confirm/index?id=${created.id}&expire=${created.expireAt}` })
  } catch (error) {
    await handleFailure(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="cart-page">
    <view v-if="cart.isEmpty" class="empty-box">
      <view class="empty-icon">车</view>
      <view class="empty-text">购物车是空的</view>
      <view class="empty-sub" @click="goMenu">去菜单挑一杯</view>
    </view>

    <template v-else>
      <view class="cart-header">
        <text class="header-title">共 {{ cart.totalQuantity }} 杯</text>
        <text class="header-clear" @click="clearCart">清空</text>
      </view>

      <view class="cart-list">
        <view v-for="item in cart.items" :key="item.key" class="cart-line">
          <view class="line-main">
            <view class="line-info">
              <view class="line-name">{{ item.productName }}</view>
              <view v-if="item.specText" class="line-spec">{{ item.specText }}</view>
            </view>
            <view class="line-price">￥{{ formatCents(item.unitCents) }}</view>
          </view>

          <view class="line-remark">
            <input
              class="remark-input"
              type="text"
              :value="item.remark"
              placeholder="口味备注（选填，如：少放糖）"
              maxlength="30"
              @input="(event) => onRemarkInput(item.key, event)"
            />
          </view>

          <view class="line-actions">
            <view class="line-remove" @click="removeLine(item.key)">删除</view>
            <view class="qty">
              <view class="qty-btn" @click="changeQty(item.key, -1)">－</view>
              <view class="qty-value">{{ item.quantity }}</view>
              <view class="qty-btn" @click="changeQty(item.key, 1)">＋</view>
            </view>
          </view>
        </view>
      </view>

      <view class="cart-footer">
        <view class="footer-total">
          <text class="total-label">合计</text>
          <text class="total-amount">￥{{ cart.totalAmount }}</text>
          <text class="total-tip">以结算页后端金额为准</text>
        </view>
        <view class="checkout-btn" @click="checkout">
          {{ submitting ? '提交中…' : '去结算' }}
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.cart-page {
  min-height: 100vh;
  padding-bottom: 200rpx;
}

.cart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 28rpx 8rpx;
}

.header-title {
  font-size: 26rpx;
  color: #909399;
}

.header-clear {
  font-size: 26rpx;
  color: #f56c6c;
}

.cart-list {
  padding: 0 20rpx;
}

.cart-line {
  padding: 24rpx;
  margin-bottom: 16rpx;
  background: #fff;
  border-radius: 16rpx;
}

.line-main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.line-info {
  flex: 1;
  min-width: 0;
}

.line-name {
  font-size: 30rpx;
  font-weight: 600;
}

.line-spec {
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #909399;
}

.line-price {
  font-size: 30rpx;
  font-weight: 600;
  color: #f56c6c;
}

.line-remark {
  margin-top: 16rpx;
}

.remark-input {
  height: 64rpx;
  padding: 0 20rpx;
  font-size: 26rpx;
  background: #f5f6f8;
  border-radius: 12rpx;
}

.line-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16rpx;
}

.line-remove {
  font-size: 26rpx;
  color: #909399;
}

.qty {
  display: flex;
  align-items: center;
}

.qty-btn {
  width: 52rpx;
  height: 52rpx;
  line-height: 48rpx;
  text-align: center;
  font-size: 30rpx;
  color: #409eff;
  background: #ecf5ff;
  border-radius: 50%;
}

.qty-value {
  min-width: 64rpx;
  text-align: center;
  font-size: 30rpx;
}

.cart-footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 800;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20rpx 28rpx calc(20rpx + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1rpx solid #f0f2f5;
}

.footer-total {
  display: flex;
  flex-direction: column;
}

.total-label {
  font-size: 22rpx;
  color: #909399;
}

.total-amount {
  font-size: 40rpx;
  font-weight: 700;
  color: #f56c6c;
}

.total-tip {
  font-size: 20rpx;
  color: #c0c4cc;
}

.checkout-btn {
  min-width: 240rpx;
  height: 80rpx;
  line-height: 80rpx;
  text-align: center;
  font-size: 30rpx;
  color: #fff;
  background: #409eff;
  border-radius: 999rpx;
}

/* #ifdef H5 */
/* H5 下 tabBar 为 DOM 覆盖层，把结算栏顶到 tabBar 之上 */
.cart-footer {
  bottom: 50px;
}
/* #endif */
</style>
