import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { login as loginApi, type AdminLoginRequest } from '@/api/auth'
import { clearAuth, getNickname, getToken, saveAuth } from '@/utils/token'

/**
 * 商家登录态（LLD 7.3）：token + nickname，localStorage 持久化（刷新不掉线）。
 * 仅承载登录/登出与身份信息；页面数据一律走局部 state，不提前抽象。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken())
  const nickname = ref(getNickname())
  const isLoggedIn = computed(() => Boolean(token.value))

  /** 账密登录：POST /api/admin/login，成功后持久化 token/nickname */
  async function login(payload: AdminLoginRequest) {
    const data = await loginApi(payload)
    token.value = data.token
    nickname.value = data.nickname
    saveAuth(data.token, data.nickname)
    return data
  }

  /** 退出登录：后端无 logout 端点，清本地鉴权信息即可 */
  function logout(): void {
    clearAuth()
    token.value = ''
    nickname.value = ''
  }

  return { token, nickname, isLoggedIn, login, logout }
})
