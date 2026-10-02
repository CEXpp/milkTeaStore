<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPageScroll, onShow } from '@dcloudio/uni-app'
import { getMenu, getShopStatus, type MenuCategory, type MenuProduct } from '@/api/menu'
import { useCartStore } from '@/stores/cart'
import { useA11yStore } from '@/stores/a11y'
import ProductCard from '@/components/ProductCard.vue'
import SpecSheet from '@/components/SpecSheet.vue'

/**
 * 菜单页（T25，LLD 8.2 / T45 11.4）：
 * - 分类锚点导航：点击滚动到分类区块，滚动时反高亮当前分类；
 * - 商品卡片（图/名/价「起」）→ 半屏规格选择（单选互斥 / 加料多选 / 实时算价）→ 加入购物车；
 * - 购物车浮动球：角标显示总杯数，点击进入购物车页（非 Tab，页面栈跳转）；
 * - 暂停接单（paused=true）：全页「暂停营业」遮罩，无法进入点单（AC-14 顾客侧）；
 * - 无障碍模式开关（T45）：仅切换呈现层样式（大字 + 高对比），不改变菜单数据、不新增接口、
 *   不影响下单与计价——开关状态只存本地，服务端完全不感知（LLD 11.4「共享逻辑、分叉呈现」）。
 */

/** 锚点滚动时的顶部留白（分类导航高度） */
const NAV_HEIGHT = 88

const menu = ref<Awaited<ReturnType<typeof getMenu>> | null>(null)
const loading = ref(true)
const activeCategoryId = ref<number | null>(null)
const sheetVisible = ref(false)
const activeProduct = ref<MenuProduct | null>(null)
/** 暂停提示语（menu 接口只给 paused，提示语取 shop-status 的 notice） */
const pauseNotice = ref('商家已暂停接单，请稍后再来')
/**
 * 营业公告（T62）：店长发布的售罄 / 新品 / 预计恢复等说明。
 *
 * 与 {@link pauseNotice} 同源（都是 shop-status.notice）但<b>用法不同</b>：公告是常态信息，
 * 顶部条常驻展示；暂停提示语只在 paused 遮罩里出现。
 */
const notice = ref('')

const cart = useCartStore()
const a11y = useA11yStore()

/** 各分类区块的绝对偏移（用于滚动反高亮） */
const sectionTops = ref<Array<{ id: number; top: number }>>([])

const categories = computed<MenuCategory[]>(() => menu.value?.categories ?? [])
const paused = computed(() => menu.value?.paused ?? false)

/**
 * 拉取菜单。
 * @param silent true = 静默刷新（已有数据时不显示加载态，用于每次回到菜单页同步最新的上下架 / 暂停状态）
 */
async function load(silent = false): Promise<void> {
  if (!silent) {
    loading.value = true
  }
  try {
    const result = await getMenu()
    menu.value = result
    const first = result.categories.find((category) => category.products.length) ?? result.categories[0]
    activeCategoryId.value = first ? first.id : null
    // 等待渲染完成后测量各分类区块位置
    setTimeout(measureSections, 300)

    // 公告始终要取（menu 契约只有 paused，没有公告字段）。
    // 暂停时才额外用它覆盖遮罩里的提示语——同一份 notice 两种用法。
    const status = await getShopStatus()
    notice.value = status.notice ?? ''
    if (result.paused && status.notice) {
      pauseNotice.value = status.notice
    }
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    if (!silent) {
      loading.value = false
    }
  }
}

/** 测量分类区块偏移（菜单为静态列表，加载后测量一次即可） */
function measureSections(): void {
  const list = categories.value
  if (!list.length) return
  const query = uni.createSelectorQuery()
  list.forEach((category) => query.select(`#cat-${category.id}`).boundingClientRect())
  // 类型定义要求回调参数（实际取数走 exec，回调留空即可）
  query.selectViewport().scrollOffset(() => undefined)
  query.exec((res) => {
    const viewport = res[res.length - 1] as { scrollTop?: number } | null
    const base = viewport?.scrollTop ?? 0
    sectionTops.value = list.map((category, index) => {
      const rect = res[index] as { top?: number } | null
      return { id: category.id, top: rect && typeof rect.top === 'number' ? rect.top + base : 0 }
    })
  })
}

/** 点击锚点：滚动到对应分类区块 */
function scrollToCategory(id: number): void {
  const query = uni.createSelectorQuery()
  query.select(`#cat-${id}`).boundingClientRect()
  // 类型定义要求回调参数（实际取数走 exec，回调留空即可）
  query.selectViewport().scrollOffset(() => undefined)
  query.exec((res) => {
    const rect = res[0] as { top?: number } | null
    const viewport = res[1] as { scrollTop?: number } | null
    if (!rect || typeof rect.top !== 'number') return
    activeCategoryId.value = id
    uni.pageScrollTo({
      scrollTop: Math.max(0, rect.top + (viewport?.scrollTop ?? 0) - NAV_HEIGHT),
      duration: 200
    })
  })
}

