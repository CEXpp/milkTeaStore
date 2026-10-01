<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { getOrderDetail, payOrder, type OrderDetail } from '@/api/order'
import { getSubscribeTemplates, reportSubscribe, type SubscribeTemplate } from '@/api/subscribe'
import { useCartStore } from '@/stores/cart'
import { useA11yStore } from '@/stores/a11y'
import { ApiError } from '@/utils/request'
import { formatCents } from '@/utils/money'
import { fromCompactDateTime } from '@/utils/datetime'
import { requestSubscribeQuota } from '@/utils/wx-subscribe'

/**
 * 支付确认页（T26，LLD 5.2「前端支付 UI 流三渠道统一」）：
 * - 进入即按订单 id 拉取后端详情，金额与明细一律以后端为准（本地购物车金额仅作对比提示）；
 * - 「模拟支付」→ POST /orders/{id}/pay → 成功后清空购物车并跳订单详情（取餐码页）；
 * - 真店期把「模拟支付」按钮换成微信支付组件即可，页面流与跳转不变（LLD 5.2）。
 *
 * T44 订阅消息授权（SRS 3.1「不弹任何授权窗口」）：
 * - 授权时机落在「支付」这一次用户点击的调用栈内——微信 2.8.2 起只允许
 *   「用户点击行为或支付回调后」调起订阅面板，而练手期 Mock 支付没有支付回调，故落在点击栈内；
 * - 因此模板 ID 必须**提前**拉好（见 loadSubscribeTemplates），点击时直接用，中间不能 await 网络请求；
 * - 用户拒绝 / 取不到模板 / 宿主不支持 → 一律静默跳过，不影响支付与取餐（SRS 9.3 降级）。
 */

const cart = useCartStore()
const a11y = useA11yStore()

const orderId = ref<number | null>(null)
/** 支付截止时间（下单响应带回，14 位纯数字透传，详情接口不含该字段） */
const expireAt = ref('')
const order = ref<OrderDetail | null>(null)
const loading = ref(true)
const paying = ref(false)
/** 可订阅模板：必须在用户点击前取好，点击时才能同步发起授权（见文件头注释） */
const subscribeTemplates = ref<SubscribeTemplate[]>([])

onLoad((query) => {
  const id = Number(query?.id)
  orderId.value = Number.isFinite(id) && id > 0 ? id : null
  expireAt.value = fromCompactDateTime(query?.expire ? String(query.expire) : '')
  void load()
  void loadSubscribeTemplates()
})

/** 预取可订阅模板；失败静默（订阅只是增强，不弹提示打扰支付流程）。 */
async function loadSubscribeTemplates(): Promise<void> {
  try {
    subscribeTemplates.value = await getSubscribeTemplates()
  } catch {
    subscribeTemplates.value = []
  }
}

async function load(): Promise<void> {
  if (!orderId.value) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    order.value = await getOrderDetail(orderId.value)
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    loading.value = false
  }
}

/** 本地购物车金额与后端订单金额不一致（后端改价场景）：以后端为准并提示 */
const priceChanged = computed(() => {
  if (!order.value || cart.isEmpty) return false
  return formatCents(cart.totalCents) !== order.value.totalAmount
})

function goMenu(): void {
  uni.switchTab({ url: '/pages/menu/index' })
}

/** 模拟支付（Mock 通道立即成功）；真店期此处替换为微信支付组件调起 */
async function pay(): Promise<void> {
  if (!orderId.value || paying.value || !order.value) return
  if (order.value.status !== 'PENDING_PAYMENT') {
    uni.showToast({ title: '该订单无需支付', icon: 'none' })
    return
  }
  paying.value = true
  try {
    // T44：订阅授权必须在「点击行为」的同步调用栈内发起，故先于任何 await；
    // 授权结果留到支付成功后再上报（授权本身失败只影响订阅提醒，不影响支付）
    const acceptedPromise = requestSubscribeQuota(subscribeTemplates.value)
    const result = await payOrder(orderId.value)
    // 支付成功：清空购物车 → 上报订阅授权 → 替换页面栈为取餐码页（返回键不再回到支付页）
    cart.clear()
    await reportSubscribeQuota(acceptedPromise)
    uni.redirectTo({ url: `/pages/order-detail/index?id=${result.id}` })
  } catch (error) {
    await handlePayFailure(error)
  } finally {
    paying.value = false
  }
}

/**
 * 上报订阅授权结果（T44）：服务端登记后，订单进入制作 / 出餐才会推微信消息给顾客。
 * 任何失败都静默吞掉——订阅是增强项，不能污染支付成功这条主链路（SRS 9.3 降级）。
 */
async function reportSubscribeQuota(acceptedPromise: Promise<string[]>): Promise<void> {
  try {
    const accepted = await acceptedPromise
    if (accepted.length > 0) {
      await reportSubscribe(accepted)
    }
  } catch {
    // 静默降级：授权或上报失败都不影响支付结果
  }
}

