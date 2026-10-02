import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { getToken } from '@/utils/token'

/**
 * 路由表（LLD 7.2）：/login 免守卫且独立布局；业务页统一挂在 AdminLayout 之下。
 *
 * meta 约定：
 * - title / desc：顶栏标题与副标题（缺省时由 config/nav.ts 按 path 兜底）；
 * - fullHeight：内容区不留白不滚动，由页面内部控制（柜台点单）；
 * - keepAlive：切换后保留实例，避免重建与重复请求（看板、统计）。
 */
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/board' },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/Login.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('@/layout/AdminLayout.vue'),
    children: [
      {
        path: 'board',
        name: 'board',
        component: () => import('@/views/Board.vue'),
        meta: { title: '订单看板', keepAlive: true }
      },
      {
        path: 'counter',
        name: 'counter',
        component: () => import('@/views/Counter.vue'),
        meta: { title: '柜台点单', fullHeight: true }
      },
      {
        path: 'products',
        name: 'products',
        component: () => import('@/views/Products.vue'),
        meta: { title: '商品管理' }
      },
      {
        path: 'categories',
        name: 'categories',
        component: () => import('@/views/Categories.vue'),
        meta: { title: '分类管理' }
      },
      {
        path: 'specs',
        name: 'specs',
        component: () => import('@/views/Specs.vue'),
        meta: { title: '规格模板' }
      },
      {
        path: 'stats',
        name: 'stats',
        component: () => import('@/views/Stats.vue'),
        meta: { title: '账台统计', keepAlive: true }
      },
      {
        path: 'archives',
        name: 'archives',
        component: () => import('@/views/BrewArchives.vue'),
        meta: { title: '制作档案' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/board' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局守卫：未登录访问业务页 → 跳 /login 并携带回跳地址；已登录访问 /login → 直接进看板
router.beforeEach((to) => {
  const loggedIn = Boolean(getToken())
  if (!to.meta.public && !loggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && loggedIn) {
    return { path: '/board' }
  }
  return true
})

export default router
