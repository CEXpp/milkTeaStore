/**
 * 商家端鉴权信息持久化（localStorage）。
 * request 层（自动携带 / 401 清除）与 authStore 共用此模块，避免两者直接互相依赖。
 */
const TOKEN_KEY = 'milktea_admin_token'
const NICKNAME_KEY = 'milktea_admin_nickname'

/** 读取商家 JWT；未登录返回空串 */
export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

/** 读取商家昵称 */
export function getNickname(): string {
  return localStorage.getItem(NICKNAME_KEY) ?? ''
}

/** 登录成功后持久化 token 与昵称（刷新不掉线） */
export function saveAuth(token: string, nickname: string): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(NICKNAME_KEY, nickname)
}

/** 清除鉴权信息（登出 / token 失效） */
export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(NICKNAME_KEY)
}
