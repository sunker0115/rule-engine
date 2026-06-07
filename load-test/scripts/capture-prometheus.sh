#!/usr/bin/env bash
# 抓一帧关键指标（高 VU 平台期跑）。用法：./capture-prometheus.sh [baseUrl]
set -euo pipefail
BASE="${1:-http://localhost:8080}"
TS=$(date +%H%M%S)
OUT="/tmp/loadtest-metrics-${TS}.txt"
curl -s "${BASE}/actuator/prometheus" | grep -E \
  'hikaricp_connections_active|hikaricp_connections_pending|hikaricp_connections_idle|hikaricp_connections_max|jvm_gc_pause_seconds_count|executor_active_threads|executor_queued_tasks' \
  | grep -v '#' | sort > "${OUT}"
echo "saved ${OUT}"
grep -E 'hikaricp_connections_(active|pending|idle|max)' "${OUT}" || echo "(no hikaricp metrics — 确认 -Dmanagement.endpoints.web.exposure.include=prometheus,health)"
