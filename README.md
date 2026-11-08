# 奶茶在线点单系统

项目由三个应用组成：`backend`（Spring Boot API）、`admin-web`（Vue 管理后台）和 `miniprogram`（uni-app 微信小程序）。数据库结构由后端的 Flyway 迁移管理，设计与验收资料在 `docs/`。

## 开发环境

- Java 25、Maven 3.9；Node.js 22（与 CI 一致）；MySQL 8、MinIO。
- 后端配置模板是 `backend/.env.example`。复制为 `backend/.env`，填写数据库、MinIO、`APP_ID` 和 JWT 密钥。该文件已被 Git 忽略。AI 密钥可留空，相关功能会降级。
- 后端默认启用 `dev` profile。需要免微信联调时，在可信本地环境设置 `DEV_LOGIN_ENABLED=true`，才会注册 `/api/customer/dev-login`。它允许指定顾客 openid；默认关闭。

```powershell
cd backend
mvn spring-boot:run
```

启动后可访问 `http://127.0.0.1:8080/actuator/health`。数据库迁移会在启动时执行。

```powershell
cd admin-web
npm ci
npm run dev
```

管理端开发服务器将 `/api` 代理到 `http://127.0.0.1:8080`；可通过 `VITE_PROXY_TARGET` 调整代理目标。

```powershell
cd miniprogram
npm ci
npm run dev:mp-weixin
```

用微信开发者工具打开 `miniprogram`。小程序请求地址由 `VITE_API_BASE_URL` 指定，默认 `http://localhost:8080`；真机联调需配置手机可访问的地址。开发构建默认使用开发登录，生产构建默认走微信登录，可通过 `VITE_USE_DEV_LOGIN` 显式设置。

## 检查

```powershell
cd backend; mvn test
cd ../admin-web; npm run build
cd ../miniprogram; npm run type-check
```

服务运行后，可在仓库根目录执行 `python scripts/smoke.py` 验证点单主链路；它会创建测试订单。`scripts/ai-smoke.py` 用于 AI 点单联调。

## 容器

`docker/docker-compose.yml` 默认只构建并启动后端，MySQL 和 MinIO 由 `--profile infra` 显式启用。容器内的 `localhost` 指向容器自身；若基础设施也由 Compose 启动，需将后端连接地址配置为 `mysql:3306` 和 `minio:9000`，并提供相应凭据。后端镜像的默认基础镜像位于私有仓库，构建前需有访问权限，或通过 Dockerfile 的构建参数替换。

生产部署必须使用非 `dev` profile，且不要开启 `DEV_LOGIN_ENABLED`。仓库目前只提供开发环境配置，部署前还需提供对应环境的数据库、存储、微信、密钥和跨域配置，并关闭演示数据入口。
