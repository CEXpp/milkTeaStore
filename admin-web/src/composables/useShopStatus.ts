import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getShopStatus, updateShopNotice, updateShopPause } from '@/api/shop'

/**
 * 门店营业状态（T23 暂停接单 + T62 营业公告）：从Board.vue 上提到布局层，顶栏与看板共用同一份状态。
 *
 * 语义保持原样：暂停只拦截顾客端「创建订单」，已下单的订单照常流转；
 * 切换失败时回拉服务端真值，避免开关显示与后端不一致。
 * 模块级单例：顶栏与看板共享同一组 ref，并用 in-flight Promise 合并并发的首次加载。
 *
 * T62 起 notice 的语义升级为**营业公告**（顾客端菜单顶部展示），不再由暂停开关自动写入——
 * 否则店长刚写好的公告会被切一次开关就覆盖掉。切暂停只改 paused。
 */

const paused = ref(false)
/** 营业公告（T62）：未发布或已撤下时为 null */
const notice = ref<string | null>(null)
const busy = ref(false)
/** 公告保存中（与暂停开关的 busy 分开，避免互相 disable） */
const noticeBusy = ref(false)
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
 *
 * T62 起<b>不再附带 notice</b>：公告是店长自主维护的独立字段，
 * 切开关不应覆盖它。暂停原因请写进公告。
 *
 * @param value 开关的目标值（来自 el-switch 的 change）
 */
async function setPaused(value: boolean | string | number): Promise<void> {
  const next = Boolean(value)
  busy.value = true
  try {
    const result = await updateShopPause({ paused: next })
    paused.value = result.paused
    ElMessage.success(next ? '已暂停接单：顾客端无法下单' : '已恢复接单')
  } catch {
    // 失败回到服务端真值
    await loadShopStatus()
  } finally {
    busy.value = false
  }
}

/**
 * 发布 / 撤下营业公告（T62）。
 *
 * @param value 公告正文；空串或 null 表示撤下
 * @returns 是否已成功保存（失败时返回 false，由调用方决定是否保持弹窗打开）
 */
async function setNotice(value: string | null): Promise<boolean> {
  if (noticeBusy.value) {
    return false
  }
  noticeBusy.value = true
  try {
    const result = await updateShopNotice(value)
    // 以后端归一化后的值为准：那才是库里真实存下的样子
    notice.value = result.notice
    ElMessage.success(result.notice ? '公告已发布，顾客端菜单顶部可见' : '公告已撤下')
    return true
  } catch {
    // 错误提示已由 request 层直显
    return false
  } finally {
    noticeBusy.value = false
  }
}

export function useShopStatus() {
  return {
    paused,
    notice,
    busy,
    noticeBusy,
    ensureLoaded,
    loadShopStatus,
    setPaused,
    setNotice,
    reset
  }
}
