<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NAV_GROUPS } from '@/config/nav'

/**
 * 左侧导航（蓝灰体系：深蓝灰 sidebar + 靛蓝激活态）。
 * 菜单项来自 config/nav.ts 单一数据源；点击用 router.push 而非 el-menu 的 router 模式，
 * 以便统一控制跳转行为（与 vue-router 版本解耦）。
 */
const props = defineProps<{
  collapsed: boolean
}>()

// 折叠状态由 AdminLayout 持有（v-model:collapsed），侧栏仅消费
defineEmits<{
  (e: 'update:collapsed', value: boolean): void
}>()

const route = useRoute()
const router = useRouter()

const activePath = computed(() => route.path)

function handleSelect(path: string): void {
  if (path !== route.path) void router.push(path)
}
</script>

<template>
  <aside class="admin-sidebar" :class="{ 'is-collapsed': props.collapsed }">
    <div class="brand">
      <div class="brand-mark">奶</div>
      <div v-show="!props.collapsed" class="brand-text">
        <span class="brand-name">奶茶店</span>
        <span class="brand-sub">商家后台</span>
      </div>
    </div>

    <el-scrollbar class="sidebar-scroll">
      <el-menu
        class="sidebar-menu"
        :default-active="activePath"
        :collapse="props.collapsed"
        :collapse-transition="false"
        background-color="transparent"
        text-color="#a8b0c2"
        active-text-color="#ffffff"
        @select="handleSelect"
      >
        <template v-for="group in NAV_GROUPS" :key="group.label">
          <div v-show="!props.collapsed" class="menu-group-label">{{ group.label }}</div>
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
            <el-icon>
              <component :is="item.icon" />
            </el-icon>
            <template #title>{{ item.title }}</template>
          </el-menu-item>
        </template>
      </el-menu>
    </el-scrollbar>

    <div class="sidebar-foot">
      <div v-show="!props.collapsed" class="foot-text">
        <span class="dot" />
        实时数据 · 3 秒刷新
      </div>
    </div>
  </aside>
</template>

<style scoped>
.admin-sidebar {
  display: flex;
  flex-direction: column;
  width: var(--sidebar-w);
  height: 100%;
  flex-shrink: 0;
  background: linear-gradient(180deg, #1b2233 0%, #171e2d 100%);
  transition: width var(--dur-base) var(--ease-out);
}

.admin-sidebar.is-collapsed {
  width: var(--sidebar-w-collapsed);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: var(--topbar-h);
  padding: 0 14px;
  flex-shrink: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.admin-sidebar.is-collapsed .brand {
  justify-content: center;
  padding: 0;
}

.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  font-size: 15px;
  font-weight: 700;
  color: #fff;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--brand-400) 0%, var(--brand-600) 100%);
  box-shadow: 0 4px 12px rgba(92, 107, 224, 0.35);
}

.brand-text {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  white-space: nowrap;
}

.brand-name {
  font-size: 15px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 0.5px;
}

.brand-sub {
  font-size: var(--fs-xs);
  color: #7c869b;
}

.sidebar-scroll {
  flex: 1;
  min-height: 0;
}

.sidebar-menu {
  padding: 8px 10px;
  border-right: none;
}

.menu-group-label {
  padding: 14px 12px 6px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 1px;
  color: #5f6a80;
  text-transform: uppercase;
}

.sidebar-menu :deep(.el-menu-item) {
  height: 42px;
  margin-bottom: 4px;
  border-radius: var(--radius-md);
  font-size: var(--fs-body);
  transition:
    background-color var(--dur-fast) var(--ease-out),
    color var(--dur-fast) var(--ease-out);
}

.sidebar-menu :deep(.el-menu-item:hover) {
  background-color: var(--bg-sidebar-hover);
  color: #dfe4ee;
}

.sidebar-menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(90deg, var(--brand-500) 0%, var(--brand-400) 100%);
  box-shadow: 0 4px 14px rgba(75, 91, 214, 0.32);
}

.sidebar-menu :deep(.el-menu-item.is-active .el-icon) {
  color: #fff;
}

.sidebar-menu :deep(.el-menu-item .el-icon) {
  font-size: 17px;
  color: inherit;
}

.sidebar-menu.is-collapsed :deep(.el-menu-item) {
  justify-content: center;
  padding: 0;
}

.sidebar-foot {
  padding: 12px 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.foot-text {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: #5f6a80;
  white-space: nowrap;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--c-success);
  box-shadow: 0 0 0 3px rgba(47, 163, 107, 0.18);
}
</style>
