# =====================================================================
# backend/Dockerfile
# 多阶段构建：JDK 25 编译打包 → 同版本 JDK 25 运行（含 curl 供健康检查）
#
# 适用：milkTeaStore 后端（Spring Boot 4.1.1 / Java 25 / Maven）
# 端口：8080（Spring Boot 默认，与 scripts/smoke.sh 默认探测一致）
# 构建上下文：backend/ 目录（docker-compose 中以 ../backend 为 context）
# 数据依赖：MySQL / MinIO 在远端服务器，由 application-dev.yml 指定，容器不内置
# =====================================================================

# ---------------------------------------------------------------------
# 1) 构建阶段：用仓库自带的 Maven Wrapper（mvnw）完成依赖下载与打包，
#    避免宿主机 Maven 版本与 JDK 25 不匹配。
# ---------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# 先拷入构建描述与 wrapper，预热依赖以命中缓存层；
# 注意：COPY <dir> <dest> 是「拷目录内容、不拷目录名」，故 .mvn 必须显式指定同名目标，
#       否则 wrapper 会落到 ./wrapper，mvnw 找不到 ./.mvn/wrapper/maven-wrapper.properties。
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw \
    && ./mvnw -B -ntp -DskipTests dependency:go-offline || true

# 再拷源码并打包；跳过测试（@SpringBootTest 需 MySQL 服务容器，见 CI）
COPY src ./src
# 选出可执行 jar（排除 Spring Boot 重命名的 *.jar.original）
RUN ./mvnw -B -ntp -DskipTests package \
    && cp $(ls target/*.jar | grep -v '\.original$') /app.jar

# ---------------------------------------------------------------------
# 2) 运行阶段
# ---------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS runtime
WORKDIR /app

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8

# 仅装 curl 供 HEALTHCHECK 探测 /actuator/health（Ubuntu 基础镜像默认无 wget/curl）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app.jar /app/app.jar

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD curl -f http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
