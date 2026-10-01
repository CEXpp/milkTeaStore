import { getToken } from '@/utils/token'

/**
 * 看板实时事件订阅层（T43，LLD 11.1）。
 *
 * - 通道：SSE `GET /api/admin/board/events`（商家 JWT）；浏览器原生 EventSource 无法设置请求头，
 *   故令牌经查询参数 `token` 携带（后端 JwtAuthenticationFilter 已支持该回落）。
 * - 语义：SSE 是 3 秒全量轮询的**增强通道**，二者互斥——本模块可用则只走 SSE，
 *   否则由调用方回落 3 秒轮询（`onFallback`）。
 * - 事件：后端每有订单状态迁移即推一条 `event: ORDER_STATUS_CHANGED | PICKUP_READY`，
 *   调用方收到任意事件刷新一次看板即可（复用既有的新单差集判定）。
 *
 * 降级触发（LLD 11.1 触发降级）：① 建连超时未 open；② 连接被关闭（readyState=CLOSED）；
 * ③ 连续失败超过 MAX_RETRY 次。任一命中即关闭通道并回调 `onFallback`，不再自行重连
 * （避免与调用方的轮询通道同时存活）。
 */

/** 后端事件体（LLD 11.1 事件结构） */
export interface OrderStatusEvent {
  type: 'ORDER_STATUS_CHANGED' | 'PICKUP_READY'
  orderId: number | null
  status: string | null
  pickupCode: string | null
  /** 事件生成时刻（Unix 秒） */
  ts: number
}

export interface BoardEventHandlers {
  /** 收到业务事件 */
  onEvent: (event: OrderStatusEvent) => void
  /** 判定 SSE 不可用：调用方应回落到 3 秒轮询 */
  onFallback: () => void
}

export interface BoardEventSubscription {
  close(): void
}

const EVENT_PATH = '/api/admin/board/events'
/** 建连超时（毫秒）：超时仍未 open 视为不可用 */
const CONNECT_TIMEOUT_MS = 8_000
/** 连续失败次数上限：超过即降级（LLD 11.1「重试 2 次失败」） */
const MAX_RETRY = 2
/** 关注的事件名：收到任意一条都触发一次看板刷新 */
const EVENT_NAMES: OrderStatusEvent['type'][] = ['ORDER_STATUS_CHANGED', 'PICKUP_READY']

/**
 * 建立看板事件订阅。返回句柄供调用方在离开页面/切后台时关闭。
 *
 * @returns 订阅句柄；`EventSource` 不可用（老浏览器）时返回一个已触发 `onFallback` 的哑句柄
 */
export function subscribeBoardEvents(handlers: BoardEventHandlers): BoardEventSubscription {
  const token = getToken()

  // EventSource 缺失（或未登录）直接降级：不抛错，交给调用方的轮询通道兜底。
  // 降级回调推迟到微任务，保证调用方先拿到返回句柄再收到 onFallback（避免赋值顺序歧义）。
  if (typeof EventSource === 'undefined' || !token) {
    queueMicrotask(() => handlers.onFallback())
    return { close: () => undefined }
  }

  let closed = false
  let retries = 0
  let source: EventSource | null = null
  let connectTimer: number | null = null

  function clearConnectTimer(): void {
    if (connectTimer !== null) {
      window.clearTimeout(connectTimer)
      connectTimer = null
    }
  }

  function teardown(): void {
    clearConnectTimer()
    if (source) {
      source.close()
      source = null
    }
  }

  /** 判定不可用：关闭通道并通知调用方回落轮询（只触发一次） */
  function fallback(): void {
    if (closed) return
    closed = true
    teardown()
    handlers.onFallback()
  }

  function connect(): void {
    if (closed) return
    const url = `${EVENT_PATH}?token=${encodeURIComponent(token)}`
    const current = new EventSource(url)
    source = current

    // 建连超时：超时未 open 视为不可用（LLD 11.1 触发降级①）
    connectTimer = window.setTimeout(() => {
      if (closed || current !== source) return
      retries += 1
      if (retries > MAX_RETRY) {
        fallback()
      } else {
        teardown()
        connect()
      }
    }, CONNECT_TIMEOUT_MS)

    current.onopen = () => {
      if (closed || current !== source) return
      retries = 0
      clearConnectTimer()
    }

    current.onerror = () => {
      // 已关闭 / 已被新连接顶替的旧句柄：忽略
      if (closed || current !== source) return
      // EventSource 自身会在 readyState=CONNECTING 时自动重连；关闭态说明服务端已断开流
      if (current.readyState === EventSource.CLOSED) {
        fallback()
        return
      }
      retries += 1
      if (retries > MAX_RETRY) {
        fallback()
      }
    }

    for (const name of EVENT_NAMES) {
      current.addEventListener(name, (raw) => {
        if (closed || current !== source) return
        // 收到任意事件即视为连接健康，失败计数归零
        retries = 0
        const event = parseEvent((raw as MessageEvent).data)
        if (event) {
          handlers.onEvent(event)
        }
      })
    }
  }

  connect()
  return {
    close(): void {
      closed = true
      teardown()
    }
  }
}

/** 解析 data 行为事件体；脏帧（非 JSON）返回 null，不影响后续事件 */
function parseEvent(data: unknown): OrderStatusEvent | null {
  if (typeof data !== 'string' || data === '') return null
  try {
    const parsed = JSON.parse(data) as OrderStatusEvent
    return parsed && typeof parsed.type === 'string' ? parsed : null
  } catch {
    return null
  }
}
