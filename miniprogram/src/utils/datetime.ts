/**
 * 时间格式工具。后端时间契约统一为 {@code yyyy-MM-dd HH:mm:ss}（LLD 3.1）。
 */

const DATE_TIME_RE = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})$/
const COMPACT_RE = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})$/

/**
 * {@code yyyy-MM-dd HH:mm:ss} → {@code yyyyMMddHHmmss}（14 位纯数字）。
 *
 * <p>用于把时间透传过页面参数：空格 / 冒号在 URL 中的转义是平台相关的——
 * H5 路由会把空格转成 {@code +}（form-urlencoded 语义），而 {@code decodeURIComponent}
 * 不会把 {@code +} 还原成空格，小程序端又不做 form-urlencoding，属于典型踩坑点。
 * 纯数字不受任何编解码层数影响，回填后再格式化即可。格式不匹配返回空串。</p>
 */
export function toCompactDateTime(value: string | null | undefined): string {
  if (!value) return ''
  const matched = DATE_TIME_RE.exec(value.trim())
  if (!matched) return ''
  return `${matched[1]}${matched[2]}${matched[3]}${matched[4]}${matched[5]}${matched[6]}`
}

/** {@code yyyyMMddHHmmss} → {@code yyyy-MM-dd HH:mm:ss}；非法输入返回空串（调用方隐藏展示） */
export function fromCompactDateTime(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return ''
  const matched = COMPACT_RE.exec(String(value).trim())
  if (!matched) return ''
  return `${matched[1]}-${matched[2]}-${matched[3]} ${matched[4]}:${matched[5]}:${matched[6]}`
}
