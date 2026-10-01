<script setup lang="ts">
import type { MenuCategory, MenuProduct } from '@/api/menu'

/**
 * 柜台点单左栏（T20，LLD 7.3 ProductPicker）：分类分组 + 商品网格。
 * 商品卡片使用原生 button，保证收银员可全程 Tab / 回车键盘操作（focus-visible 描边保留）。
 * 图片走原生 lazy + async 解码，避免点单页首屏被图片加载阻塞。
 */
const props = defineProps<{
  categories: MenuCategory[]
  loading?: boolean
}>()

const emit = defineEmits<{
  (e: 'select', product: MenuProduct): void
}>()

/** 无图时的占位字符（取商品名首字） */
function initial(name: string): string {
  return name.slice(0, 1)
}
</script>

<template>
  <div v-loading="props.loading" class="product-panel">
    <template v-for="(category, index) in props.categories" :key="category.id">
      <div v-if="category.products.length" class="category-block" :style="{ '--delay': `${index * 20}ms` }">
        <h3 class="category-title">
          <span class="title-bar" />
          {{ category.name }}
          <span class="count">{{ category.products.length }}</span>
        </h3>
        <div class="product-grid">
          <button
            v-for="product in category.products"
            :key="product.id"
            type="button"
            class="product-card"
            @click="emit('select', product)"
          >
            <span class="product-thumb">
              <img
                v-if="product.imageUrl"
                :src="product.imageUrl"
                :alt="product.name"
                loading="lazy"
                decoding="async"
              />
              <span v-else class="thumb-placeholder">{{ initial(product.name) }}</span>
            </span>
            <span class="product-name">{{ product.name }}</span>
            <span class="product-price">￥{{ product.basePrice }} 起</span>
          </button>
        </div>
      </div>
    </template>
    <el-empty v-if="!props.loading && !props.categories.length" description="暂无上架商品" class="app-empty" />
  </div>
</template>

<style scoped>
.product-panel {
  min-height: 200px;
}

.category-block + .category-block {
  margin-top: var(--gap-5);
}

.category-title {
  display: flex;
  align-items: center;
  gap: var(--gap-2);
  margin: 0 0 var(--gap-3);
  font-size: var(--fs-h2);
  font-weight: 600;
  color: var(--text-1);
}

.title-bar {
  width: 3px;
  height: 14px;
  border-radius: var(--radius-pill);
  background: linear-gradient(180deg, var(--brand-400) 0%, var(--brand-600) 100%);
}

.count {
  padding: 1px 8px;
  font-size: 11px;
  font-weight: 500;
  color: var(--text-2);
  background: var(--bg-subtle);
  border-radius: var(--radius-pill);
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(136px, 1fr));
  gap: var(--gap-3);
}

.product-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px 8px 12px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-xs);
  cursor: pointer;
  font: inherit;
  transition:
    transform var(--dur-fast) var(--ease-out),
    box-shadow var(--dur-base) var(--ease-out),
    border-color var(--dur-base) var(--ease-out);
  animation: card-in var(--dur-base) var(--ease-out) both;
  animation-delay: var(--delay, 0ms);
}

@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.product-card:hover {
  border-color: var(--brand-400);
  box-shadow: var(--shadow-hover);
  transform: translateY(-3px);
}

.product-card:active {
  transform: translateY(-1px) scale(0.985);
}

.product-card:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 1px;
}

.product-thumb {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 76px;
  overflow: hidden;
  background: var(--bg-subtle);
  border-radius: var(--radius-sm);
}

.product-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-placeholder {
  font-size: 26px;
  font-weight: 700;
  color: var(--text-3);
}

.product-name {
  font-size: var(--fs-body);
  font-weight: 500;
  color: var(--text-1);
  text-align: center;
  line-height: 1.35;
}

.product-price {
  font-size: var(--fs-sm);
  font-weight: 500;
  color: var(--c-danger);
}
</style>
