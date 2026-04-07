# ---- Build stage ----
FROM golang:1.24-alpine AS build

ARG VERSION=dev
ARG BUILD_TIME=unknown
ARG GOARCH=amd64

WORKDIR /app

# Cache dependency downloads
COPY go.mod go.sum ./
RUN go mod download

# Build a statically linked binary with version info
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${GOARCH} go build \
    -ldflags="-s -w -X main.version=${VERSION} -X main.buildTime=${BUILD_TIME}" \
    -o /server ./cmd/server

# ---- Runtime stage ----
FROM scratch

# OCI image metadata
LABEL org.opencontainers.image.title="ai-driven" \
      org.opencontainers.image.description="AI-Driven agent service" \
      org.opencontainers.image.source="https://github.com/AirdropToTheMoon/ai-driven" \
      org.opencontainers.image.version="${VERSION}"

# Copy CA certificates and timezone data from build stage
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /usr/share/zoneinfo /usr/share/zoneinfo

# Copy the statically linked binary
COPY --from=build /server /server

# Run as non-root user (numeric UID since scratch has no /etc/passwd)
USER 65534

EXPOSE 8080

ENTRYPOINT ["/server"]
