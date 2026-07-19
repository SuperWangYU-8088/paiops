ARG DOCKER_HUB_MIRROR=m.daocloud.io/docker.io
FROM ${DOCKER_HUB_MIRROR}/library/node:22-alpine AS build

ARG NPM_REGISTRY=https://registry.npmmirror.com

WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci --registry=${NPM_REGISTRY}

COPY . .
RUN npm run build

ARG DOCKER_HUB_MIRROR=m.daocloud.io/docker.io
FROM ${DOCKER_HUB_MIRROR}/library/nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
