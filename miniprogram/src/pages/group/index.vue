<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import {
  abandonGroup,
  createGroup,
  freezeGroup,
  getGroup,
  payGroup,
  submitGroupItems,
  type GroupCart
} from '@/api/group'
import { getMenu, type MenuProduct } from '@/api/menu'
import { useCartStore } from '@/stores/cart'
import { useA11yStore } from '@/stores/a11y'

/**
 * 拼单页（T64，W13/W14）：
 * 一页承载四个阶段，靠 `canFreeze` / `status` 决定按钮，不拆四页——
 * 拆开会让「拉池→看别人的杯子→加自己的」这条主路径反复跳页。
 *
 * - 未带 uuid 进来 → 创建池（POST /groups）后停在本页；
 * - 带 uuid 进来 → 加入页：看各成员选品 + 提交自己的选品（整份替换）；
 * - 发起人 → 冻结（他人随即不可再改）→ 支付（生成一张订单、一个取餐码）。
 *
 * 金额一律以服务端返回为准（`group.totalAmount`），本页只做展示，不自算总价。
 */

const a11y = useA11yStore()
const cart = useCartStore()

const uuid = ref('')
const group = ref<GroupCart | null>(null)
const loading = ref(true)
const busy = ref(false)

/** 我的成员标识（出餐时喊「003 王工」） */
const myTag = ref('')
/** 邀请口令：分享给朋友的链接载体 */
const shareCode = ref('')
/** 拉取在售商品用于选品（与菜单页同源，只返回上架商品） */
const products = ref<MenuProduct[]>([])
/** 冻结后不再允许改单 */
const canEdit = computed(() => group.value?.status === 'OPEN' && !group.value.expired)
/** 冻结按钮（仅发起人，由服务端 canFreeze 判定） */
const canFreeze = computed(() => Boolean(group.value?.canFreeze))
/** 支付 / 放弃按钮（仅发起人且已冻结，由服务端 canPay 判定） */
const canPay = computed(() => Boolean(group.value?.canPay))

onLoad((query) => {
  const code = String(query?.uuid ?? '').trim()
  if (code) {
    uuid.value = code
  } else {
    void initPool()
  }
})

// 回到本页可能来自分享回流，需要刷新池状态（别人的选品可能已变）
onShow(() => {
  if (uuid.value) {
    void load()
  }
})

/** 新建池：创建后即分享口令 */
async function initPool(): Promise<void> {
  loading.value = true
  try {
    uuid.value = await createGroup({ minutes: 30 })
    shareCode.value = uuid.value
    await load()
    void loadProducts()
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    loading.value = false
  }
}

async function load(): Promise<void> {
  if (!uuid.value) return
  loading.value = true
  try {
    group.value = await getGroup(uuid.value)
    if (group.value.joined && !myTag.value) {
      // joined 由服务端判定（池里只有一人时客户端分不清是谁），取池中该成员的标识回填
      const mine = group.value.members[0]
      if (mine) {
        myTag.value = mine.tag ?? ''
      }
    }
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    loading.value = false
  }
}

async function loadProducts(): Promise<void> {
  try {
    const menu = await getMenu()
    products.value = menu.categories.flatMap((category) => category.products)
  } catch {
    products.value = []
  }
}

/**
 * 提交我的选品（整份替换）。
 * 池里存的是 productId/optionIds，不含金额——后端在支付那一刻重算。
 */
async function submitMine(): Promise<void> {
  if (!uuid.value || busy.value) return
  busy.value = true
  try {
    await submitGroupItems(uuid.value, {
      tag: myTag.value.trim() || undefined,
      items: cart.toOrderItems()
    })
    cart.clear()
    await load()
    uni.showToast({ title: '已加入拼单', icon: 'success' })
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    busy.value = false
  }
}

/** 退出拼单：提交空 items，后端把自己从池里移除 */
async function leave(): Promise<void> {
  if (!uuid.value || busy.value) return
  busy.value = true
  try {
    await submitGroupItems(uuid.value, { items: [] })
    cart.clear()
    await load()
    uni.showToast({ title: '已退出拼单', icon: 'none' })
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    busy.value = false
  }
}

/** 发起人冻结：冻结后他人不可再加入或改单 */
async function freeze(): Promise<void> {
  if (!uuid.value || busy.value) return
  busy.value = true
  try {
    await freezeGroup(uuid.value)
    await load()
    uni.showToast({ title: '已冻结', icon: 'success' })
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    busy.value = false
  }
}

