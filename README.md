# 奶茶在线点单系统

`backend` 提供 Spring Boot API，`admin-web` 是 Vue 管理后台，`miniprogram` 提供 uni-app 微信小程序和浏览器 H5。后端用 Flyway 管理数据库结构，设计及验收资料位于 `docs/`。

## 本地启动

准备 JDK 25、Maven 3.9、Node.js 22 和 MySQL 8.0。图片上传需要 MinIO。创建 UTF-8 数据库 `milk_tea`，将 `backend/.env.example` 复制为 `backend/.env`，填写 MySQL、MinIO 和 JWT 密钥。直接安装的 MySQL 通常使用 3306，Compose 的宿主机端口为 3307。仅信任的本地联调环境可设置 `DEV_LOGIN_ENABLED=true`，并将服务绑定到回环地址。使用开发登录时，微信和 AI 凭据可留空。

在三个终端分别从仓库根目录执行以下命令。

```powershell
cd backend
mvn spring-boot:run '-Dspring-boot.run.arguments=--server.address=127.0.0.1'
```

```powershell
cd admin-web
npm ci
npm run dev -- --host 127.0.0.1
```

```powershell
cd miniprogram
npm ci
npm run dev:h5 -- --host 127.0.0.1
```

- 后端健康检查为 `http://127.0.0.1:8080/actuator/health`。首次启动会迁移数据库并写入本地种子数据。
- 管理后台为 `http://127.0.0.1:5173/`，其 `/api` 默认代理到本机后端。新建本地数据库的演示账号为 `admin`，密码为 `admin123`。
- 顾客 H5 为 `http://127.0.0.1:5174/`。顾客端 API 地址可用 `VITE_API_BASE_URL` 设置。生产构建默认通过微信登录，H5 本地演示可显式设置 `VITE_USE_DEV_LOGIN=true`。
- 微信小程序在 `miniprogram/` 执行 `npm run dev:mp-weixin` 后，可用微信开发者工具导入 `dist/dev/mp-weixin`。正式微信登录需要同一应用的 AppID、AppSecret 和合法 HTTPS 域名。

## 测试

```powershell
cd backend; mvn test
cd ../admin-web; npm run build
cd ../miniprogram; npm run type-check; npm run build:mp-weixin; npm run build:h5
```

启动后端并打开本地开发登录后，可在仓库根目录运行 `$env:SMOKE_SKIP_TIMEOUT='1'; python scripts/smoke.py` 验证点单链路。要验证自动关单，在隔离环境中将 `ORDER_PAYMENT_TIMEOUT_MINUTES` 临时设为 `1`，并取消 `SMOKE_SKIP_TIMEOUT`。脚本会创建测试订单。`scripts/ai-smoke.py` 用于 AI 点单联调。

## 容器部署

将 `docker/.env.example` 复制为 `docker/.env` 并设置 MySQL 与 MinIO 的初始化凭据。在 `docker/` 运行 `docker compose --profile infra up -d mysql minio` 启动本地基础设施。新数据卷使用 `milk_tea`，已有卷保留原数据库名称。

运行 `docker compose up -d --build` 会启动 API 和管理端 Nginx。若同一 Compose 项目提供基础设施，在 `backend/.env` 中将数据库与 MinIO 的地址分别设为 `mysql:3306` 和 `http://minio:9000`，凭据与基础设施一致。管理端通过 `http://127.0.0.1:8081/` 访问，Nginx 反代 `/api/`，页面刷新使用 SPA 回退。

主分支 CI 在三端校验通过后向 GHCR 发布 `ghcr.io/cexpp/milkteastore` 和 `ghcr.io/cexpp/milkteastore-admin-web`。从 GHCR 单独运行镜像时，选择同一提交的 SHA 标签，并将两个容器放在同一自定义网络，令后端可由 `backend` 解析。

公开部署前需提供独立的环境配置和凭据，关闭开发登录及演示数据入口，并配置 HTTPS、访问来源和认证。当前支付渠道为本地模拟支付。
