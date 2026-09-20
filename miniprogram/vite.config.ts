import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [uni()],
  // H5 形态（npm run dev:h5）：用于电脑端快速自测页面与请求层；
  // 固定 5174 端口，避免与商家端 admin-web（5173）冲突。
  server: {
    host: true,
    port: 5174,
  },
});
