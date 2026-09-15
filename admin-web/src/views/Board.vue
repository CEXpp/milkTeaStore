<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
  } catch {
    // 用户取消
    return
  }
  authStore.logout()
  await router.replace('/login')
}
</script>

<template>
  <div class="board-page">
    <el-card shadow="never">
      <template #header>
        <div class="board-header">
          <span class="board-title">订单看板</span>
          <span>
            <span class="board-user">{{ authStore.nickname || '店长' }}</span>
            <el-button link type="primary" @click="handleLogout">退出登录</el-button>
          </span>
        </div>
      </template>

      <el-empty description="T17 仅交付工程与请求层；看板双分区、3 秒轮询与行内操作按钮由后续任务实现" />
    </el-card>
  </div>
</template>

<style scoped>
.board-page {
  padding: 16px;
}

.board-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.board-title {
  font-weight: 600;
}

.board-user {
  margin-right: 12px;
  color: #606266;
}
</style>
