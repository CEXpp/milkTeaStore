import { request } from '@/utils/request'

/**
 * 顾客订阅消息接口（T44，SRS 第 8 章「顾客·订阅」接口组）。
 *
 * 模板 ID 只配置在服务端，前端按需拉取——避免同一份 ID 两端各写一份，换模板时漏改。
 */

/** 可订阅的微信模板 */
export interface SubscribeTemplate {
  /** 业务模板键：PREPARING / PICKUP */
  key: string
  /** 微信模板 ID */
  templateId: string
}

/** 上报授权结果 */
export interface SubscribeReportResult {
  /** 本次登记成功的模板数 */
  granted: number
  /** 登记后剩余可下发总次数 */
  remainingTotal: number
}

/**
 * 拉取可订阅模板。
 *
 * 未配置模板（练手期默认）时返回空数组，调用方据此整体跳过授权请求——
 * 这是「订阅消息不可用不影响主流程」的降级入口。
 */
export function getSubscribeTemplates(): Promise<SubscribeTemplate[]> {
  return request<SubscribeTemplate[]>({ url: '/api/customer/subscribe/templates', auth: true })
}

/**
 * 上报用户同意订阅的模板（一次性订阅的额度记在服务端，不回传则服务端无从得知）。
 *
 * @param accepted 业务模板键列表，如 ['PREPARING', 'PICKUP']
 */
export function reportSubscribe(accepted: string[]): Promise<SubscribeReportResult> {
  return request<SubscribeReportResult>({
    url: '/api/customer/subscribe',
    method: 'POST',
    data: { accepted },
    auth: true
  })
}
