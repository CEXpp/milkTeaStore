#!/usr/bin/env bash
#
# M1 出口门禁冒烟入口（T16）：转调同目录 smoke.py。
# 说明：断言需要解析 JSON；Windows 开发机无 jq，故统一以 Python 实现解析与断言，
#       本脚本仅做跨平台入口包装（Git Bash / WSL / Linux / macOS 均可执行）。
#
# 用法:
#   bash scripts/smoke.sh [host:port]        # 默认 127.0.0.1:8080，全绿输出 M1_GATE_PASS
#
# 可配置环境变量见 scripts/smoke.py 头部注释（SMOKE_LOG_FILE / SMOKE_SKIP_TIMEOUT 等）。
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
  echo "smoke.sh: python3/python 未找到或不可用，无法执行冒烟断言" >&2
  exit 2
fi

exec "$PY" "$SCRIPT_DIR/smoke.py" "$@"
