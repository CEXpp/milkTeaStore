import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

// 样式引入顺序不可调换：EP 基础样式 → 设计 token → EP 变量覆写 → 全局基础样式 → 无障碍覆写
import 'element-plus/dist/index.css'
import '@/styles/tokens.css'
import '@/styles/element-theme.css'
import '@/styles/base.css'
import '@/styles/a11y.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)
// 全量引入 Element Plus（组件文案走 zh-cn）；主题通过 CSS 变量在 element-theme.css 中覆写
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