/** 滚动反高亮：取最后一个「已滚过」的分类 */
onPageScroll((event) => {
  if (!sectionTops.value.length) return
  const line = event.scrollTop + NAV_HEIGHT + 10
  let current = sectionTops.value[0].id
  for (const section of sectionTops.value) {
    if (section.top <= line) {
      current = section.id
    }
  }
  activeCategoryId.value = current
})

/** 打开规格选择半屏 */
function openSpec(product: MenuProduct): void {
  activeProduct.value = product
  sheetVisible.value = true
}

/** 规格确认 → 写入购物车（角标随 store 自动 +N） */
function handleAdd(payload: { optionIds: number[]; quantity: number; specText: string; unitCents: number }): void {
  const product = activeProduct.value
  if (!product) return
  cart.add({
    productId: product.id,
    productName: product.name,
    imageUrl: product.imageUrl,
    optionIds: payload.optionIds,
    specText: payload.specText,
    unitCents: payload.unitCents,
    quantity: payload.quantity
  })
  uni.showToast({ title: '已加入购物车', icon: 'success' })
}

function goCart(): void {
  uni.navigateTo({ url: '/pages/cart/index' })
}

// 首次进入显示加载态；再次回到菜单页静默刷新，同步最新的上下架 / 暂停接单状态
onShow(() => {
  void load(Boolean(menu.value))
})
</script>

<template>
  <view class="menu-page" :class="{ 'a11y-mode': a11y.enabled }">
    <!-- 营业公告条（T62）：店长发布的售罄 / 新品 / 预计恢复等说明，置顶常驻。
         不做自动消失——公告是需要被读到的信息，读完由用户自己点 × 收起。 -->
    <view v-if="notice" class="notice-bar" @click="notice = ''">
      <text class="notice-icon">📢</text>
      <text class="notice-text">{{ notice }}</text>
      <text class="notice-close">×</text>
    </view>

    <!-- 无障碍模式开关（T45）：纯呈现层开关，状态只存本地，不影响任何订单数据与统计口径 -->
    <view class="a11y-bar" @click="a11y.toggle()">
      <view class="a11y-bar-text">
        <text class="a11y-bar-title">无障碍模式</text>
        <text class="a11y-bar-desc">
          {{ a11y.enabled ? '已开启：大字高对比 + 取餐震动提醒' : '点击开启：放大字号 / 高对比 / 取餐震动' }}
        </text>
      </view>
      <view class="a11y-switch" :class="{ 'a11y-switch-on': a11y.enabled }">
        <view class="a11y-switch-dot" />
      </view>
    </view>

    <view class="category-nav">
      <scroll-view scroll-x class="nav-scroll" :show-scrollbar="false">
        <view class="nav-inner">
          <view
            v-for="category in categories"
            :key="category.id"
            :class="['nav-item', activeCategoryId === category.id && 'nav-item-active']"
            @click="scrollToCategory(category.id)"
          >
            {{ category.name }}
          </view>
        </view>
      </scroll-view>
    </view>

    <view class="product-list">
      <view v-if="loading" class="page-tip">菜单加载中…</view>

      <template v-else-if="menu">
        <view v-for="category in categories" :key="category.id" :id="`cat-${category.id}`" class="category-section">
          <view class="category-title">{{ category.name }}</view>
          <ProductCard
            v-for="product in category.products"
            :key="product.id"
            :product="product"
            @select="openSpec"
          />
          <view v-if="!category.products.length" class="category-empty">该分类暂无商品</view>
        </view>

        <view v-if="!categories.length" class="empty-box">
          <view class="empty-icon">单</view>
          <view class="empty-text">菜单还在准备中</view>
          <view class="empty-sub">请稍后再来</view>
        </view>
      </template>

      <view v-else class="empty-box">
        <view class="empty-icon">!</view>
        <view class="empty-text">菜单加载失败</view>
        <view class="empty-sub" @click="load(false)">点击重试</view>
      </view>
    </view>

    <!-- 购物车浮动球（角标 = 总杯数） -->
    <view v-if="cart.totalQuantity > 0" class="cart-ball" @click="goCart">
      <text class="cart-ball-icon">🛒</text>
      <text class="cart-badge">{{ cart.totalQuantity }}</text>
    </view>

    <SpecSheet v-model:visible="sheetVisible" :product="activeProduct" @add="handleAdd" />

    <!-- 暂停营业遮罩（SRS 3.2 / AC-14 顾客侧）：暂停时无法进入点单 -->
    <view v-if="paused" class="pause-mask">
      <view class="pause-card">
        <view class="pause-title">暂停营业</view>
        <view class="pause-notice">{{ pauseNotice }}</view>
        <view class="pause-sub">暂停期间无法下单，已下单单据不受影响</view>
        <view class="pause-refresh" @click="load(false)">刷新试试</view>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* 营业公告条（T62）：暖底色 + 左侧色条，与下方白色卡片区区分；点× 可本次收起 */
