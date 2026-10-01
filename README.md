docker指定.env构建
# 在服务器上
# 1. 把 .env 放到某个目录（比如 /opt/milktea/）

#拉取镜像（示例版本，最新版查看包）
docker pull ghcr.io/cexpp/milkteastore:sha-33d7193

# 2. 运行容器时指定 env-file
docker run -d \
  --name milktea-backend \
  --env-file /opt/milktea/.env \
  -p 8080:8080 \
  your-registry/milktea-backend:latest
