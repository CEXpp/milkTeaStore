<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ username: '', password: '' })

const rules: FormRules<typeof form> = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin(): Promise<void> {
  const valid = formRef.value ? await formRef.value.validate().catch(() => false) : false
  if (!valid) return

  submitting.value = true
  try {
    await authStore.login({ username: form.username, password: form.password })
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/board'
    await router.replace(redirect)
  } catch {
    // 失败提示由 request 层统一直显（LLD 7.1），此处仅终止流程
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <!-- 背景层：靛蓝 → 深蓝灰斜向渐变 + 极淡网格 + 底部柔光 -->
    <div class="login-bg" aria-hidden="true">
      <span class="bg-grid" />
      <span class="bg-glow" />
    </div>

    <div class="login-card">
      <div class="brand">
        <span class="brand-mark">奶</span>
        <div class="brand-text">
          <h1 class="brand-title">奶茶店 · 商家后台</h1>
          <p class="brand-sub">接单、收银、对账，一屏掌控</p>
        </div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleLogin">
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="账号"
            autocomplete="username"
            :prefix-icon="User"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            show-password
            autocomplete="current-password"
            :prefix-icon="Lock"
          />
        </el-form-item>
        <el-button class="login-submit" type="primary" size="large" :loading="submitting" @click="handleLogin">
          {{ submitting ? '登录中…' : '登 录' }}
        </el-button>
      </el-form>

      <div class="login-tip">开发环境默认账号：admin / admin123（见后端 application-dev.yml）</div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: var(--gap-6);
  overflow: hidden;
  box-sizing: border-box;
}

.login-bg {
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, #2b3550 0%, #1b2233 55%, #151b28 100%);
}

/* 极淡网格纹理 */
.bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.04) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(circle at 50% 40%, rgba(0, 0, 0, 0.9), transparent 72%);
}

/* 底部靛蓝柔光 */
.bg-glow {
  position: absolute;
  left: 50%;
  bottom: -220px;
  width: 720px;
  height: 460px;
  transform: translateX(-50%);
  background: radial-gradient(ellipse at center, rgba(92, 107, 224, 0.42) 0%, rgba(92, 107, 224, 0) 68%);
  filter: blur(6px);
}

.login-card {
  position: relative;
  width: 380px;
  max-width: 100%;
  padding: 32px 30px 24px;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(14px);
  border: 1px solid rgba(255, 255, 255, 0.6);
  border-radius: var(--radius-xl);
  box-shadow: 0 24px 64px rgba(8, 12, 24, 0.42);
  animation: card-up var(--dur-base) var(--ease-out);
}

@keyframes card-up {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--gap-3);
  margin-bottom: var(--gap-6);
}

.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  border-radius: var(--radius-md);
  background: linear-gradient(135deg, var(--brand-400) 0%, var(--brand-600) 100%);
  box-shadow: 0 8px 20px rgba(75, 91, 214, 0.32);
}

.brand-title {
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.3px;
  color: var(--text-1);
}

.brand-sub {
  margin-top: 3px;
  font-size: var(--fs-xs);
  color: var(--text-3);
}

.login-submit {
  width: 100%;
  height: 44px;
  margin-top: var(--gap-2);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 2px;
}

.login-tip {
  margin-top: var(--gap-4);
  font-size: var(--fs-xs);
  color: var(--text-3);
  text-align: center;
}

.login-card :deep(.el-form-item) {
  margin-bottom: var(--gap-4);
}

.login-card :deep(.el-input__wrapper) {
  height: 44px;
  border-radius: var(--radius-md);
}

@media (max-width: 480px) {
  .login-card {
    padding: 26px 20px 18px;
  }
}
</style>
