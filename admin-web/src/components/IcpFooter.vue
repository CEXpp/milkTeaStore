<script setup lang="ts">
/**
 * 全站统一 ICP 备案页脚（符合工信部备案要求）。
 * - 常规页面（浅色背景）直接使用；
 * - 深色背景页面（如登录页）传入 dark。
 * 同时悬挂公安联网备案号（符合《计算机信息网络国际联网安全保护管理办法》
 * 关于在网站首页底部悬挂公安备案号并链接至公安部查询页的要求）。
 */

// 公安部「互联网站安全服务备案平台」核验地址（code 为公安备案号数字部分）
const GA_BEIAN_QUERY_URL = 'https://beian.mps.gov.cn/#/query/webSearch?code=44098302441295'

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
    <span class="icp-divider" aria-hidden="true">|</span>
    <a
      class="icp-link icp-ga"
      :href="GA_BEIAN_QUERY_URL"
      target="_blank"
      rel="noopener noreferrer"
    >
      <img class="icp-ga-badge" src="/beian/ghs.png" width="14" height="14" alt="公安备案" />
      <span>粤公网安备44098302441295号</span>
    </a>
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

.icp-divider {
  margin: 0 10px;
  opacity: 0.5;
}

.icp-ga {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.icp-ga-badge {
  display: block;
  flex: none;
}

.icp-footer.is-dark {
  color: rgba(255, 255, 255, 0.45);
}

.icp-footer.is-dark .icp-link:hover {
  color: rgba(255, 255, 255, 0.85);
}
</style>