/** 发起人支付：生成一张订单（一个取餐码 + N 个订单项） */
async function pay(): Promise<void> {
  if (!uuid.value || busy.value) return
  busy.value = true
  try {
    const result = await payGroup(uuid.value)
    cart.clear()
    uni.showToast({ title: '取餐码 ' + result.pickupCode, icon: 'none' })
    //跳订单详情看取餐码
    setTimeout(() => {
      uni.redirectTo({ url: `/pages/order-detail/index?id=${result.id}` })
    }, 1200)
  } catch {
    // 错误提示已由 request 层 toast 直显
    await load()
  } finally {
    busy.value = false
  }
}

/** 发起人放弃：池失效，不生成订单 */
async function abandon(): Promise<void> {
  if (!uuid.value || busy.value) return
  busy.value = true
  try {
    await abandonGroup(uuid.value)
    await load()
    uni.showToast({ title: '已结束拼单', icon: 'none' })
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    busy.value = false
  }
}

/** 复制口令：分享给他人 */
function copyShare(): void {
  if (!uuid.value) return
  uni.setClipboardData({
    data: uuid.value,
    success: () => uni.showToast({ title: '口令已复制', icon: 'none' })
  })
}

function goMenu(): void {
  uni.switchTab({ url: '/pages/menu/index' })
}
</script>

<template>
  <view class="group-page" :class="{ 'a11y-mode': a11y.enabled }">
    <view v-if="loading" class="page-tip">拼单加载中…</view>

    <template v-else-if="group">
      <!-- 阶段提示：让每个人一眼知道现在能不能改 -->
      <view class="status-bar" :class="'status-' + group.status.toLowerCase()">
        <text class="status-title">
          <template v-if="group.status === 'OPEN' && !group.expired">收单中</template>
          <template v-else-if="group.status === 'OPEN' && group.expired">已超时</template>
          <template v-else-if="group.status === 'FROZEN'">已冻结，等待发起人支付</template>
          <template v-else-if="group.status === 'CONVERTING'">正在生成订单…</template>
          <template v-else-if="group.status === 'CONVERTED'">已下单</template>
          <template v-else>已结束</template>
        </text>
        <text class="status-sub">
          截止 {{ group.expiresAt }} · 共 {{ group.cupCount }} 杯 · 合计 ￥{{ group.totalAmount }}
        </text>
      </view>

      <!-- 失效提示：商品下架时点名，不静默吞掉 -->
      <view v-if="group.invalidTip" class="invalid-tip">{{ group.invalidTip }}</view>

      <!-- 成员选品：每杯挂成员标识，出餐喊「003 王工」 -->
      <view class="section">
        <text class="section-title">各成员选品</text>
        <view v-for="member in group.members" :key="member.customerId" class="member-block">
          <view class="member-head">
            <text class="member-tag">{{ member.tag || '未命名成员' }}</text>
          </view>
          <view v-for="(item, idx) in member.items" :key="idx" class="item-row">
            <text class="item-name">{{ item.productName }} ×{{ item.quantity }}</text>
            <text v-if="item.specText" class="item-spec">{{ item.specText }}</text>
            <text class="item-amount">￥{{ item.itemAmount }}</text>
          </view>
        </view>
        <view v-if="!group.members.length" class="empty-text">还没有人加东西</view>
      </view>

      <!-- 我的标识（出餐时喊） -->
      <view v-if="canEdit" class="section">
        <text class="section-title">我的标识（出餐时店员会喊这个名字）</text>
        <input v-model="myTag" class="tag-input" maxlength="64" placeholder="如 003 王工" />
      </view>

      <!-- 选品入口：复用购物车，加购后提交 -->
      <view v-if="canEdit" class="section">
        <text class="section-title">我要喝的</text>
        <view v-for="product in products" :key="product.id" class="product-row" @click="goMenu">
          <text class="product-name">{{ product.name }}</text>
          <text class="product-price">￥{{ product.basePrice }} 起</text>
        </view>
        <text class="hint">点击商品到菜单页选规格，加购后回到这里提交</text>

        <view class="cart-summary">
          <text>购物车 {{ cart.totalQuantity }} 杯 · 本地估算 ￥{{ cart.totalAmount }}</text>
        </view>
        <button class="btn-primary" :disabled="busy || cart.isEmpty" @click="submitMine">
          {{ busy ? '提交中…' : '提交我的选品' }}
        </button>
        <button v-if="group.joined" class="btn-ghost" :disabled="busy" @click="leave">退出拼单</button>
      </view>

      <!-- 分享口令 -->
      <view class="section">
        <text class="section-title">邀请口令</text>
        <view class="share-row">
          <text class="share-code">{{ shareCode || group.groupUuid }}</text>
          <button class="btn-ghost" @click="copyShare">复制</button>
        </view>
        <text class="hint">把它发给朋友，让 ta 打开小程序输入口令加入</text>
      </view>

      <!-- 发起人操作区 -->
      <view v-if="canFreeze" class="section action-bar">
        <button class="btn-primary" :disabled="busy" @click="freeze">冻结并准备支付</button>
      </view>
      <view v-if="canPay" class="section action-bar">
        <button class="btn-primary" :disabled="busy" @click="pay">
          {{ busy ? '支付中…' : '发起人支付' }}
        </button>
        <button class="btn-ghost" :disabled="busy" @click="abandon">放弃本次拼单</button>
      </view>
    </template>

    <view v-else class="page-tip">拼单不存在或已结束</view>
  </view>
