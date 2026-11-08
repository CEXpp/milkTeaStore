import { BASE_URL } from '@/utils/config'
import { getToken, login } from '@/utils/request'

/**
 * 小程序侧的分块流式通道（SSE）。
 *
 * ## 为什么不用 EventSource
 * 小程序没有浏览器原生的 `EventSource`；`uni.request` 的 `enableChunked: true` +
 * `onChunkReceived` 才是官方通道（基础库 2.20.0 起支持）。它还有一个额外好处：
 * **能发 POST、能带 Authorization 头**，因此令牌不必像既有订单状态通道那样放进 URL
 * （URL 会被写进访问日志）。
 *
 * ## 为什么自己写 UTF-8 解码
 * 分块回调给的是 `ArrayBuffer`。虽然现代基础库有 `TextDecoder`，但它在小程序侧的可用性
 * 与版本相关——流式通道是「有就用、没有就降级」的能力，不该因为一个解码器不可用而整条挂掉。
 * 这里手写一份，并在非法字节上**跳过而不是抛错**：一个坏字节不该炸掉整轮对话。
 *
 * ## 跨块的多字节字符
 * 一个中文字符占 3 个字节，而分块边界可能正好切在字符中间。因此每个块解码后要把
 * 「尾部不完整的字节」留到下一块拼上——直接丢弃会导致偶发的乱码或丢字。
 */

/** 收到一个 SSE 事件（`name` 为空串表示该帧没有 `event:` 行） */
export type SseEventListener = (name: string, data: string) => void

/** 打开流式通道的参数 */
export interface SseOptions {
  /** 相对路径，如 `/api/customer/ai/chat/stream` */
  url: string
  /** 请求体（POST JSON） */
  data: Record<string, unknown>
  /** 事件回调 */
  onEvent: SseEventListener
  /** 传输层正常结束（服务端 complete / 连接关闭） */
  onClose?: () => void
  /** 传输层失败（网络异常、非 2xx） */
  onFail?: (error: Error) => void
}

/** 流式通道句柄 */
export interface SseHandle {
  /** 主动断开（页面卸载、用户重新提问时用） */
  abort: () => void
  /**
   * 流结束（正常关闭或失败）后 resolve。
   *
   * <p>必须有它：`openSse` 在请求**发出**后就会返回，事件是之后陆续到达的。
   * 调用方（例如 AI 页）需要「等这一轮真正说完」才能把输入框重新放开，
   * 否则会在第一个徽章亮起时就允许再次发送。</p>
   */
  completed: Promise<void>
}

/**
 * 当前基础库是否支持分块接收。
 *
 * 用版本号判断而不是「试一次看报不报错」：不支持时请求本身可能成功
 * （服务端会一次性返回整段），于是「没报错」并不能证明流式可用。
 * 判断为不支持时调用方直接走非流式接口，行为与升级前一致。
 */
export function isChunkedSupported(): boolean {
  try {
    const info = uni.getSystemInfoSync() as { SDKVersion?: string }
    return compareVersion(info.SDKVersion || '0', '2.20.0') >= 0
  } catch {
    return false
  }
}

