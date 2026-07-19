ARG DOCKER_HUB_MIRROR=m.daocloud.io/docker.io
FROM ${DOCKER_HUB_MIRROR}/library/golang:1.24-alpine AS build

ARG GOPROXY=https://goproxy.cn,direct
ENV GOPROXY=${GOPROXY}

WORKDIR /build
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" \
    -o /out/paiops-worker ./cmd/paiops-worker

ARG DOCKER_HUB_MIRROR=m.daocloud.io/docker.io
FROM ${DOCKER_HUB_MIRROR}/library/alpine:3.21

ARG ALPINE_MIRROR=https://mirrors.aliyun.com/alpine

# Alpine 默认软件源在部分网络中响应较慢，先切换到可配置的国内镜像。
RUN sed -i "s#https://dl-cdn.alpinelinux.org/alpine#${ALPINE_MIRROR}#g" /etc/apk/repositories \
    && addgroup -S paiops && adduser -S -G paiops paiops \
    && apk add --no-cache ca-certificates

COPY --from=build /out/paiops-worker /usr/local/bin/paiops-worker

USER paiops
ENTRYPOINT ["/usr/local/bin/paiops-worker"]
