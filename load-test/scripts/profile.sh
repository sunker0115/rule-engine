#!/usr/bin/env bash
# 拐点平台期抓 30s flame graph。用法：./profile.sh <app_pid> [cpu|alloc|lock]
# 需先装 async-profiler（asprof）：https://github.com/async-profiler/async-profiler
set -euo pipefail
PID="${1:?usage: profile.sh <pid> [cpu|alloc|lock]}"
EVENT="${2:-cpu}"
OUT="/tmp/loadtest-flame-${EVENT}-$(date +%H%M%S).html"
asprof -d 30 -e "${EVENT}" -f "${OUT}" "${PID}"
echo "saved ${OUT}"