.notice-bar {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 18rpx 20rpx;
  margin-bottom: 16rpx;
  background: #fff7e8;
  border-left: 6rpx solid #e0a33c;
  border-radius: 12rpx;
}

.notice-icon {
  font-size: 28rpx;
}

.notice-text {
  flex: 1;
  font-size: 26rpx;
  line-height: 1.5;
  color: #8a6318;
}

.notice-close {
  padding: 0 8rpx;
  font-size: 34rpx;
  line-height: 1;
  color: #b08a4a;
}

/* 无障碍模式开关条（T45）：开关状态只存本地，纯呈现层 */
.a11y-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 20rpx 24rpx;
  margin-bottom: 16rpx;
  background: #fff;
  border-radius: 16rpx;
}

.a11y-bar-text {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.a11y-bar-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #303133;
}

.a11y-bar-desc {
  font-size: 24rpx;
  color: #909399;
}

.a11y-switch {
  position: relative;
  width: 88rpx;
  height: 48rpx;
  flex-shrink: 0;
  background: #dcdfe6;
  border-radius: 999rpx;
  transition: background-color 0.2s;
}

.a11y-switch-on {
  background: #07c160;
}

.a11y-switch-dot {
  position: absolute;
  top: 4rpx;
  left: 4rpx;
  width: 40rpx;
  height: 40rpx;
  background: #fff;
  border-radius: 50%;
  transition: transform 0.2s;
}

.a11y-switch-on .a11y-switch-dot {
  transform: translateX(40rpx);
}

.menu-page {
  min-height: 100vh;
  padding-bottom: 160rpx;
}

.category-nav {
  position: sticky;
  top: 0;
  z-index: 10;
  background: #fff;
  border-bottom: 1rpx solid #f0f2f5;
}

.nav-scroll {
  white-space: nowrap;
}

.nav-inner {
  display: inline-flex;
  padding: 16rpx 20rpx;
  gap: 12rpx;
}

.nav-item {
  padding: 10rpx 28rpx;
  font-size: 26rpx;
  color: #606266;
  background: #f5f6f8;
  border-radius: 999rpx;
}

.nav-item-active {
  color: #fff;
  background: #409eff;
}

.product-list {
  padding: 20rpx;
}

.category-section {
  margin-bottom: 24rpx;
}

.category-title {
  margin: 8rpx 0 16rpx;
  font-size: 28rpx;
  font-weight: 600;
  color: #303133;
}

.category-empty {
  padding: 24rpx;
  font-size: 24rpx;
  color: #c0c4cc;
  text-align: center;
  background: #fff;
  border-radius: 16rpx;
}

.cart-ball {
  position: fixed;
  right: 32rpx;
  bottom: 160rpx;
  z-index: 800;
  width: 104rpx;
  height: 104rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #409eff;
  border-radius: 50%;
  box-shadow: 0 8rpx 24rpx rgba(64, 158, 255, 0.4);
}

.cart-ball-icon {
  font-size: 48rpx;
}

.cart-badge {
  position: absolute;
  top: -8rpx;
  right: -8rpx;
  min-width: 40rpx;
  height: 40rpx;
  padding: 0 10rpx;
  line-height: 40rpx;
  text-align: center;
  font-size: 24rpx;
  color: #fff;
  background: #f56c6c;
  border-radius: 999rpx;
}

.pause-mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.6);
}

.pause-card {
  width: 520rpx;
  padding: 56rpx 40rpx;
  text-align: center;
  background: #fff;
  border-radius: 24rpx;
}

.pause-title {
  font-size: 40rpx;
  font-weight: 700;
  color: #e6a23c;
}

.pause-notice {
  margin-top: 20rpx;
  font-size: 28rpx;
  color: #303133;
}

.pause-sub {
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #909399;
}

.pause-refresh {
  margin-top: 32rpx;
  height: 72rpx;
  line-height: 72rpx;
  font-size: 28rpx;
  color: #fff;
  background: #409eff;
  border-radius: 999rpx;
}
</style>
