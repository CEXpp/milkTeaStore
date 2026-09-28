import { DataAnalysis, Goods, Grid, PriceTag, Shop, Tickets } from '@element-plus/icons-vue'
import type { Component } from 'vue'

/**
 * 侧边导航单一数据源：侧栏菜单与顶栏标题共用。
 * 路径与 router/index.ts 中的业务路由一一对应，新增页面只需在此追加一项。
 */

export interface NavItem {
  /** 菜单与页面标题文案 */
  title: string
  /** 路由 path（与 router 中一致） */
  path: string
  /** 菜单图标（@element-plus/icons-vue 按需引入，不做全局注册以控制体积） */
  icon: Component
  /** 顶栏副标题 / 菜单 tooltip 说明 */
  desc: string
}

export interface NavGroup {
  label: string
  items: NavItem[]
}

export const NAV_GROUPS: NavGroup[] = [
  {
    label: '日常经营',
    items: [
      { title: '订单看板', path: '/board', icon: Tickets, desc: '实时接单与制作流转' },
      { title: '柜台点单', path: '/counter', icon: Shop, desc: '店内人工收银点单' }
    ]
  },
  {
    label: '商品配置',
    items: [
      { title: '商品管理', path: '/products', icon: Goods, desc: '商品上下架与规格绑定' },
      { title: '分类管理', path: '/categories', icon: Grid, desc: '菜单分组与展示顺序' },
      { title: '规格模板', path: '/specs', icon: PriceTag, desc: '杯型 / 温度 / 甜度 / 加料' }
    ]
  },
  {
    label: '经营数据',
    items: [{ title: '账台统计', path: '/stats', icon: DataAnalysis, desc: '营业额、销量与流水对账' }]
  }
]

/** 扁平菜单项（顶栏标题匹配用） */
export const NAV_ITEMS: NavItem[] = NAV_GROUPS.flatMap((group) => group.items)

/** path → 菜单项 */
export function findNavItem(path: string): NavItem | undefined {
  return NAV_ITEMS.find((item) => item.path === path)
}
