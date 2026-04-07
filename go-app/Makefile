APP_NAME := ai-driven
BUILD_DIR := ./bin
GO := go
GOFLAGS := -v
LDFLAGS := -s -w

.PHONY: all build test lint coverage clean docker docker-arm fmt vet

VERSION := $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
BUILD_TIME := $(shell date -u '+%Y-%m-%dT%H:%M:%SZ')

all: lint test build

## Build
build:
	$(GO) build $(GOFLAGS) -ldflags="$(LDFLAGS)" -o $(BUILD_DIR)/server ./cmd/server
	$(GO) build $(GOFLAGS) -ldflags="$(LDFLAGS)" -o $(BUILD_DIR)/mcp-github ./cmd/mcpserver/github
	$(GO) build $(GOFLAGS) -ldflags="$(LDFLAGS)" -o $(BUILD_DIR)/mcp-jira ./cmd/mcpserver/jira
	$(GO) build $(GOFLAGS) -ldflags="$(LDFLAGS)" -o $(BUILD_DIR)/shadowcompare ./cmd/shadowcompare

## Test
test:
	$(GO) test ./... -race -count=1

test-coverage:
	$(GO) test ./... -race -count=1 -coverprofile=coverage.out -covermode=atomic
	$(GO) tool cover -func=coverage.out

coverage: test-coverage
	$(GO) tool cover -html=coverage.out -o coverage.html
	@echo "Coverage report: coverage.html"

## Lint
lint:
	golangci-lint run ./...

fmt:
	goimports -w .
	$(GO) fmt ./...

vet:
	$(GO) vet ./...

## Docker
docker:
	docker build \
		--build-arg VERSION=$(VERSION) \
		--build-arg BUILD_TIME=$(BUILD_TIME) \
		-t $(APP_NAME):latest .

docker-arm:
	docker build \
		--build-arg VERSION=$(VERSION) \
		--build-arg BUILD_TIME=$(BUILD_TIME) \
		--build-arg GOARCH=arm64 \
		-t $(APP_NAME):arm64 .

docker-run: docker
	docker run --rm -p 8080:8080 $(APP_NAME):latest

## Clean
clean:
	rm -rf $(BUILD_DIR) coverage.out coverage.html

## Dependencies
deps:
	$(GO) mod download
	$(GO) mod tidy

## Help
help:
	@echo "Available targets:"
	@echo "  build          - Build all binaries"
	@echo "  test           - Run tests with race detector"
	@echo "  test-coverage  - Run tests with coverage report"
	@echo "  coverage       - Generate HTML coverage report"
	@echo "  lint           - Run golangci-lint"
	@echo "  fmt            - Format code"
	@echo "  vet            - Run go vet"
	@echo "  docker         - Build Docker image (amd64)"
	@echo "  docker-arm     - Build Docker image (arm64/Graviton)"
	@echo "  clean          - Remove build artifacts"
	@echo "  deps           - Download and tidy dependencies"
