#!/usr/bin/env bash
# 在本次独占网络和 MySQL 中验证最终镜像；不连接宿主数据库或挂载已有数据。
set -euo pipefail

image="${1:?用法：bash scripts/verify_mysql_image.sh <已构建的应用镜像>}"
docker image inspect "$image" >/dev/null
run_name="rule-mysql-smoke-$$-$RANDOM"
password="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
network_id=''
mysql_id=''
app_id=''

cleanup() {
  result=$?
  trap - EXIT
  # 只使用 docker create 返回的 ID，创建失败时不得清理同名的已有资源。
  for container_id in "$app_id" "$mysql_id"; do
    if [[ -n "$container_id" ]]; then
      if [[ "$result" -ne 0 ]]; then
        docker logs --tail 100 "$container_id" || true
      fi
      docker stop --time 10 "$container_id" >/dev/null || result=1
      docker rm --volumes "$container_id" >/dev/null || result=1
    fi
  done
  if [[ -n "$network_id" ]]; then
    docker network rm "$network_id" >/dev/null || result=1
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

network_id="$(docker network create "$run_name")"
mysql_id="$(docker create --name "$run_name-mysql" --network "$network_id" --network-alias mysql \
  -e MYSQL_ROOT_PASSWORD="$password" -e MYSQL_DATABASE=rule_engine_smoke \
  -e MYSQL_USER=smoke -e MYSQL_PASSWORD="$password" mysql:8.4)"
docker start "$mysql_id" >/dev/null
mysql_ready=false
for attempt in {1..60}; do
  if docker exec -e MYSQL_PWD="$password" "$mysql_id" mysql --connect-timeout=2 \
    -h127.0.0.1 -usmoke rule_engine_smoke -e 'SELECT 1' >/dev/null 2>&1; then
    mysql_ready=true
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$mysql_id")" != true ]]; then
    break
  fi
  sleep 2
done
if [[ "$mysql_ready" != true ]]; then
  echo '临时 MySQL 未就绪，镜像验证失败。' >&2
  exit 1
fi

app_id="$(docker create --name "$run_name-app" --network "$network_id" \
  -p 127.0.0.1::8080 \
  -e 'SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/rule_engine_smoke?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true' \
  -e SPRING_DATASOURCE_USERNAME=smoke -e SPRING_DATASOURCE_PASSWORD="$password" \
  -e MANAGEMENT_OPENTELEMETRY_METRICS_EXPORT_OTLP_ENABLED=false \
  -e MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENABLED=false \
  -e MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENABLED=false "$image")"
docker start "$app_id" >/dev/null
address="$(docker port "$app_id" 8080/tcp)"
base_url="http://$address"
for attempt in {1..60}; do
  if curl --fail --silent --connect-timeout 2 --max-time 5 "$base_url/actuator/health" \
      | jq -e '.status == "UP"' >/dev/null \
    && curl --fail --silent --connect-timeout 2 --max-time 5 "$base_url/admin/v1/tenants" \
      | jq -e '.success == true and (.data | any(.code == "SYSTEM"))' >/dev/null; then
    echo '镜像 MySQL 验证通过：默认配置健康为 UP，SYSTEM 租户可读取。'
    exit 0
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$app_id")" != true ]]; then
    break
  fi
  sleep 2
done
echo '应用未就绪或健康/租户断言失败。' >&2
exit 1
