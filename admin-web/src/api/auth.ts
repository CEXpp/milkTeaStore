import { request } from '@/utils/request'

/** POST /api/admin/login 请求体 */
export interface AdminLoginRequest {
  username: string
  password: string
}

/** POST /api/admin/login 响应 data（JWT 有效期 12h，LLD 3.5） */
export interface AdminLoginResult {
  token: string
  nickname: string
}

/**
 * 商家登录（公开端点，无需 token）。
 * LLD 3.5：POST /api/admin/login
 */
export function login(data: AdminLoginRequest): Promise<AdminLoginResult> {
  return request<AdminLoginResult>({ url: '/admin/login', method: 'post', data })
}
