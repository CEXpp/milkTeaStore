/**
 * 小程序端构建常量（LLD 8.3：请求基地址为构建常量，不散落在业务代码里）。
 *
 * BASE_URL 取值指引：
 * - 微信开发者工具模拟器（与后端同机）：http://127.0.0.1:8080
 * - 真机联调（T28）：改成开发机局域网 IP，如 http://192.168.1.10:8080（手机与开发机同一 WiFi）
 * - 演示期（T36）：改成 cpolar 域名（https）
 *
 * 注意：小程序真机只允许 https + 已配置的合法域名；练手期联调必须二选一：
 * ① 微信开发者工具「详情 → 本地设置 → 不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」勾选；
 * ② 仓库 `project.config.json` 的 `setting.urlCheck` 为 `false`（已设置，见 T33 fix）。
 * 该开关是初学者最容易踩的坑：urlCheck 为 true 时，http:// 请求会被开发者工具直接拦截，
 * 表现为「登录失败 / 请求发不出去」，而后端日志里什么都看不到。
 */
export const BASE_URL = 'http://127.0.0.1:8080'

/**
 * 登录通道开关（LLD 8.3：wx.login → jscode2session → JWT）。
 *
 * 练手期后端未配置微信 AppSecret（application-dev.yml 的 wx.secret 为空），
 * 走后端白名单里的 dev-login 联调通道；配置好 AppSecret 后置为 false，
 * 即切换为真实的 uni.login → POST /api/customer/login，调用方代码不变。
 */
export const USE_DEV_LOGIN = true

/** 请求超时（毫秒） */
export const REQUEST_TIMEOUT = 15000