/** 支付失败降级：重复支付 / 订单已关闭等状态冲突按订单真实状态引导 */
async function handlePayFailure(error: unknown): Promise<void> {
  const code = error instanceof ApiError ? error.code : -1
  if (code !== 1004) {
    return // 其余错误已由 request 层 toast 直显
  }
  await load()
  const status = order.value?.status
  if (status === 'PAID' || status === 'PREPARING' || status === 'COMPLETED') {
    // 已支付（如重复点击）：直接进详情看取餐码
    cart.clear()
    uni.redirectTo({ url: `/pages/order-detail/index?id=${orderId.value}` })
    return
  }
  uni.showModal({
    title: '订单不可支付',
    content: status === 'CLOSED' ? '订单已超时关闭，请重新下单。' : '订单状态已变更，请重新下单。',
    showCancel: false,
    confirmText: '回菜单',
    success: goMenu
  })
}

function cancel(): void {
  uni.navigateBack()
}
</script>

<template>
  <view class="pay-page" :class="{ 'a11y-mode': a11y.enabled }">
    <view v-if="loading" class="page-tip">订单加载中…</view>

    <template v-else-if="order">
      <view class="amount-card">
        <view class="amount-label a11y-md a11y-dim">应付金额</view>
        <view class="amount-value a11y-lg">￥{{ order.totalAmount }}</view>
        <view class="amount-order a11y-sm a11y-dim">订单号 {{ order.orderNo }}</view>
        <view v-if="expireAt" class="amount-expire">请于 {{ expireAt }} 前完成支付（超时自动关单）</view>
      </view>

      <view v-if="priceChanged" class="price-tip">
        商品价格已更新，结算金额以订单金额 ￥{{ order.totalAmount }} 为准
      </view>

      <view class="detail-card">
        <view v-for="(item, index) in order.items" :key="index" class="detail-line">
          <view class="detail-main">
            <text class="detail-name">{{ item.productName }}</text>
            <text class="detail-qty">x{{ item.quantity }}</text>
          </view>
          <view v-if="item.options.length" class="detail-spec">
            {{ item.options.map((option) => option.optionName).join('/') }}
          </view>
          <view class="detail-amount">￥{{ item.itemAmount }}</view>
        </view>
        <view v-if="order.remark" class="detail-remark">备注：{{ order.remark }}</view>
      </view>

      <view class="pay-footer">
        <view class="cancel-btn" @click="cancel">稍后再付</view>
        <view class="pay-btn" @click="pay">{{ paying ? '支付中…' : '模拟支付' }}</view>
      </view>
    </template>

    <view v-else class="empty-box">
      <view class="empty-icon">!</view>
      <view class="empty-text">订单加载失败</view>
      <view class="empty-sub" @click="goMenu">回菜单重新下单</view>
    </view>
  </view>
</template>

<style scoped>
.pay-page {
  min-height: 100vh;
  padding: 24rpx 24rpx 200rpx;
}

.amount-card {
  padding: 48rpx 32rpx;
  text-align: center;
  background: #fff;
  border-radius: 20rpx;
}

.amount-label {
  font-size: 26rpx;
  color: #909399;
}

.amount-value {
  margin: 16rpx 0;
  font-size: 76rpx;
  font-weight: 700;
  color: #f56c6c;
}

.amount-order {
  font-size: 24rpx;
  color: #606266;
}

.amount-expire {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #c0c4cc;
}

.price-tip {
  margin-top: 16rpx;
  padding: 16rpx 24rpx;
  font-size: 24rpx;
  color: #b88230;
  background: #fdf6ec;
  border-radius: 12rpx;
}

.detail-card {
  margin-top: 16rpx;
  padding: 24rpx;
  background: #fff;
  border-radius: 20rpx;
}

.detail-line {
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f5f6f8;
}

.detail-line:last-child {
  border-bottom: none;
}

.detail-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.detail-name {
  font-size: 28rpx;
  font-weight: 600;
}

.detail-qty {
  font-size: 26rpx;
  color: #909399;
}

.detail-spec {
  margin-top: 6rpx;
  font-size: 24rpx;
  color: #909399;
}

.detail-amount {
  margin-top: 6rpx;
  font-size: 26rpx;
  color: #f56c6c;
  text-align: right;
}

.detail-remark {
  margin-top: 16rpx;
  font-size: 24rpx;
  color: #909399;
}

.pay-footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  gap: 20rpx;
  padding: 20rpx 28rpx calc(20rpx + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1rpx solid #f0f2f5;
}

.cancel-btn {
  width: 240rpx;
  height: 84rpx;
  line-height: 84rpx;
  text-align: center;
  font-size: 30rpx;
  color: #606266;
  border: 1rpx solid #dcdfe6;
  border-radius: 999rpx;
}

.pay-btn {
  flex: 1;
  height: 84rpx;
  line-height: 84rpx;
  text-align: center;
  font-size: 32rpx;
  color: #fff;
  background: #07c160;
  border-radius: 999rpx;
}
</style>
