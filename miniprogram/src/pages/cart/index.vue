<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { createOrder } from '@/api/order'
import { getQueueEstimate, scheduleRemind, type QueueEstimate } from '@/api/queue'
import { getSubscribeTemplates, reportSubscribe, type SubscribeTemplate } from '@/api/subscribe'
import { useCartStore } from '@/stores/cart'
import { useA11yStore } from '@/stores/a11y'
import { ApiError, CODE_SHOP_PAUSED, CODE_PRODUCT_UNAVAILABLE } from '@/utils/request'
import { formatCents } from '@/utils/money'
import { toCompactDateTime } from '@/utils/datetime'
import { requestSubscribeQuota } from '@/utils/wx-subscribe'
import IcpFooter from '@/components/IcpFooter.vue'

/**
 * 购物车页（T26，LLD 8.2 / T45 11.4 / T47 下单前预期管理）：
 * - 列表项：数量步进 / 删除 / 口味备注；合计为本地展示价（后端计价才是事实来源）；
 * - 「去结算」→ POST /api/customer/orders 创建订单（金额以后端返回为准）→ 跳支付确认页；
 * - 失败降级（SRS）：1006 暂停接单 / 1002 商品变动 / 1003 规格非法 → 弹窗说明并引导回菜单刷新；
 * - 无障碍（T45）：仅放大合计金额并提升对比度，计价与下单逻辑完全不变；
 * - 下单前预期管理（T47）：结算前展示**真实队列预估**（T46 内核）并提供「稍后提醒我再点」。
 *   关键约束：该功能发生在创建订单之前——只登记一条提醒任务，**不产生任何订单**，
 *   因此 3.5 下单流程与 6.1 超时关单规则不受影响；购物车留在本地，提醒送达后不丢失。
 */

/** 规格不合法的错误码（LLD 3.2） */
const CODE_SPEC_INVALID = 1003

const cart = useCartStore()
const a11y = useA11yStore()
const submitting = ref(false)

/** 下单前队列预估（T47）：来自后端真实队列聚合，前端不做本地估算 */
const estimate = ref<QueueEstimate | null>(null)
/** 可订阅模板：点击「稍后提醒」需在手势内同步发起授权，故提前取好 */
const subscribeTemplates = ref<SubscribeTemplate[]>([])
/** 本次会话内已登记的提醒时间（登记后展示，避免重复点击） */
const remindAt = ref<string | null>(null)
const remindBusy = ref(false)

/** 「稍后提醒」只需要 REMIND 模板 */
const remindTemplates = computed(() => subscribeTemplates.value.filter((item) => item.key === 'REMIND'))

onShow(() => {
  // 从菜单页返回购物车时 store 数据仍在；此处补一次队列预估与订阅模板（均失败静默，不打断结算）
  void loadEstimate()
  void loadSubscribeTemplates()
})

/** 下单前队列预估；空购物车或请求失败时不展示该卡片。 */
async function loadEstimate(): Promise<void> {
  if (cart.isEmpty) {
    estimate.value = null
    return
  }
  try {
    estimate.value = await getQueueEstimate()
  } catch {
    estimate.value = null
  }
}

/** 预取可订阅模板（失败静默：订阅只是增强项，不打扰结算流程）。 */
async function loadSubscribeTemplates(): Promise<void> {
  try {
    subscribeTemplates.value = await getSubscribeTemplates()
  } catch {
    subscribeTemplates.value = []
  }
}

/**
 * 「稍后提醒我再点」（T47）。
 *
 * 顺序不可颠倒：订阅授权必须在**用户点击的同步调用栈内**发起（微信 2.8.2 起约束），
 * 故先调 requestSubscribeQuota 拿到 Promise，再 await 网络请求。
 * 登记成功**不产生任何订单**；即便用户拒绝授权，登记照样成功（到点因无额度被后端跳过）。
 */
async function remindLater(): Promise<void> {
  if (remindBusy.value) return
  remindBusy.value = true
  const acceptedPromise = requestSubscribeQuota(remindTemplates.value)
  try {
    const accepted = await acceptedPromise
    if (accepted.length > 0) {
      await reportSubscribe(accepted)
    }
    const result = await scheduleRemind()
    remindAt.value = result.remindAt
    uni.showToast({ title: `已登记，${result.remindAt.slice(11, 16)} 提醒你`, icon: 'none' })
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    remindBusy.value = false
  }
}

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
    // 支付截止时间用 14 位纯数字透传：时间串含空格 / 冒号，各端 URL 编解码层数与语义不一致
    const expire = toCompactDateTime(created.expireAt)
    uni.navigateTo({
      url: `/pages/pay-confirm/index?id=${created.id}${expire ? `&expire=${expire}` : ''}`
    })
  } catch (error) {
    await handleFailure(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="cart-page" :class="{ 'a11y-mode': a11y.enabled }">
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

      <!-- 下单前预期管理（T47）：真实队列预估 + 稍后提醒。只登记提醒，不产生任何订单 -->
      <view v-if="estimate" class="eta-card">
        <view class="eta-head">
          <text class="eta-label">预计等待</text>
          <text class="eta-value">
            {{ estimate.queueCups === 0 ? '无需排队' : `${estimate.etaMinutes} 分钟` }}
          </text>
        </view>
        <view class="eta-range">
          {{
            estimate.queueCups === 0
              ? '队列空闲，下单即可开始制作'
              : `当前排队 ${estimate.queueCups} 杯，波动区间 ${estimate.etaLow}–${estimate.etaHigh} 分钟（仅供参考，不做承诺）`
          }}
        </view>
        <view class="eta-remind" :class="{ 'eta-remind-busy': remindBusy }" @click="remindLater">
          {{
            remindBusy
              ? '登记中…'
              : remindAt
                ? `已登记 ${remindAt.slice(11, 16)} 提醒你`
                : '稍后提醒我再点'
          }}
        </view>
      </view>

      <view class="cart-footer">
        <view class="footer-total">
          <text class="total-label a11y-md">合计</text>
          <text class="total-amount a11y-lg">￥{{ cart.totalAmount }}</text>
          <text class="total-tip a11y-sm a11y-dim">以结算页后端金额为准</text>
        </view>
        <view class="checkout-btn" @click="checkout">
          {{ submitting ? '提交中…' : '去结算' }}
        </view>
      </view>
    </template>

    <IcpFooter />
  </view>
</template>

<style scoped>
/* 下单前预期管理卡片（T47）：展示真实队列预估，并提供「稍后提醒我再点」入口 */
.eta-card {
  padding: 24rpx;
  margin-bottom: 20rpx;
  background: #fff;
  border-radius: 20rpx;
}

.eta-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.eta-label {
  font-size: 28rpx;
  color: #606266;
}

.eta-value {
  font-size: 40rpx;
  font-weight: 700;
  color: #f56c6c;
}

.eta-range {
  margin-top: 8rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: #909399;
}

.eta-remind {
  margin-top: 20rpx;
  height: 72rpx;
  line-height: 72rpx;
  font-size: 28rpx;
  text-align: center;
  color: #409eff;
  background: #ecf5ff;
  border-radius: 999rpx;
}

.eta-remind-busy {
  color: #909399;
  background: #f4f4f5;
}

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
