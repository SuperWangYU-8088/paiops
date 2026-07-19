ARG DOCKER_HUB_MIRROR=m.daocloud.io/docker.io
FROM ${DOCKER_HUB_MIRROR}/library/maven:3.9.9-eclipse-temurin-21-alpine AS build

ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
ENV MAVEN_MIRROR_URL=${MAVEN_MIRROR_URL}

WORKDIR /build

# Maven 仓库使用 BuildKit 缓存卷；即使源码发生变化或上次构建中断，
# 已下载的依赖仍可复用，显著缩短后续构建时间。
COPY pom.xml .
COPY settings.xml /root/.m2/settings.xml
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -s /root/.m2/settings.xml clean package -DskipTests

ARG DOCKER_HUB_MIRROR=m.daocloud.io/docker.io
FROM ${DOCKER_HUB_MIRROR}/library/eclipse-temurin:21-jre-alpine

RUN addgroup -S paiops && adduser -S -G paiops paiops
WORKDIR /app

COPY --from=build /build/target/*.jar /app/paiops-backend.jar

USER paiops
EXPOSE 8084

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/paiops-backend.jar"]
