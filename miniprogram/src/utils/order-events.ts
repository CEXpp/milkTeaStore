import { BASE_URL } from './config'
import { getToken } from './request'

/**
 * 顾客订单实时事件订阅层（T43，LLD 11.1）。
 *
 * - 通道：SSE `GET /api/customer/orders/events?orderId={id}`（顾客 JWT）。
 *   小程序无原生 EventSource，且 `uni.request` 的常规响应不支持流式读取，故用
 *   `uni.request({ enableChunked: true })` + 请求任务上的 `onChunkReceived` 逐块读取，
 *   自行解析 SSE 帧（LLD 11.1 已明确授权此方案）。
 * - 令牌：同时带上 `Authorization` 头与 `token` 查询参数——流式通道在部分宿主上头部可能不生效，
 *   查询参数是后端（JwtAuthenticationFilter）为 EventSource 类客户端保留的回落口径。
 * - 降级：任何一处不支持或中断（宿主无 `onChunkReceived`、连接失败、超过 2 个心跳周期无数据）
 *   都回调 `onFallback`，由调用方回落到既有的 3 秒轮询（LLD 11.1「降级回落轮询」）。
 *
 * 注：`timeout` 显式置 0（不设超时）——该请求是长连接，不能套用 15 秒的常规请求超时；
 * 若某宿主仍按自身策略关闭长连接，`complete` 会触发降级回落轮询，功能不中断。
 */

/** 后端事件体（LLD 11.1 事件结构） */
export interface OrderStatusEvent {
  type: string
  orderId: number | null
  status: string | null
  pickupCode: string | null
  /** 事件生成时刻（Unix 秒） */
  ts: number
}

export interface OrderEventHandlers {
  /** 收到业务事件（含连接建立时补发的当前状态快照） */
  onEvent: (event: OrderStatusEvent) => void
  /** 判定通道不可用：调用方应回落到 3 秒轮询 */
  onFallback: () => void
}

export interface OrderEventSubscription {
  close(): void
}

/** 心跳周期 15s（LLD 11.1）；连续 2 个周期无任何数据（含心跳注释）即判定失效 */
const IDLE_TIMEOUT_MS = 30_000

/** `enableChunked` 场景下请求任务额外提供 `onChunkReceived`（uni-app 类型未覆盖，故显式声明） */
type ChunkedRequestTask = UniNamespace.RequestTask & {
  onChunkReceived?: (callback: (res: { data: ArrayBuffer }) => void) => void
}

/**
 * 订阅指定订单的实时状态事件。
 *
 * @returns 订阅句柄；宿主不支持流式响应时返回 `null`，调用方应立即回落轮询
 */
export function subscribeOrderEvents(
  orderId: number,
  handlers: OrderEventHandlers
): OrderEventSubscription | null {
  const token = getToken()
  if (!token) {
    return null
  }

  let closed = false
  /** 已触发过降级（保证 onFallback 只回调一次） */
  let fellBack = false
  let buffer = ''
  let eventName = ''
  let dataLines: string[] = []
  let watchdog: number | null = null

  const task = uni.request({
    url: `${BASE_URL}/api/customer/orders/events?orderId=${orderId}&token=${encodeURIComponent(token)}`,
    method: 'GET',
    header: { Authorization: `Bearer ${token}` },
    enableChunked: true,
    responseType: 'arraybuffer',
    timeout: 0,
    fail: () => fallback(),
    complete: () => fallback()
  }) as ChunkedRequestTask

  if (typeof task.onChunkReceived !== 'function') {
    // 宿主不支持分块响应（如 H5 端）：中止本次请求，交由调用方回落轮询
    closed = true
    abort()
    return null
  }

  function stopWatchdog(): void {
    if (watchdog !== null) {
      clearTimeout(watchdog)
      watchdog = null
    }
  }

  /** 中止流式请求；请求尚未返回句柄或已结束时静默忽略 */
  function abort(): void {
    try {
      task.abort()
    } catch {
      // 请求已结束 / 句柄未就绪：无需处理
    }
  }

  /** 看门狗：每收到一块数据即重置；超时未收到任何数据（含心跳注释）判定连接失效 */
  function touch(): void {
    stopWatchdog()
    watchdog = setTimeout(() => fallback(), IDLE_TIMEOUT_MS) as unknown as number
  }

  /** 降级：中止流式请求并通知调用方回落轮询（只触发一次） */
  function fallback(): void {
    if (closed || fellBack) return
    fellBack = true
    stopWatchdog()
    abort()
    handlers.onFallback()
  }

  /** 组帧完成：把累积的 data 行按 JSON 解析后回调（脏帧忽略，不断流） */
  function dispatch(): void {
    if (dataLines.length === 0) {
      eventName = ''
      return
    }
    const payload = dataLines.join('\n')
    dataLines = []
    eventName = ''
    try {
      const event = JSON.parse(payload) as OrderStatusEvent
      if (event && typeof event.type === 'string') {
        handlers.onEvent(event)
      }
    } catch {
      // 忽略无法解析的帧
    }
  }

  function handleLine(line: string): void {
    // 空行为帧结束
    if (line === '') {
      dispatch()
      return
    }
    // 以 ':' 开头为注释行（心跳 `:ping`）：只用于重置看门狗
    if (line.startsWith(':')) {
      touch()
      return
    }
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    let value = colon < 0 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }
    if (field === 'event') {
      eventName = value
    } else if (field === 'data') {
      dataLines.push(value)
    }
  }

  /**
   * 逐字节转字符：SSE 事件体按契约只含 ASCII（事件名 / 状态枚举名 / 取餐码 / 数字时间戳 / 订单号），
   * 不存在多字节字符被分片截断的问题，故无需 UTF-8 解码器。
   */
  function decode(chunk: ArrayBuffer): string {
    const bytes = new Uint8Array(chunk)
    let text = ''
    for (let i = 0; i < bytes.length; i += 1) {
      text += String.fromCharCode(bytes[i])
    }
    return text
  }

  function feed(text: string): void {
    buffer += text
    let index = buffer.indexOf('\n')
    while (index >= 0) {
      const rawLine = buffer.slice(0, index)
      buffer = buffer.slice(index + 1)
      handleLine(rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine)
      index = buffer.indexOf('\n')
    }
  }

  task.onChunkReceived((res) => {
    if (closed || fellBack) return
    touch()
    feed(decode(res.data))
  })
  touch()

  return {
    close(): void {
      closed = true
      stopWatchdog()
      abort()
    }
  }
}
