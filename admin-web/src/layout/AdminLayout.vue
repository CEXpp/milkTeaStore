<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AdminSidebar from './AdminSidebar.vue'
import AdminTopbar from './AdminTopbar.vue'

/**
 * 后台统一外壳：深蓝灰侧栏 + 顶栏 + 内容区。
 * - 内容区统一承载留白与滚动，业务页不再各自声明 padding / 100vh；
 * - meta.fullHeight 的页面（柜台点单）走整屏布局，由页面内部控制内部滚动；
 * - meta.keepAlive 的页面进入 keep-alive 缓存，切换时不重建（看板轮询由 onActivated 自行恢复）。
 */
const route = useRoute()

const collapsed = ref(false)
const fullHeight = computed(() => Boolean(route.meta.fullHeight))

let media: MediaQueryList | null = null

function applyBreakpoint(matches: boolean): void {
  collapsed.value = matches
}

function handleMediaChange(event: MediaQueryListEvent): void {
  applyBreakpoint(event.matches)
}

onMounted(() => {
  media = window.matchMedia('(max-width: 1100px)')
  applyBreakpoint(media.matches)
  media.addEventListener('change', handleMediaChange)
})

onBeforeUnmount(() => {
  media?.removeEventListener('change', handleMediaChange)
  media = null
})
</script>

<template>
  <div class="admin-layout">
    <AdminSidebar v-model:collapsed="collapsed" />

    <div class="layout-main">
      <AdminTopbar v-model:collapsed="collapsed" />

      <main class="layout-content" :class="{ 'is-full': fullHeight }">
        <div class="route-view">
          <router-view v-slot="{ Component, route: current }">
            <transition name="route-fade">
              <keep-alive v-if="current.meta.keepAlive">
                <component :is="Component" :key="current.path" />
              </keep-alive>
              <component v-else :is="Component" :key="current.path" />
            </transition>
          </router-view>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.admin-layout {
  display: flex;
  height: 100%;
  overflow: hidden;
  background: var(--bg-app);
}

.layout-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  height: 100%;
}

.layout-content {
  flex: 1;
  min-height: 0;
  padding: var(--content-pad);
  overflow-y: auto;
  overflow-x: hidden;
}

/* 整屏页面（柜台点单）：内容区不留白、不滚动，交给页面内部两栏各自滚动 */
.layout-content.is-full {
  padding: 0;
  overflow: hidden;
}

.route-view {
  position: relative;
  min-height: 100%;
}

.layout-content.is-full .route-view {
  height: 100%;
}
</style>
