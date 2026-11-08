#!/usr/bin/env bash
#
# AI 点单升级验收入口（流式链路 + 口味记忆闭环）：转调同目录 ai-smoke.py。
# 说明：断言需要解析 JSON 与 SSE 事件流；Windows 开发机无 jq，故统一以 Python 实现，
#       本脚本仅做跨平台入口包装（Git Bash / WSL / Linux / macOS 均可执行）。
#
# 用法:
#   bash scripts/ai-smoke.sh [host:port]        # 默认 127.0.0.1:8080，全绿输出 AI_GATE_PASS
#
# 可配置环境变量见 scripts/ai-smoke.py 头部注释
#   （AI_SMOKE_OPENID / AI_SMOKE_MESSAGE / AI_SMOKE_SKIP_AI / SMOKE_LOG_FILE）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 依次探测 python3 / python，并以 --version 校验真实可用——
# Windows 的 WindowsApps 目录可能带不可用的 python3 Store 别名（stub），须跳过。
PY=""
for cand in python3 python; do
  if command -v "$cand" >/dev/null 2>&1 && "$cand" --version >/dev/null 2>&1; then
    PY="$cand"
    break
  fi
done

if [ -z "$PY" ]; then
  echo "ai-smoke.sh: python3/python 未找到或不可用，无法执行验收断言" >&2
  exit 2
fi

exec "$PY" "$SCRIPT_DIR/ai-smoke.py" "$@"