/** 打开一条 SSE 通道（自动带上顾客令牌）。 */
export async function openSse(options: SseOptions): Promise<SseHandle> {
  const token = getToken() || (await login())

  let pending = new Uint8Array(0)
  let textBuffer = ''
  let closed = false
  let resolveCompleted: () => void = () => undefined
  const completed = new Promise<void>((resolve) => {
    resolveCompleted = resolve
  })

  const finish = (error?: Error): void => {
    if (closed) {
      return
    }
    closed = true
    if (error) {
      options.onFail?.(error)
    } else {
      options.onClose?.()
    }
    resolveCompleted()
  }

  // 这里必须显式转换返回值：uni-app 的类型定义在「传了 success 回调」时会把返回值推成
  // Promise<RequestSuccessCallbackResult>，而运行时它始终是 RequestTask（onChunkReceived 在它上面）。
  const task = uni.request({
    url: BASE_URL + options.url,
    method: 'POST',
    data: options.data,
    header: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`
    },
    // 分块接收的两个必要条件：开启 enableChunked 且要求二进制响应
    enableChunked: true,
    responseType: 'arraybuffer',
    success: (res: UniApp.RequestSuccessCallbackResult) => {
      if (res.statusCode !== 200) {
        // 鉴权失败等由过滤器直接返回 JSON（不是事件流），在此归一成错误
        finish(new Error(`流式通道返回 ${res.statusCode}`))
        return
      }
      finish()
    },
    fail: (err: UniApp.GeneralCallbackResult) => finish(new Error(err.errMsg || '流式通道连接失败'))
    // enableChunked / responseType 在部分版本的类型定义里没有声明，故做一次宽松转换
  } as unknown as UniApp.RequestOptions) as unknown as UniApp.RequestTask

  // onChunkReceived 只在支持分块的基础库上存在；缺失时通道仍可用（服务端一次性返回），
  // 只是没有增量——调用方会发现「没有 delta 事件」，由它决定是否降级。
  const chunkable = task as unknown as {
    onChunkReceived?: (cb: (res: { data: ArrayBuffer }) => void) => void
  }
  chunkable.onChunkReceived?.((res) => {
    if (closed) {
      return
    }
    const decoded = decodeChunk(pending, new Uint8Array(res.data))
    pending = decoded.pending
    textBuffer += decoded.text
    textBuffer = drainEvents(textBuffer, options.onEvent)
  })

  return {
    abort: () => {
      const wasClosed = closed
      closed = true
      try {
        task.abort()
      } catch {
        // 已结束的连接再 abort 会抛，忽略即可
      }
      if (!wasClosed) {
        resolveCompleted()
      }
    },
    completed
  }
}

/** 从缓冲区里取出所有完整事件帧，返回剩余的不完整尾巴。 */
function drainEvents(buffer: string, listener: SseEventListener): string {
  let rest = buffer
  let index = rest.indexOf('\n\n')
  while (index >= 0) {
    const frame = rest.slice(0, index)
    rest = rest.slice(index + 2)
    dispatch(frame, listener)
    index = rest.indexOf('\n\n')
  }
  return rest
}

/** 解析单帧：`event:` 行给事件名，多个 `data:` 行按 SSE 规范用换行拼接。 */
function dispatch(frame: string, listener: SseEventListener): void {
  let name = ''
  const dataLines: string[] = []
  for (const rawLine of frame.split('\n')) {
    const line = rawLine.replace(/\r$/, '')
    if (line.startsWith('event:')) {
      name = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).replace(/^\s/, ''))
    }
  }
  if (!name && dataLines.length === 0) {
    return
  }
  listener(name, dataLines.join('\n'))
}

/** 一次分块解码的结果：已解码文本 + 需要留到下一块的残缺字节 */
interface DecodedChunk {
  text: string
  pending: Uint8Array
}

/**
 * 递增式 UTF-8 解码。
 *
 * 非法字节直接跳过（宁少一个字，不抛异常）；尾部不完整的多字节序列留给下一块，
 * 否则分块边界正好切在汉字中间时会丢字。
 */
function decodeChunk(pending: Uint8Array, chunk: Uint8Array): DecodedChunk {
  const bytes = new Uint8Array(pending.length + chunk.length)
  bytes.set(pending, 0)
  bytes.set(chunk, pending.length)

  let text = ''
  let i = 0
  while (i < bytes.length) {
    const byte = bytes[i]
    if (byte < 0x80) {
      text += String.fromCharCode(byte)
      i += 1
      continue
    }
    let need = 0
    let codePoint = 0
    if (byte >= 0xc0 && byte < 0xe0) {
      need = 1
      codePoint = byte & 0x1f
    } else if (byte >= 0xe0 && byte < 0xf0) {
      need = 2
      codePoint = byte & 0x0f
    } else if (byte >= 0xf0) {
      need = 3
      codePoint = byte & 0x07
    } else {
      // 孤立续字节：非法，跳过
      i += 1
      continue
    }
    if (i + need >= bytes.length) {
      break
    }
    let valid = true
    for (let k = 1; k <= need; k += 1) {
      const next = bytes[i + k]
      if (next < 0x80 || next >= 0xc0) {
        valid = false
        break
      }
      codePoint = (codePoint << 6) | (next & 0x3f)
    }
    if (!valid) {
      i += 1
      continue
    }
    if (codePoint > 0xffff) {
      const offset = codePoint - 0x10000
      text += String.fromCharCode(0xd800 + (offset >> 10), 0xdc00 + (offset & 0x3ff))
    } else {
      text += String.fromCharCode(codePoint)
    }
    i += need + 1
  }

  return { text, pending: bytes.slice(i) }
}

/** 版本号比较：`a >= b` 返回 >=0（只比较数字段，忽略其它字符）。 */
function compareVersion(a: string, b: string): number {
  const left = a.split('.').map((part) => Number.parseInt(part, 10) || 0)
  const right = b.split('.').map((part) => Number.parseInt(part, 10) || 0)
  const length = Math.max(left.length, right.length)
  for (let i = 0; i < length; i += 1) {
    const diff = (left[i] || 0) - (right[i] || 0)
    if (diff !== 0) {
      return diff
    }
  }
  return 0
}
