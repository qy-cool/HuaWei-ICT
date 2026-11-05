# cloide-shell Agent 多阶段构建：Go 交叉编译 → alpine 运行（非 root）
# 遵循 docker-standards：官方镜像固定版本、CGO_ENABLED=0 静态编译、专用用户。

# 构建阶段
FROM golang:1.23-alpine AS builder
WORKDIR /build
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o /out/cloide-agent ./cmd/cloide-agent

# 运行阶段
FROM alpine:3.20
RUN addgroup -S cloide && adduser -S -G cloide cloide \
    && mkdir -p /var/lib/cloide \
    && chown -R cloide:cloide /var/lib/cloide
COPY --from=builder /out/cloide-agent /usr/local/bin/cloide-agent
USER cloide
ENTRYPOINT ["cloide-agent"]
