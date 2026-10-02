<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { redeemDelegate, type DelegatePickup } from '@/api/delegate'
import { ApiError } from '@/utils/request'
import { STATUS_TEXT } from '@/utils/order-status'
import { useA11yStore } from '@/stores/a11y'

/**
 * 代取凭证页（T65，C4e · W15）。
 *
 * **公开页**：代取人可能从未登录过小程序，靠令牌自身授权（URL 上的 token）。
 * 打开即核销 —— 令牌一次性，防转发链条。
 *
 * 页面刻意只呈现「取餐码 + 商品概要」：金额、支付信息由后端取数时就不返回，
 * 前端无处可渲染（而非「因为渲染了才藏起来」）。
 */

const a11y = useA11yStore()
const token = ref('')
const data = ref<DelegatePickup | null>(null)
const loading = ref(true)
/** 核销失败原因（令牌失效 / 已用过 / 已撤销统一一句话） */
const errorTip = ref('')

onLoad((query) => {
  token.value = String(query?.token ?? '').trim()
  // 支持扫码 / 分享链接带入的代取人标识（选填，仅供店员识别来者）
  const proxyTag = String(query?.proxyTag ?? '').trim()
  if (!token.value) {
    loading.value = false
    errorTip.value = '缺少取餐凭证，请让下单人重新分享'
    return
  }
  void load(proxyTag || undefined)
})

async function load(proxyTag?: string): Promise<void> {
  loading.value = true
  try {
    data.value = await redeemDelegate(token.value, proxyTag)
  } catch (e) {
    // 后端不区分「过期 / 已用 / 已撤销」——区分等于把状态泄露给持有令牌的人
    errorTip.value =
      e instanceof ApiError ? e.message : '取餐凭证已失效，请联系下单人重新获取'
  } finally {
    loading.value = false
  }
}

/** 大写取餐码字号：代取人在店门口隔着柜台也要看清 */
function codeFontSize(): string {
  return a11y.enabled ? '120rpx' : '96rpx'
}

function backHome(): void {
  uni.switchTab({ url: '/pages/menu/index' })
}
</script>

<template>
  <view class="delegate-page" :class="{ 'a11y-mode': a11y.enabled }">
    <view v-if="loading" class="page-tip">正在核验取餐凭证…</view>

    <template v-else-if="data">
      <view class="code-card">
        <text class="code-label">取餐码</text>
        <text class="code-value" :style="{ fontSize: codeFontSize() }">{{ data.pickupCode }}</text>
        <text class="code-order">订单号 {{ data.orderNo }}</text>
        <text class="code-status">{{ STATUS_TEXT[data.status] ?? data.status }}</text>
      </view>

      <view class="section">
        <text class="section-title">商品概要</text>
        <view v-for="(item, idx) in data.items" :key="idx" class="item-row">
          <text class="item-name">{{ item.productName }} ×{{ item.quantity }}</text>
          <text v-if="item.specSummary" class="item-spec">{{ item.specSummary }}</text>
        </view>
      </view>

      <!-- 明确告知已核销，避免代取人以为可以再打开一次 -->
      <view class="used-tip">
        凭证已核销（一次性）：本页刷新后即失效，请勿关闭前截图保存取餐码。
      </view>
      <text class="expire-hint">凭证有效期至 {{ data.expiresAt }}</text>
    </template>

    <template v-else>
      <view class="empty-box">
        <text class="empty-icon">!</text>
        <text class="empty-text">{{ errorTip }}</text>
        <text class="empty-sub" @click="backHome">返回首页</text>
      </view>
    </template>
  </view>
</template>

<style scoped>
.delegate-page {
  min-height: 100vh;
  padding: 24rpx;
  background: #f5f6f8;
}

/* 取餐码卡：全页视觉重心，店员隔柜台也要能看清 */
.code-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
  padding: 48rpx 24rpx;
  margin-bottom: 24rpx;
  background: #fff;
  border-radius: 16rpx;
}

.code-label {
  font-size: 26rpx;
  color: #909399;
}

.code-value {
  font-weight: 700;
  letter-spacing: 8rpx;
  color: #3b49b8;
}

.code-order {
  font-size: 24rpx;
  color: #909399;
}

.code-status {
  padding: 4rpx 16rpx;
  font-size: 24rpx;
  color: #2fa36b;
  background: #e9f7f0;
  border-radius: 20rpx;
}

.section {
  padding: 24rpx;
  margin-bottom: 24rpx;
  background: #fff;
  border-radius: 12rpx;
}

.section-title {
  display: block;
  margin-bottom: 16rpx;
  font-size: 28rpx;
  font-weight: 600;
  color: #303133;
}

.item-row {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 10rpx 0;
  border-bottom: 1rpx solid #f0f2f5;
}

.item-row:last-child {
  border-bottom: none;
}

.item-name {
  flex-shrink: 0;
  font-size: 26rpx;
  color: #303133;
}

.item-spec {
  font-size: 24rpx;
  color: #909399;
}

.used-tip {
  padding: 20rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: #b88230;
  background: #fdf6e8;
  border-radius: 12rpx;
}

.expire-hint {
  display: block;
  margin-top: 16rpx;
  font-size: 22rpx;
  color: #909399;
  text-align: center;
}

.page-tip {
  padding: 80rpx 0;
  font-size: 26rpx;
  color: #909399;
  text-align: center;
}

.empty-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
  padding: 120rpx 0;
}

.empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 88rpx;
  height: 88rpx;
  font-size: 44rpx;
  color: #fff;
  background: #e0a33c;
  border-radius: 50%;
}

.empty-text {
  font-size: 28rpx;
  color: #303133;
  text-align: center;
}

.empty-sub {
  font-size: 26rpx;
  color: #3b49b8;
}
</style>
