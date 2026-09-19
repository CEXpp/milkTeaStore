import { BASE_URL, REQUEST_TIMEOUT, USE_DEV_LOGIN } from './config'

/**
 * 请求层（T24，LLD 8.3）：
 * - uni.request promisify，统一拆 {code, message, data}（LLD 3.1）；
 * - 自动携带 Authorization: Bearer <JWT>；401 时静默重登一次并重试原请求；
 * - 业务错误码（code ≠ 0）直显 message 并 reject ApiError，调用方可按 code 分支
 *   （如 1006 暂停接单 / 1002 商品变动 → 引导回菜单刷新）。
 */

/** 统一响应体（LLD 3.1） */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}

/** 分页响应固定结构（LLD 3.1） */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

/** 业务错误（body.code ≠ 0 或 HTTP 401） */
export class ApiError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

/** 错误码：未登录 / 令牌过期（LLD 3.2） */
export const CODE_UNAUTHORIZED = 401
/** 错误码：店铺暂停接单（LLD 3.2） */
export const CODE_SHOP_PAUSED = 1006
/** 错误码：商品不存在或已下架（LLD 3.2） */
export const CODE_PRODUCT_UNAVAILABLE = 1002

const TOKEN_KEY = 'customer_token'
const DEV_OPENID_KEY = 'customer_dev_openid'

export function getToken(): string {
  return uni.getStorageSync(TOKEN_KEY) || ''
}

export function setToken(token: string): void {
  uni.setStorageSync(TOKEN_KEY, token)
}

export function clearToken(): void {
  uni.removeStorageSync(TOKEN_KEY)
}

/** 请求参数 */
export interface RequestOptions {
  /** 相对路径，如 /api/customer/menu */
  url: string
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: Record<string, unknown>
  /** true = 必须登录：无 token 时先静默登录（下单 / 订单类接口用） */
  auth?: boolean
}

interface RawResponse {
  statusCode: number
  body: ApiResult | null
}

/**
 * 练手期 dev-login 的 openid：本地持久化，保证同一设备始终是同一个顾客身份。
 */
function devOpenid(): string {
  let openid = uni.getStorageSync(DEV_OPENID_KEY)
  if (!openid) {
    openid = `dev_${Math.random().toString(36).slice(2, 10)}`
    uni.setStorageSync(DEV_OPENID_KEY, openid)
  }
  return openid
}

/** wx.login 取一次性 code（真实通道） */
function wxLoginCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success: (res) => (res.code ? resolve(res.code) : reject(new Error('wx.login 未返回 code'))),
      fail: (err) => reject(new Error(err.errMsg || 'wx.login 失败'))
    })
  })
}

/** 底层请求：单次发出、不含重登逻辑（登录接口自身也复用它，避免递归） */
function raw(options: RequestOptions, token: string): Promise<RawResponse> {
  return new Promise((resolve, reject) => {
    const header: Record<string, string> = { 'Content-Type': 'application/json' }
    if (token) {
      header.Authorization = `Bearer ${token}`
    }
    uni.request({
      url: BASE_URL + options.url,
      method: options.method || 'GET',
      data: options.data,
      header,
      timeout: REQUEST_TIMEOUT,
      success: (res) =>
        resolve({ statusCode: res.statusCode, body: (res.data as unknown as ApiResult) ?? null }),
      fail: (err) => reject(new Error(err.errMsg || '网络异常，请稍后重试'))
    })
  })
}

/** 并发去重：同一时刻只发一次登录请求 */
let loginInFlight: Promise<string> | null = null

/**
 * 登录换取顾客 JWT（LLD 3.3）：启动时调用一次，401 时静默重登。
 */
export function login(): Promise<string> {
  if (loginInFlight) {
    return loginInFlight
  }
  loginInFlight = doLogin().finally(() => {
    loginInFlight = null
  })
  return loginInFlight
}

async function doLogin(): Promise<string> {
  const res = USE_DEV_LOGIN
    ? await raw({ url: `/api/customer/dev-login?openid=${encodeURIComponent(devOpenid())}` }, '')
    : await raw({ url: '/api/customer/login', method: 'POST', data: { code: await wxLoginCode() } }, '')

  const body = res.body
  if (res.statusCode !== 200 || !body || body.code !== 0) {
    throw new ApiError(body?.code ?? -1, body?.message || '登录失败，请重试')
  }
  const token = (body.data as { token: string }).token
  setToken(token)
  return token
}

/** 判断是否为「未登录 / 令牌过期」（HTTP 401 或 body.code=401） */
function isUnauthorized(res: RawResponse): boolean {
  return res.statusCode === 401 || res.body?.code === CODE_UNAUTHORIZED
}

/** 业务错误统一提示（登录失败类错误由调用方自行处理提示） */
function toast(message: string): void {
  uni.showToast({ title: message, icon: 'none' })
}

/**
 * 统一请求入口：resolve 已拆包的 data；失败一律 reject ApiError / Error。
 * 401 处理：清 token → 静默重登 → 重试原请求一次（LLD 8.3）。
 */
export function request<T>(options: RequestOptions): Promise<T> {
  return send<T>(options, false)
}

async function send<T>(options: RequestOptions, retried: boolean): Promise<T> {
  let token = getToken()
  if (!token && options.auth) {
    token = await login()
  }

  let res = await raw(options, token)

  if (isUnauthorized(res) && !retried) {
    clearToken()
    const fresh = await login()
    res = await raw(options, fresh)
  }

  if (isUnauthorized(res)) {
    clearToken()
    const message = res.body?.message || '登录已过期，请重新登录'
    toast(message)
    throw new ApiError(CODE_UNAUTHORIZED, message)
  }

  const body = res.body
  if (!body || typeof body.code !== 'number') {
    toast('响应格式异常')
    throw new ApiError(-1, '响应格式异常：缺少统一响应体')
  }
  if (body.code === 0) {
    return body.data as T
  }

  const message = body.message || '请求失败'
  toast(message)
  throw new ApiError(body.code, message)
}