</template>

<style scoped>
.group-page {
  min-height: 100vh;
  padding: 24rpx;
  background: #f5f6f8;
}

/* 阶段条：颜色即语义，收单中偏蓝、冻结偏黄、失效偏灰 */
.status-bar {
  display: flex;
  flex-direction: column;
  gap: 6rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
  background: #eef1fb;
  border-left: 6rpx solid #3b49b8;
  border-radius: 12rpx;
}

.status-frozen {
  background: #fdf6e8;
  border-left-color: #e0a33c;
}

.status-converted,
.status-expired {
  background: #f1f2f6;
  border-left-color: #9ca3af;
}

.status-title {
  font-size: 32rpx;
  font-weight: 600;
  color: #1f2937;
}

.status-sub {
  font-size: 24rpx;
  color: #6b7280;
}

.invalid-tip {
  padding: 16rpx 20rpx;
  margin-bottom: 24rpx;
  font-size: 24rpx;
  color: #b88230;
  background: #fdf6e8;
  border-radius: 8rpx;
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

.member-block {
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f0f2f5;
}

.member-block:last-child {
  border-bottom: none;
}

.member-head {
  margin-bottom: 8rpx;
}

.member-tag {
  font-size: 24rpx;
  font-weight: 600;
  color: #3b49b8;
}

.item-row {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 6rpx 0;
  font-size: 24rpx;
  color: #303133;
}

.item-name {
  flex-shrink: 0;
}

.item-spec {
  flex: 1;
  overflow: hidden;
  font-size: 22rpx;
  color: #9ca3af;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.item-amount {
  margin-left: auto;
  color: #6b7280;
}

.tag-input {
  height: 64rpx;
  padding: 0 16rpx;
  font-size: 26rpx;
  background: #f8fafc;
  border: 1rpx solid #e5e9f0;
  border-radius: 8rpx;
}

.product-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f0f2f5;
}

.product-name {
  font-size: 26rpx;
  color: #303133;
}

.product-price {
  font-size: 24rpx;
  color: #9ca3af;
}

.hint {
  display: block;
  margin-top: 12rpx;
  font-size: 22rpx;
  color: #9ca3af;
}

.cart-summary {
  margin: 16rpx 0;
  font-size: 24rpx;
  color: #6b7280;
}

.share-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

.share-code {
  flex: 1;
  padding: 12rpx 16rpx;
  overflow: hidden;
  font-size: 24rpx;
  font-family: monospace;
  color: #3b49b8;
  word-break: break-all;
  background: #f8fafc;
  border-radius: 8rpx;
}

.action-bar {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.btn-primary {
  height: 80rpx;
  font-size: 28rpx;
  color: #fff;
  background: #3b49b8;
  border: none;
  border-radius: 40rpx;
}

.btn-ghost {
  height: 72rpx;
  font-size: 26rpx;
  color: #3b49b8;
  background: #eef1fb;
  border: none;
  border-radius: 36rpx;
}

.empty-text {
  padding: 24rpx 0;
  font-size: 24rpx;
  color: #9ca3af;
  text-align: center;
}

.page-tip {
  padding: 80rpx 0;
  font-size: 26rpx;
  color: #9ca3af;
  text-align: center;
}
</style>
