docker指定.env构建
# 在服务器上
# 1. 把 .env 放到某个目录（比如 /opt/milktea/）

# 镜像由 .github/workflows/ci.yml 的 docker-* job 在三端校验全部跑绿后推送（仅 main 分支）。
# 本仓库是公开仓库，工作流用 GITHUB_TOKEN 创建的包会继承仓库可见性，
# 因此下面两个包均公开，服务器上 docker pull 前不需要 docker login。

# 拉取镜像（示例版本，最新版查看包）
docker pull ghcr.io/cexpp/milkteastore:sha-33d7193
docker pull ghcr.io/cexpp/milkteastore-admin-web:sha-33d7193

# 2. 运行容器时指定 env-file
# 注意两点，否则前端 /api 必定 502：
#   · 后端容器名必须是 backend —— admin-web 的 nginx 把 proxy_pass 写死成 http://backend:8080
#   · 两个容器必须在同一个「自定义网络」上 —— 默认 bridge 网络不做容器名 DNS 解析
docker network create milktea

docker run -d \
  --name backend \
  --network milktea \
  --env-file /opt/milktea/.env \
  -p 8080:8080 \
  ghcr.io/cexpp/milkteastore:sha-33d7193

docker run -d \
  --name admin-web \
  --network milktea \
  -p 8081:80 \
  ghcr.io/cexpp/milkteastore-admin-web:sha-33d7193

# 访问 http://<服务器>:8081/ ，/api 由容器内 nginx 转发到 backend:8080

# 3. 或者直接用编排（推荐，DNS 与启动顺序由 compose 负责）
#    把 docker/docker-compose.yml 与 backend/.env 放到服务器上：
# cd docker && docker compose up -d --build
#    （compose 文件里的 image 名为 milktea-backend / milktea-admin-web，
#     若要用上面 GHCR 拉下来的镜像名，改 image 字段或用 docker tag 改名）
