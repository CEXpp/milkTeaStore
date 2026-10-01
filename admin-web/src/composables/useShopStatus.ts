import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getShopStatus, updateShopPause } from '@/api/shop'

/**
 * 门店营业状态（T23 暂停接单）：从 Board.vue 上提到布局层，顶栏与看板共用同一份状态。
 *
 * 语义保持原样：暂停只拦截顾客端「创建订单」，已下单的订单照常流转；
 * 切换失败时回拉服务端真值，避免开关显示与后端不一致。
 * 模块级单例：顶栏与看板共享同一组 ref，并用 in-flight Promise 合并并发的首次加载。
 */

/** 暂停接单时写入的顾客端提示语（LLD 3.5.5 的 notice 字段） */
const DEFAULT_PAUSE_NOTICE = '高峰期制作中，稍后开放点单'

const paused = ref(false)
const notice = ref<string | null>(null)
const busy = ref(false)
const loaded = ref(false)
/** 首次加载的在途 Promise：顶栏与看板同时调用 ensureLoaded 时只发一次请求 */
let inFlight: Promise<void> | null = null

/** 读取门店营业状态：开关初始值 + 顾客端提示语 */
async function loadShopStatus(): Promise<void> {
  try {
    const status = await getShopStatus()
    paused.value = status.paused
    notice.value = status.notice
    loaded.value = true
  } catch {
    // 错误提示已由 request 层直显
  }
}

/** 首次进入后台时加载一次；后续由顶栏/看板共享，避免重复请求 */
async function ensureLoaded(): Promise<void> {
  if (loaded.value) return
  if (inFlight) return inFlight
  inFlight = loadShopStatus().finally(() => {
    inFlight = null
  })
  return inFlight
}

/** 退出登录时清空：避免下一个账号沿用上一个账号缓存的营业状态 */
function reset(): void {
  inFlight = null
  loaded.value = false
  paused.value = false
  notice.value = null
}

/**
 * 切换暂停接单。
 * @param value 开关的目标值（来自 el-switch 的 change）
 */
async function setPaused(value: boolean | string | number): Promise<void> {
  const next = Boolean(value)
  busy.value = true
  try {
    const result = await updateShopPause({ paused: next, notice: next ? DEFAULT_PAUSE_NOTICE : undefined })
    paused.value = result.paused
    notice.value = next ? DEFAULT_PAUSE_NOTICE : notice.value
    ElMessage.success(next ? '已暂停接单：顾客端无法下单' : '已恢复接单')
  } catch {
    // 失败回到服务端真值
    await loadShopStatus()
  } finally {
    busy.value = false
  }
}

export function useShopStatus() {
  return { paused, notice, busy, ensureLoaded, loadShopStatus, setPaused, reset }
}
