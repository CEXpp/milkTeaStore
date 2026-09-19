/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 开发期 vite proxy 目标（仅 vite.config.ts 读取，业务代码不感知） */
  readonly VITE_PROXY_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
