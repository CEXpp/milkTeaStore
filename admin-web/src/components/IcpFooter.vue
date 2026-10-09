<script setup lang="ts">
/**
 * 全站统一 ICP 备案页脚（符合工信部备案要求）。
 * - 常规页面（浅色背景）直接使用；
 * - 深色背景页面（如登录页）传入 dark。
 */
withDefaults(defineProps<{ dark?: boolean }>(), { dark: false })
</script>

<template>
  <footer class="icp-footer" :class="{ 'is-dark': dark }">
    <a
      class="icp-link"
      href="https://beian.miit.gov.cn/"
      target="_blank"
      rel="noopener noreferrer"
    >粤ICP备2026150217号</a>
  </footer>
</template>

<style scoped>
.icp-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 10px 16px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--text-3, #94a3b8);
  user-select: none;
}

.icp-link {
  color: inherit;
  text-decoration: none;
  transition: color var(--dur-base, 0.2s) var(--ease-out, ease);
}

.icp-link:hover {
  color: var(--brand-500, #6366f1);
  text-decoration: underline;
}

.icp-footer.is-dark {
  color: rgba(255, 255, 255, 0.45);
}

.icp-footer.is-dark .icp-link:hover {
  color: rgba(255, 255, 255, 0.85);
}
</style>
