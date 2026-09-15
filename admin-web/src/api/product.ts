import { request, type PageQuery, type PageResult } from '@/utils/request'

/**
 * 商家端商品 / 分类 / 规格模板 / 图片域接口（LLD 3.5.1 + 3.5.2 + 3.5.3）。
 * 说明：LLD 未逐一约定写操作（新建/编辑/删除）的响应 data，此处统一声明为 void，
 * 页面按列表刷新处理；联调期若后端返回实体，只需替换对应泛型。
 */

/** 商品分页查询参数（GET /api/admin/products） */
export interface ProductQuery extends PageQuery {
  categoryId?: number
  /** 0=下架 1=上架；不传查全部 */
  status?: 0 | 1
}

/** 商品条目（含图片 key、价格、状态、适用规格组 id 列表） */
export interface AdminProduct {
  id: number
  name: string
  categoryId: number
  basePrice: string
  description: string
  imageKey: string
  /** 0=下架 1=上架 */
  status: number
  sortOrder: number
  specGroupIds: number[]
}

/** 新建 / 编辑商品请求体（编辑价格不影响历史订单——快照机制） */
export interface ProductSaveRequest {
  name: string
  categoryId: number
  basePrice: string
  description?: string
  imageKey?: string
  sortOrder?: number
  specGroupIds?: number[]
}

/** 分类条目（含每组商品数，删除前置校验） */
export interface Category {
  id: number
  name: string
  sortOrder: number
  productCount: number
}

/** 新建 / 编辑分类请求体 */
export interface CategorySaveRequest {
  name: string
  sortOrder?: number
}

/** 规格选项 */
export interface SpecOption {
  id: number
  name: string
  priceDelta: string
  sortOrder: number
  /** 1=启用 0=停用 */
  enabled: number
}

/** 规格组（杯型/温度/甜度/加料四个系统模板，不提供删除） */
export interface SpecGroup {
  id: number
  /** 组编码，如 CUP_SIZE / TEMPERATURE / SWEETNESS / TOPPING（LLD 3.3 菜单结构） */
  code: string
  name: string
  multiSelect: boolean
  sortOrder: number
  enabled: number
  options: SpecOption[]
}

/** 组内新增 / 编辑规格选项请求体（新增用 name/priceDelta/sortOrder；编辑可再带 enabled） */
export interface SpecOptionSaveRequest {
  name: string
  priceDelta: string
  sortOrder?: number
  enabled?: number
}

/** 编辑规格组请求体（名称/排序/启用） */
export interface SpecGroupSaveRequest {
  name?: string
  sortOrder?: number
  enabled?: number
}

/** POST /api/admin/upload 响应 data */
export interface UploadResult {
  key: string
  url: string
}

/** 商品分页列表（GET /api/admin/products） */
export function getProducts(params: ProductQuery = {}): Promise<PageResult<AdminProduct>> {
  return request<PageResult<AdminProduct>>({ url: '/admin/products', method: 'get', params })
}

/** 新建商品 */
export function createProduct(data: ProductSaveRequest): Promise<void> {
  return request<void>({ url: '/admin/products', method: 'post', data })
}

/** 编辑商品（同新建字段） */
export function updateProduct(id: number, data: ProductSaveRequest): Promise<void> {
  return request<void>({ url: `/admin/products/${id}`, method: 'put', data })
}

/** 商品上下架（body {status: 0|1}） */
export function updateProductStatus(id: number, status: 0 | 1): Promise<void> {
  return request<void>({ url: `/admin/products/${id}/status`, method: 'put', data: { status } })
}

/** 设置商品适用规格组（body {groupIds: []}，全量覆盖） */
export function setProductSpecGroups(id: number, groupIds: number[]): Promise<void> {
  return request<void>({ url: `/admin/products/${id}/spec-groups`, method: 'put', data: { groupIds } })
}

/** 分类列表（含每组商品数） */
export function getCategories(): Promise<Category[]> {
  return request<Category[]>({ url: '/admin/categories', method: 'get' })
}

/** 新建分类（name/sortOrder） */
export function createCategory(data: CategorySaveRequest): Promise<void> {
  return request<void>({ url: '/admin/categories', method: 'post', data })
}

/** 编辑分类（更名/排序） */
export function updateCategory(id: number, data: CategorySaveRequest): Promise<void> {
  return request<void>({ url: `/admin/categories/${id}`, method: 'put', data })
}

/** 删除分类（分类下有商品时后端返回 1001） */
export function deleteCategory(id: number): Promise<void> {
  return request<void>({ url: `/admin/categories/${id}`, method: 'delete' })
}

/** 全部规格组及选项（杯型/温度/甜度/加料） */
export function getSpecGroups(): Promise<SpecGroup[]> {
  return request<SpecGroup[]>({ url: '/admin/spec-groups', method: 'get' })
}

/** 组内新增规格选项（name/priceDelta/sortOrder） */
export function createSpecOption(groupId: number, data: SpecOptionSaveRequest): Promise<void> {
  return request<void>({ url: `/admin/spec-groups/${groupId}/options`, method: 'post', data })
}

/** 编辑规格选项（含价差；enabled=0 等效停用） */
export function updateSpecOption(id: number, data: SpecOptionSaveRequest): Promise<void> {
  return request<void>({ url: `/admin/spec-options/${id}`, method: 'put', data })
}

/** 编辑规格组（名称/排序/启用） */
export function updateSpecGroup(groupId: number, data: SpecGroupSaveRequest): Promise<void> {
  return request<void>({ url: `/admin/spec-groups/${groupId}`, method: 'put', data })
}

/**
 * 图片上传（multipart/form-data）：类型白名单 jpg/png/webp、单张 ≤2MB，key 由后端生成。
 * 注意：不要手工设置 Content-Type，浏览器会自动带上 multipart boundary。
 */
export function uploadImage(file: File): Promise<UploadResult> {
  const formData = new FormData()
  formData.append('file', file)
  return request<UploadResult>({ url: '/admin/upload', method: 'post', data: formData })
}

/**
 * 图片访问地址：DB 只存 key，对外一律走后端代理 /api/files/{key}（LLD 9.4.2，
 * 穿透域名变化不影响历史数据）。仅用于 <img :src>，不走 axios。
 */
export function fileUrl(key?: string | null): string {
  return key ? `/api/files/${key}` : ''
}
