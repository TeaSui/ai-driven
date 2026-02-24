# Zeno Claude CLI

CLI tool để cài đặt Claude Code phiên bản mới nhất và tự động cấu hình với Zeno360.

## Cài đặt

```bash
bunx zeno-claude-cli
```

hoặc

```bash
npx zeno-claude-cli
```

Tool sẽ yêu cầu bạn nhập Auth Token khi chạy.

## Tính năng

- Tự động cài đặt Claude Code phiên bản mới nhất
- Cấu hình sẵn Zeno360 API endpoint
- Sử dụng claude-opus-4-6 cho tất cả models
- Cho phép người dùng nhập Auth Token riêng

## Phát triển

```bash
# Cài đặt dependencies
bun install

# Test local
node index.js

# Build executable
bun run build
```

## Cấu hình

Tool sẽ tự động ghi đè cấu hình tại `~/.claude/settings.json` với:

- Base URL: http://zeno360.click
- Auth Token: Người dùng tự nhập
- Tất cả models: claude-opus-4-6
- Experimental features enabled
