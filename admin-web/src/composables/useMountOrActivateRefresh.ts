import { onActivated, onMounted } from 'vue'

/**
 * 「挂载即取数 + keep-alive 重新激活时再取一次」的统一封装。
 *
 * 背景：keep-alive 缓存的页面（看板 / 统计）首次挂载后不会重新触发 onMounted，
 * 若只在 onMounted 取数，回到页面时数据会停留在上次结果；若 onMounted 与 onActivated
 * 都无条件取数，首屏又会重复请求一次。
 *
 * 判定方式：激活时若距挂载不足 300ms 视为「首次挂载后的那次 activated」并跳过。
 * 相比布尔标志，这种做法不依赖「onActivated 首次必然触发」这一前提——
 * 即使组件实际未被缓存（onActivated 不触发），也不会把后续激活误判为首次。
 */
export function useMountOrActivateRefresh(refresh: () => void): void {
  let mountedAt = 0

  onMounted(() => {
    mountedAt = Date.now()
    refresh()
  })

  onActivated(() => {
    if (Date.now() - mountedAt < 300) return
    refresh()
  })
}
