<script setup lang="ts">
import type { MenuCategory, MenuProduct } from '@/api/menu'

/**
 * 柜台点单左栏（T20，LLD 7.3 ProductPicker）：分类分组 + 商品网格。
 * 商品卡片使用原生 button，保证收银员可全程 Tab / 回车键盘操作。
 */
defineProps<{
  categories: MenuCategory[]
  loading?: boolean
}>()

const emit = defineEmits<{
  (e: 'select', product: MenuProduct): void
}>()
</script>

<template>
  <div v-loading="loading" class="product-panel">
    <template v-for="category in categories" :key="category.id">
      <div v-if="category.products.length" class="category-block">
        <h3 class="category-title">{{ category.name }}</h3>
        <div class="product-grid">
          <button
            v-for="product in category.products"
            :key="product.id"
            type="button"
            class="product-card"
            @click="emit('select', product)"
          >
            <span class="product-thumb">
              <img v-if="product.imageUrl" :src="product.imageUrl" :alt="product.name" />
              <span v-else class="thumb-placeholder">{{ product.name.slice(0, 1) }}</span>
            </span>
            <span class="product-name">{{ product.name }}</span>
            <span class="product-price">￥{{ product.basePrice }} 起</span>
          </button>
        </div>
      </div>
    </template>
    <el-empty v-if="!loading && !categories.length" description="暂无上架商品" />
  </div>
</template>

<style scoped>
.product-panel {
  min-height: 200px;
}

.category-block + .category-block {
  margin-top: 18px;
}

.category-title {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
  gap: 10px;
}

.product-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px 8px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s, transform 0.1s;
  font: inherit;
}

.product-card:hover {
  border-color: #409eff;
  box-shadow: 0 2px 10px rgba(64, 158, 255, 0.18);
}

.product-card:active {
  transform: scale(0.98);
}

.product-card:focus-visible {
  outline: 2px solid #409eff;
  outline-offset: 1px;
}

.product-thumb {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 72px;
  overflow: hidden;
  background: #f5f7fa;
  border-radius: 6px;
}

.product-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-placeholder {
  font-size: 26px;
  font-weight: 700;
  color: #c0c4cc;
}

.product-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.product-price {
  font-size: 13px;
  color: #f56c6c;
}
</style>
