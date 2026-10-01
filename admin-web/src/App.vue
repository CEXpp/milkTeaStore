<script setup lang="ts">
// 根组件仅承载路由出口：登录页为独立路由，业务页由 AdminLayout 嵌套承载。
// 全局基础样式已抽到 src/styles/*，在 main.ts 中按序引入。
</script>

<template>
  <router-view />
</template>
