import axios, { type AxiosError, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import { clearAuth, getToken } from '@/utils/token'

/** LLD 3.1 统一响应体：code=0 成功；非 0 为业务错误码（见 LLD 3.2） */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}

/** LLD 3.1 分页响应固定结构 */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

/** LLD 3.1 分页请求参数：page 从 1 起，size 默认 20、最大 100 */
export interface PageQuery {
  page?: number
  size?: number
}

/** 业务错误（body.code ≠ 0）：调用方可用 code 分支（如 1004 状态冲突、1008 AI 不可用） */
export class ApiError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

/**
 * axios 实例：baseURL 恒为相对路径 /api。
 * 开发期由 vite proxy 转发到后端（vite.config.ts），演示期前后端同源——两形态零代码差异（LLD 7.1）。
 */
const service = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截：自动携带 Authorization: Bearer <JWT>（LLD 3.1）
service.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/** 401（HTTP 状态码或 body.code）：清本地鉴权信息并跳登录页（/login 免守卫） */
function handleUnauthorized(message: string): void {
  clearAuth()
  if (window.location.pathname !== '/login') {
    ElMessage.error(message)
    window.location.href = '/login'
  }
}

// 响应拦截：统一拆包 + 错误直显（LLD 3.1；401/403 由后端以真实 HTTP 状态码承载，见 JwtAuthenticationFilter）
service.interceptors.response.use(
  (response: AxiosResponse<ApiResult>) => {
    const body = response.data
    if (!body || typeof body.code !== 'number') {
      // 理论上不应出现：后端所有接口均返回统一响应体
      ElMessage.error('响应格式异常')
      return Promise.reject(new Error('响应格式异常：缺少统一响应体'))
    }
    if (body.code === 0) {
      // 拆包：直接把 data 交给调用方（返回类型由 request<T> 声明）
      return body.data as never
    }
    if (body.code === 401) {
      handleUnauthorized(body.message || '登录已过期，请重新登录')
    } else {
      ElMessage.error(body.message || '请求失败')
    }
    return Promise.reject(new ApiError(body.code, body.message))
  },
  (error: AxiosError<ApiResult>) => {
    const status = error.response?.status
    const message = error.response?.data?.message
    if (status === 401) {
      handleUnauthorized(message || '登录已过期，请重新登录')
    } else if (status === 403) {
      ElMessage.error(message || '无权限访问')
    } else {
      ElMessage.error(message || error.message || '网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

/**
 * 统一请求入口：resolve 得到已拆包的业务数据 data；失败一律 reject（ApiError / AxiosError）。
 * 错误 message 已由拦截器直显，页面只需按需做后续动作（如 401 跳转、1004 后刷新看板）。
 */
export function request<T = unknown>(config: AxiosRequestConfig): Promise<T> {
  // 响应拦截器已把 {code,message,data} 拆包为 data；
  // axios 的 R 泛型是延迟条件类型（AxiosResponseResult），无法在本泛型函数内推导，故此处显式断言。
  return service.request(config) as unknown as Promise<T>
}

export default service
