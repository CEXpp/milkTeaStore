<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Expand, Fold, SwitchButton } from '@element-plus/icons-vue'
import { findNavItem } from '@/config/nav'
import { useShopStatus } from '@/composables/useShopStatus'
import { useAuthStore } from '@/stores/auth'

/**
 * 顶栏（业务页通用）：折叠开关 + 当前页面标题 + 门店营业状态（暂停接单）+ 用户与退出。
 * 营业状态与退出登录从 Board.vue 上提到此处，业务页不再重复实现（行为保持一致）。
 */
const props = defineProps<{
  collapsed: boolean
}>()

const emit = defineEmits<{
  (e: 'update:collapsed', value: boolean): void
}>()

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const { paused, notice, busy, ensureLoaded, setPaused, reset } = useShopStatus()

const current = computed(() => findNavItem(route.path))
const title = computed(() => (route.meta.title as string | undefined) ?? current.value?.title ?? '')
const desc = computed(() => (route.meta.desc as string | undefined) ?? current.value?.desc ?? '')
const initial = computed(() => (authStore.nickname || '店').slice(0, 1))

onMounted(() => void ensureLoaded())

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
  } catch {
    return
  }
  authStore.logout()
  // 清空门店状态缓存，避免下一账号沿用上一账号的暂停接单状态
  reset()
  ElMessage.success('已退出登录')
  await router.replace('/login')
}
</script>

<template>
  <header class="admin-topbar">
    <div class="topbar-left">
      <button
        type="button"
        class="icon-btn"
        :title="props.collapsed ? '展开菜单' : '收起菜单'"
        @click="emit('update:collapsed', !props.collapsed)"
      >
        <el-icon :size="18">
          <component :is="props.collapsed ? Expand : Fold" />
        </el-icon>
      </button>

      <div class="page-heading">
        <h1 class="page-title">{{ title }}</h1>
        <span v-if="desc" class="page-desc">{{ desc }}</span>
      </div>
    </div>

    <div class="topbar-right">
      <div class="shop-status" :class="{ paused }">
        <span class="status-text">{{ paused ? '暂停接单中' : '正常接单' }}</span>
        <el-switch v-model="paused" :loading="busy" :disabled="busy" @change="setPaused" />
      </div>

      <span class="divider" />

      <div class="user">
        <span class="avatar">{{ initial }}</span>
        <span class="nickname">{{ authStore.nickname || '店长' }}</span>
      </div>

      <button type="button" class="icon-btn danger" title="退出登录" @click="handleLogout">
        <el-icon :size="18"><SwitchButton /></el-icon>
      </button>
    </div>

    <!-- 暂停接单时给一次明确告知（顾客端提示语同步展示，语义与原看板横幅一致） -->
    <el-tooltip v-if="paused" :content="notice ?? '顾客端暂不可下单'" placement="bottom" effect="dark">
      <span class="pause-flag">已暂停接单</span>
    </el-tooltip>
  </header>
</template>

<style scoped>
.admin-topbar {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap-4);
  height: var(--topbar-h);
  padding: 0 var(--gap-5);
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--border);
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: var(--gap-3);
  min-width: 0;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: var(--gap-3);
}

.icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  color: var(--text-2);
  background: transparent;
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition:
    background-color var(--dur-fast) var(--ease-out),
    color var(--dur-fast) var(--ease-out);
}

.icon-btn:hover {
  color: var(--brand-500);
  background: var(--brand-050);
}

.icon-btn.danger:hover {
  color: var(--c-danger);
  background: var(--c-danger-soft);
}

.page-heading {
  display: flex;
  align-items: baseline;
  gap: var(--gap-2);
  min-width: 0;
}

.page-title {
  font-size: var(--fs-h1);
  font-weight: 600;
  letter-spacing: 0.2px;
  color: var(--text-1);
  white-space: nowrap;
}

.page-desc {
  font-size: var(--fs-xs);
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.shop-status {
  display: flex;
  align-items: center;
  gap: var(--gap-2);
  padding: 5px 10px 5px 14px;
  border-radius: var(--radius-pill);
  background: var(--c-success-soft);
  border: 1px solid rgba(47, 163, 107, 0.22);
  transition: background-color var(--dur-base) var(--ease-out);
}

.shop-status.paused {
  background: var(--c-warning-soft);
  border-color: rgba(224, 163, 60, 0.28);
}

.status-text {
  font-size: var(--fs-sm);
  font-weight: 500;
  color: var(--c-success);
}

.shop-status.paused .status-text {
  color: #b88230;
}

.divider {
  width: 1px;
  height: 22px;
  background: var(--border);
}

.user {
  display: flex;
  align-items: center;
  gap: var(--gap-2);
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: #fff;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand-400) 0%, var(--brand-600) 100%);
}

.nickname {
  font-size: var(--fs-sm);
  color: var(--text-2);
  white-space: nowrap;
}

.pause-flag {
  position: absolute;
  left: 50%;
  bottom: -11px;
  transform: translateX(-50%);
  padding: 2px 12px;
  font-size: 11px;
  font-weight: 500;
  color: #b88230;
  white-space: nowrap;
  background: var(--c-warning-soft);
  border: 1px solid rgba(224, 163, 60, 0.3);
  border-radius: var(--radius-pill);
  box-shadow: var(--shadow-xs);
}

@media (max-width: 900px) {
  .page-desc,
  .nickname {
    display: none;
  }
}
</style>
