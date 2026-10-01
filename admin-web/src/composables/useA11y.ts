import { ref } from 'vue'

/**
 * 无障碍模式（T45，SRS 9.6 / LLD 11.4）——商家端呈现层开关。
 *
 * <p>设计纪律「共享逻辑、分叉呈现」：本开关**只切换样式**（在 {@code <html>} 上挂一个 class），
 * 不触发任何后端请求、不改变任何数据读取与写入语义。因此它满足任务卡验收项
 * 「开关不改变任何订单数据与统计口径」——因为整条业务链路根本不感知它的存在。</p>
 *
 * <p>偏好存在 localStorage（刷新 / 重新登录后保持），与「暂停接单」这类<b>业务状态</b>互不相干：
 * 前者是当前浏览器的显示偏好，后者是门店的全局营业状态。</p>
 */

const STORAGE_KEY = 'milk_tea_admin_a11y'

/** 挂在 {@code <html>} 上的类名：a11y.css 的所有规则都以它为前缀 */
const HTML_CLASS = 'a11y'

/** 模块级单例状态：顶栏开关与样式共用同一份，避免多处各持一份导致不同步 */
const enabled = ref(false)

function apply(value: boolean): void {
  document.documentElement.classList.toggle(HTML_CLASS, value)
}

/** 首次使用时按持久化偏好恢复（SPA 场景在浏览器环境执行，无 SSR 顾虑） */
function init(): void {
  try {
    enabled.value = window.localStorage.getItem(STORAGE_KEY) === '1'
  } catch {
    // 隐私模式等禁用 storage 的场景：按关闭处理，不影响页面可用
    enabled.value = false
  }
  apply(enabled.value)
}

init()

export function useA11y() {
  /** 切换并持久化；返回切换后的值。 */
  function toggle(value: boolean): void {
    enabled.value = value
    try {
      window.localStorage.setItem(STORAGE_KEY, value ? '1' : '0')
    } catch {
      // 写失败也仍然让本次会话生效（只是下次打开不保留）
    }
    apply(value)
  }

  return { enabled, toggle }
}
