import { BASE_URL } from '@/utils/config'
import { ApiError, getToken, login, request, type ApiResult } from '@/utils/request'

/**
 * 顾客资料与口味档案接口（个人中心）。
 *
 * - `GET  /api/customer/profile`：读昵称与头像
 * - `PUT  /api/customer/profile`：改昵称
 * - `POST /api/customer/profile/avatar`：上传头像（multipart）
 * - `GET  /api/customer/preference`：口味档案（读的时候服务端可能顺带补算一次）
 */

/** 顾客资料（`avatarUrl` 为相对代理路径，展示时按 BASE_URL 拼绝对地址） */
export interface CustomerProfile {
  customerId: number
  nickname: string | null
  avatarKey: string | null
  avatarUrl: string | null
}

/** 单个规格组的偏好 */
export interface PreferenceGroup {
  code: string
  name: string
  optionNames: string[]
}

/** 口味档案（`available=false` 时走空态：新客会看到引导，而不是一排空标签） */
export interface PreferenceView {
  available: boolean
  sampleOrders: number
  favoriteProductName: string | null
  groups: PreferenceGroup[]
}

/** 读当前顾客资料。 */
export function getProfile(): Promise<CustomerProfile> {
  return request<CustomerProfile>({ url: '/api/customer/profile', auth: true })
}

/** 更新昵称（服务端会去首尾空白并做长度校验）。 */
export function updateNickname(nickname: string): Promise<CustomerProfile> {
  return request<CustomerProfile>({
    url: '/api/customer/profile',
    method: 'PUT',
    data: { nickname },
    auth: true
  })
}

/** 读口味档案（服务端在过期时会顺带补算一次，故这个 GET 可能有轻微耗时）。 */
export function getPreference(): Promise<PreferenceView> {
  return request<PreferenceView>({ url: '/api/customer/preference', auth: true })
}

/**
 * 上传头像。
 *
 * <p><b>必须上传文件，不能提交地址</b>：`chooseAvatar` 给的是本机临时路径
 * （`http://tmp/…` / `wxfile://…`），只在当次运行有效；服务端把它转存为对象 key
 * 之后，换台设备打开还能看到。服务端也会拒绝任何看起来像 URL 的 key。</p>
 *
 * <p>这里自己拼 multipart 请求而不复用 {@link request}：`uni.request` 不能传文件，
 * 必须用 `uni.uploadFile`。也因此要在上传前确保已登录（未登录时不会自动带上令牌）。</p>
 *
 * @param filePath `chooseAvatar` 返回的本地临时路径
 */
export async function uploadAvatar(filePath: string): Promise<CustomerProfile> {
  const token = getToken() || (await login())
  return new Promise<CustomerProfile>((resolve, reject) => {
    uni.uploadFile({
      url: BASE_URL + '/api/customer/profile/avatar',
      filePath,
      name: 'file',
      header: { Authorization: `Bearer ${token}` },
      success: (res) => {
        let body: ApiResult<CustomerProfile> | null = null
        try {
          body = JSON.parse(res.data) as ApiResult<CustomerProfile>
        } catch {
          reject(new Error('响应格式异常'))
          return
        }
        if (res.statusCode === 401 || body?.code === 401) {
          reject(new ApiError(401, body?.message || '登录已过期，请重新进入小程序'))
          return
        }
        if (!body || body.code !== 0) {
          reject(new ApiError(body?.code ?? -1, body?.message || '头像上传失败', body?.data))
          return
        }
        resolve(body.data)
      },
      fail: (err) => reject(new Error(err.errMsg || '头像上传失败，请稍后重试'))
    })
  })
}
