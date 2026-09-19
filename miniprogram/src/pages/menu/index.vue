<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getMenu, type MenuResult } from '@/api/menu'

/**
 * 菜单页（T24 骨架）：打通「请求层 → 后端菜单接口」链路，T25 在此实现完整点单界面
 * （分类锚点、商品卡片、规格选择半屏、购物车浮动球、暂停营业遮罩）。
 */
const menu = ref<MenuResult | null>(null)
const loading = ref(true)

onMounted(async () => {
  try {
    const result = await getMenu()
    menu.value = result
    // T24 测试用例：request 拉到真实菜单数据（开发工具 console 观察 code=0）
    console.log('[T24] GET /api/customer/menu →', result)
  } catch {
    // 错误提示已由 request 层 toast 直显
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <view class="page-skeleton">
    <view class="page-title">菜单</view>
    <view v-if="loading" class="page-tip">菜单加载中…</view>
    <view v-else-if="!menu" class="page-tip">菜单加载失败，请下拉重试</view>
    <view v-else>
      <view class="page-tip">
        已接通菜单接口：{{ menu.categories.length }} 个分类，{{
          menu.categories.reduce((sum, category) => sum + category.products.length, 0)
        }} 个上架商品，暂停营业 = {{ menu.paused }}
      </view>
      <view class="page-tip">菜单页与规格选择将在 T25 落地。</view>
    </view>
  </view>
</template>
