# ============================================================
# hltgq-device Docker 镜像（ARM64 / 银河麒麟）
# ============================================================
# 构建方式（在 ARM64 服务器上执行）：
#   1. 将 hltgq-device-0.0.1-SNAPSHOT.jar 放到此目录
#   2. docker build -t hltgq-device:latest .
#   3. docker run -d -p 18686:18686 --name hltgq-device hltgq-device:latest
# ============================================================

FROM eclipse-temurin:8-jre-jammy

LABEL maintainer="hltgq"
LABEL description="大华ICC视频监控代理服务（含ffmpeg H.265转码）"

# ----- 安装 ffmpeg（H.265→H.264 实时转码依赖）-----
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# ----- 设置时区 -----
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# ----- 应用目录 -----
WORKDIR /app

# ----- 复制 JAR（在服务器端 docker build 前把 JAR 放到同目录）-----
COPY hltgq-device-0.0.1-SNAPSHOT.jar /app/app.jar

# ----- 暴露端口 -----
EXPOSE 18686

# ----- 启动命令 -----
# JVM 参数说明：
#   -XX:+UseContainerSupport  容器内存感知
#   -XX:MaxRAMPercentage=75   堆内存上限=容器内存的75%
#   -Dfile.encoding=UTF-8     文件编码
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Dfile.encoding=UTF-8", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/app.jar"]
