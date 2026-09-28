<script setup lang="ts">
/**
 * 业务页轻量页头：标题 + 可选副说明 + 右侧操作插槽。
 * 导航职责已由 AdminLayout 的侧栏承担，这里只做页面级标题与操作区（如「新建商品」）。
 * props 保持向后兼容：使用方只需传 title，右侧继续用默认插槽。
 */
defineProps<{
  title: string
  /** 标题右侧的一句说明（选填） */
  subtitle?: string
}>()
</script>

<template>
  <header class="page-header">
    <div class="header-left">
      <h2 class="page-title">{{ title }}</h2>
      <span v-if="subtitle" class="page-subtitle">{{ subtitle }}</span>
    </div>
    <div class="header-right">
      <slot />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--gap-3);
  margin-bottom: var(--gap-4);
}

.header-left {
  display: flex;
  align-items: baseline;
  gap: var(--gap-3);
  min-width: 0;
}

.page-title {
  position: relative;
  padding-left: 12px;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0.2px;
  color: var(--text-1);
}

/* 标题左侧靛蓝指示条：与侧栏激活态呼应 */
.page-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 16px;
  border-radius: var(--radius-pill);
  background: linear-gradient(180deg, var(--brand-400) 0%, var(--brand-600) 100%);
}

.page-subtitle {
  font-size: var(--fs-xs);
  color: var(--text-3);
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--gap-2);
  flex-wrap: wrap;
}
</style>
