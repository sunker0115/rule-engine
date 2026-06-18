#!/usr/bin/env bash
# tenantId 统一 Long 的端到端验证。
# 目标:① 非数字 tenantId / 非数字 @PathVariable id → 400 INVALID_ARGUMENT（不再兜底 500）
#       ② 合法数字 tenantId 的 admin 只读接口仍 200（回归：改类型没把正常路径改坏）
#       ③ 评估 API（tenantCode 维度，保持 String）不受影响、不 500
# 前置：服务在 :8080；租户 9001(code=loadtest) 存在；scene demo.login 存在（payload 需 amount+country）。
# 用法：bash docs/examples/tenant-id-e2e-check.sh
set -u
BASE=${BASE:-http://localhost:8080}
PASS=0; FAIL=0

# check <desc> <expected_http> <expected_errcode|-> <curl args...>
check() {
  local desc="$1" exp_http="$2" exp_err="$3"; shift 3
  local resp http body err
  resp=$(curl -s -w $'\n%{http_code}' "$@")
  http=$(printf '%s' "$resp" | tail -1)
  body=$(printf '%s' "$resp" | sed '$d')
  err=$(printf '%s' "$body" | python3 -c "import sys,json;print(json.load(sys.stdin).get('errorCode') or '-')" 2>/dev/null || echo '?')
  if [ "$http" = "$exp_http" ] && { [ "$exp_err" = "-" ] || [ "$err" = "$exp_err" ]; }; then
    echo "PASS  [$http $err] $desc"; PASS=$((PASS+1))
  else
    echo "FAIL  [$http $err]（期望 $exp_http $exp_err）$desc"; FAIL=$((FAIL+1))
  fi
}

echo "=== A. 非数字 tenantId → 400 INVALID_ARGUMENT（核心：不再 500） ==="
check "scenes     非数字 tenantId" 400 INVALID_ARGUMENT "$BASE/admin/v1/scenes?tenantId=acme"
check "rules      非数字 tenantId" 400 INVALID_ARGUMENT "$BASE/admin/v1/rules?tenantId=acme"
check "metrics    非数字 tenantId" 400 INVALID_ARGUMENT "$BASE/admin/v1/metrics?tenantId=acme"
check "jobs       非数字 tenantId" 400 INVALID_ARGUMENT "$BASE/admin/v1/jobs?tenantId=acme"
check "connectors 非数字 tenantId" 400 INVALID_ARGUMENT "$BASE/admin/v1/connectors?tenantId=acme"
check "decisions  非数字 tenantId" 400 INVALID_ARGUMENT "$BASE/admin/v1/decisions?tenantId=acme"

echo "=== B. @PathVariable Long id 非数字 → 400（同一 handler 兜底） ==="
check "job id 非数字"  400 INVALID_ARGUMENT "$BASE/admin/v1/jobs/abc?tenantId=9001"

echo "=== C. 合法数字 tenantId → 200（回归：正常路径没坏） ==="
check "scenes    合法" 200 - "$BASE/admin/v1/scenes?tenantId=9001"
check "rules     合法" 200 - "$BASE/admin/v1/rules?tenantId=9001"
check "metrics   合法" 200 - "$BASE/admin/v1/metrics?tenantId=9001"
check "decisions 合法" 200 - "$BASE/admin/v1/decisions?tenantId=9001"
check "jobs      合法" 200 - "$BASE/admin/v1/jobs?tenantId=9001"

echo "=== D. 评估 API（tenantCode 维度保持 String，不应 500） ==="
EVAL_BODY='{"tenantCode":"loadtest","sceneCode":"demo.login","eventType":"login","subjectId":"u1","eventId":"tid-check","payload":{"amount":1,"country":"CN"}}'
resp=$(curl -s -w $'\n%{http_code}' -X POST "$BASE/api/v1/rule/evaluate" -H "Content-Type: application/json" -d "$EVAL_BODY")
http=$(printf '%s' "$resp" | tail -1)
if [ "$http" != "500" ]; then echo "PASS  [$http] 评估 API 非 500"; PASS=$((PASS+1)); else echo "FAIL  [500] 评估 API 返回 500"; FAIL=$((FAIL+1)); fi

echo ""
echo "==== 汇总: PASS=$PASS  FAIL=$FAIL ===="
[ "$FAIL" -eq 0 ]
