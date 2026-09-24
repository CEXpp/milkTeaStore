###############################################################################
# 管理后台前端镜像：两阶段构建
#   构建阶段：jujube/node:24-alpine（Node 24 LTS，满足 Vite 8 要求）
#   运行阶段：jujube/nginx:1.28-alpine（静态托管 + /api 反代）
#
# 构建（上下文必须是 admin-web 目录）：
#   docker build -t <前端镜像名>:<tag> ./admin-web
###############################################################################

# ---------- 阶段 1：编译 ----------
FROM crpi-0ocfwxlhi33l7izj.cn-guangzhou.personal.cr.aliyuncs.com/jujube/node:24-alpine AS build

WORKDIR /build

# npm 依赖走国内源（npmmirror 是 npm 官方源的完整代理，内容一致）
ENV NPM_CONFIG_REGISTRY=https://registry.npmmirror.com

# 先只拷 lock + manifest 下依赖：源码改动时不会重新下载 node_modules
COPY package.json package-lock.json ./
RUN npm ci

# 再拷源码构建（vue-tsc 类型检查 + vite build，产物在 dist/）
COPY . .
RUN npm run build

# ---------- 阶段 2：nginx 托管 ----------
FROM crpi-0ocfwxlhi33l7izj.cn-guangzhou.personal.cr.aliyuncs.com/jujube/nginx:1.28-alpine

ENV TZ=Asia/Shanghai

# 站点配置：SPA 回退 + /api 反代到 backend 容器
COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q --spider http://127.0.0.1/ || exit 1

# nginx 官方镜像自带前台启动命令，无需自定义 ENTRYPOINT
