###############################################################################
# 后端镜像：两阶段构建，全部使用你 ACR 里的基础镜像
#   构建阶段：jujube/maven:3.9-eclipse-temurin-25-alpine（Maven 3.9 + JDK 25）
#   运行阶段：jujube/eclipse-temurin:25-jre-alpine（仅 JRE 25，镜像小）
#
# 构建（上下文必须是 backend 目录）：
#   docker build -t <后端镜像名>:<tag> ./backend
###############################################################################

# ---------- 阶段 1：编译打包 ----------
FROM crpi-0ocfwxlhi33l7izj.cn-guangzhou.personal.cr.aliyuncs.com/jujube/maven:3.9-eclipse-temurin-25-alpine AS build

WORKDIR /build

# 先把阿里云 Maven 源注入构建容器
COPY settings.xml /root/.m2/settings.xml

# 先只拷 pom 下依赖：源码改动时不会重新下载全部依赖（层缓存）
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

# 再拷源码并打包（测试由 CI 单独跑，构建镜像时跳过）
COPY src ./src
RUN mvn -B -ntp package -DskipTests

# ---------- 阶段 2：运行 ----------
FROM crpi-0ocfwxlhi33l7izj.cn-guangzhou.personal.cr.aliyuncs.com/jujube/eclipse-temurin:25-jre-alpine

# 时区与业务配置一致（订单号 / 取餐码按 Asia/Shanghai 计算自然日）
ENV TZ=Asia/Shanghai

# JVM 堆按容器内存限制自适应（默认用限制值的 75%）
# 服务器只有 2 核 2G 时建议 docker run 加 -m 1g，可用 -e JAVA_OPTS="..." 追加参数
ENV JAVA_OPTS=""

# 非 root 运行
RUN addgroup -S spring && adduser -S spring -G spring
USER spring

WORKDIR /app
COPY --from=build /build/target/*.jar app.jar

# 项目未配置 server.port，Spring Boot 默认 8080
EXPOSE 8080

# 健康检查走 /actuator/health（含数据库连通性，数据库不可达会报 unhealthy）
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:8080/actuator/health || exit 1

# exec 让 java 接管 PID 1，能收到 SIGTERM 优雅停机
# 不写死 profile：application.properties 里默认 dev，需要换配置时用 -e SPRING_PROFILES_ACTIVE=xxx 覆盖
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
