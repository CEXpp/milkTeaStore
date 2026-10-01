import type { SubscribeTemplate } from '@/api/subscribe'

/**
 * 微信订阅消息授权封装（T44）。
 *
 * <h2>必须注意的调用时机约束</h2>
 * 微信官方文档（wx.requestSubscribeMessage）明确：
 * <pre>
 *   2.8.2 版本开始，用户发生点击行为或者发起支付回调后，才可以调起订阅消息界面。
 * </pre>
 * 因此本函数<b>必须在用户点击事件处理函数的同步调用栈内发起</b>（即「点击 → 直接调用」，
 * 中间不能先 await 一次网络请求）；否则会以
 * {@code requestSubscribeMessage:fail can only be invoked by user TAP gesture} 失败。
 *
 * 工程含义：模板 ID 必须<b>提前</b>取好（页面加载时拉取并缓存），点击时直接用。
 *
 * <h2>降级</h2>
 * 宿主不支持该 API（H5 端）、无模板、用户拒绝、接口报错——一律 resolve 空数组，
 * 由调用方跳过上报。订阅提醒只是增强项，任何失败都不得影响支付与取餐主流程（SRS 9.3）。
 */

/** 宿主是否支持订阅消息 API（H5 端没有；微信小程序端有）。 */
export function canRequestSubscribe(): boolean {
  return typeof uni.requestSubscribeMessage === 'function'
}

/**
 * 发起订阅授权请求。
 *
 * @param templates 可订阅模板（须已提前拉取，不可在本函数内再发网络请求）
 * @returns 用户<b>同意</b>订阅的业务模板键列表；拒绝 / 不支持 / 异常时为空数组
 */
export function requestSubscribeQuota(templates: SubscribeTemplate[]): Promise<string[]> {
  return new Promise((resolve) => {
    if (templates.length === 0 || !canRequestSubscribe()) {
      resolve([])
      return
    }
    // 微信回调以「模板 ID」为键返回 accept/reject/ban/filter，这里映射回业务键再上报
    const keyByTemplateId = new Map(templates.map((template) => [template.templateId, template.key]))
    uni.requestSubscribeMessage({
      tmplIds: templates.map((template) => template.templateId),
      success: (res) => {
        // 类型定义只声明了 errMsg（官方文档的动态模板键未进 @dcloudio/types），故需断言取值
        const result = res as unknown as Record<string, string>
        const accepted: string[] = []
        keyByTemplateId.forEach((key, templateId) => {
          if (result[templateId] === 'accept') {
            accepted.push(key)
          }
        })
        resolve(accepted)
      },
      fail: () => resolve([])
    })
  })
}
