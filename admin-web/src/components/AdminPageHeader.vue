<script setup lang="ts">
import { useRouter } from 'vue-router'

/**
 * 商家端业务页统一顶栏（T21-T23，T35 补统计入口）：返回看板 + 页面标题 + 业务域互跳。
 * 右侧操作区由使用方通过默认插槽提供（如「新建商品」「新建分类」按钮）。
 */
defineProps<{
  title: string
}>()

const router = useRouter()
</script>

<template>
  <header class="page-topbar">
    <div class="topbar-left">
      <el-button link type="primary" @click="router.push('/board')">← 返回看板</el-button>
      <span class="page-title">{{ title }}</span>
      <el-button link type="primary" @click="router.push('/products')">商品管理</el-button>
      <el-button link type="primary" @click="router.push('/categories')">分类管理</el-button>
      <el-button link type="primary" @click="router.push('/specs')">规格模板</el-button>
      <el-button link type="primary" @click="router.push('/stats')">账台统计</el-button>
    </div>
    <div class="topbar-right">
      <slot />
    </div>
  </header>
</template>

<style scoped>
.page-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  padding: 12px 16px;
  margin-bottom: 16px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
}
</style>
