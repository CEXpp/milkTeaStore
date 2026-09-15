import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)
// 全量引入 Element Plus（组件文案走 zh-cn）；演示规模下无需按需构建配置
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
