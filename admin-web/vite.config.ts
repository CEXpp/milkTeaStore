import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 开发期 /api 经 vite proxy 转发到本地后端（目标见 .env.development 的 VITE_PROXY_TARGET）；
 * 演示期前端与后端同源，请求层 baseURL 恒为相对路径 /api——两形态零代码差异（LLD 7.1）。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      host: true,
      proxy: {
        '/api': {
          target: env.VITE_PROXY_TARGET || 'http://127.0.0.1:8080',
          changeOrigin: true
        }
      }
    },
    build: {
      // 依赖按「变更频率」拆包：业务代码频繁改动，第三方依赖长期命中缓存
      chunkSizeWarningLimit: 1200,
      rollupOptions: {
        output: {
          manualChunks(id: string): string | undefined {
            if (!id.includes('node_modules')) return undefined
            if (id.includes('node_modules/echarts') || id.includes('node_modules/zrender')) return 'echarts'
            if (id.includes('node_modules/element-plus') || id.includes('@element-plus')) return 'element-plus'
            if (
              id.includes('node_modules/vue') ||
              id.includes('node_modules/@vue') ||
              id.includes('node_modules/pinia') ||
              id.includes('node_modules/vue-router')
            ) {
              return 'vue'
            }
            return 'vendor'
          }
        }
      }
    }
  }
})
