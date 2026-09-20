import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { getToken } from '@/utils/token'

/**
 * 路由表（LLD 7.2）：/login 免守卫；业务页面懒加载。
 * 已落地：/board（T19）、/counter（T20）、/products（T21）、/categories 与 /specs（T22）；
 * 待落地：/stats（T35）、/settings。
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
    path: '/board',
    name: 'board',
    component: () => import('@/views/Board.vue')
  },
  {
    path: '/counter',
    name: 'counter',
    component: () => import('@/views/Counter.vue')
  },
  {
    path: '/products',
    name: 'products',
    component: () => import('@/views/Products.vue')
  },
  {
    path: '/categories',
    name: 'categories',
    component: () => import('@/views/Categories.vue')
  },
  {
    path: '/specs',
    name: 'specs',
    component: () => import('@/views/Specs.vue')
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
