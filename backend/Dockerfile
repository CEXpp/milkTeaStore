# =====================================================================
# backend/Dockerfile —— ACR 构建规则 & GitHub Actions 通用（同一份文件）
#
# 基础镜像默认走个人 ACR 私有源（构建前需 docker login 该 registry）：
#   构建阶段：jujube/maven:3.9-eclipse-temurin-25-alpine（Maven 3.9 + JDK 25，自带 mvn）
#   运行阶段：jujube/eclipse-temurin:25-jre-alpine（仅 JRE，busybox 自带 wget 供健康检查）
#
# 两个环境都不需要传构建参数（ARG 默认值即 ACR 地址）；仅 VCS_REF 可选：
#   - GitHub Actions：--build-arg VCS_REF=${{ github.sha }}
#   - ACR 构建规则：  可在"构建参数"里设 VCS_REF（不设则落默认 unknown）
# 临时想换回 Docker Hub 公网镜像时，用 --build-arg 覆盖即可。
#
# 构建上下文必须是 backend/ 目录，需包含：Dockerfile / settings.xml / pom.xml / src
# =====================================================================

ARG BUILD_BASE=crpi-0ocfwxlhi33l7izj.cn-guangzhou.personal.cr.aliyuncs.com/jujube/maven:3.9-eclipse-temurin-25-alpine
ARG RUNTIME_BASE=crpi-0ocfwxlhi33l7izj.cn-guangzhou.personal.cr.aliyuncs.com/jujube/eclipse-temurin:25-jre-alpine
ARG VCS_REF=unknown

# ---------- 阶段 1：编译打包 ----------
FROM ${BUILD_BASE} AS build
WORKDIR /build

# 注入阿里云 Maven 源，mvn 默认读取 ~/.m2/settings.xml
COPY settings.xml /root/.m2/settings.xml

# 先只拷 pom 预热依赖（源码改动时命中缓存层，不重新下载全部依赖）
COPY pom.xml ./
RUN mvn -B -ntp -DskipTests dependency:go-offline || true

# 再拷源码打包；测试由 CI 单独跑（@SpringBootTest 需 MySQL 服务容器）
COPY src ./src
# 显式挑出可执行 fat jar（排除 Spring Boot repackage 生成的 *.jar.original）
RUN mvn -B -ntp -DskipTests package \
    && cp $(ls target/*.jar | grep -v '\.original$') /app.jar

# ---------- 阶段 2：运行 ----------
FROM ${RUNTIME_BASE} AS runtime

# 阶段内需重新声明，LABEL 才能取到该 ARG
ARG VCS_REF

WORKDIR /app

# 时区与业务一致（订单号/取餐码按 Asia/Shanghai 计算自然日）
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    # JVM 堆按容器内存限制取 75%（JVM 默认仅 25%），OOM 即退出交由重启策略拉起
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# 非 root 运行（Alpine 语法；若把 RUNTIME_BASE 覆盖成 Ubuntu 系镜像，
# 需改为 groupadd/useradd，且 HEALTHCHECK 的 wget 换成 apt 安装的 curl）
RUN addgroup -S spring && adduser -S spring -G spring
COPY --from=build --chown=spring:spring /app.jar /app/app.jar
USER spring

EXPOSE 8080

# alpine 的 busybox 自带 wget，无需额外安装；
# /actuator/health 含数据库连通性，DB 不可达会 unhealthy
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:8080/actuator/health || exit 1

LABEL org.opencontainers.image.title="milkTeaStore Backend" \
      org.opencontainers.image.description="奶茶点单系统后端服务（Spring Boot 4.1.1 / Java 25）" \
      org.opencontainers.image.source="https://github.com/CEXpp/milkTeaStore" \
      org.opencontainers.image.url="https://github.com/CEXpp/milkTeaStore" \
      org.opencontainers.image.documentation="https://github.com/CEXpp/milkTeaStore/blob/main/README.md" \
      org.opencontainers.image.license="MIT" \
      org.opencontainers.image.authors="lurmou" \
      org.opencontainers.image.vendor="milkTeaStore" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.revision="${VCS_REF}"

# sh -c + exec：既支持运行时 -e JAVA_OPTS 覆盖 JVM 参数（纯 exec 数组形式不做变量替换），
# 又让 java 接管 PID 1、收到 SIGTERM 优雅停机
# 不写死 profile：application.properties 默认 dev，换配置用 -e SPRING_PROFILES_ACTIVE=xxx
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
