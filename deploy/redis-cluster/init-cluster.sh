#!/usr/bin/env bash
set -euo pipefail
#
password="${REDIS_CLUSTER_PASSWORD:-notification123}"
ports=(7001 7002 7003 7004 7005 7006)

for port in "${ports[@]}"; do
  container="notification-redis-${port}"

  # 不把密码放在 redis-cli 参数中，避免命令行警告和进程列表泄漏。
  until docker exec \
      -e REDISCLI_AUTH="${password}" \
      "${container}" \
      redis-cli -p "${port}" PING >/dev/null; do
    echo "waiting for redis ${port}"
    sleep 1
  done
done

# 已经初始化成功时直接退出，使脚本可重复执行。
if docker exec \
    -e REDISCLI_AUTH="${password}" \
    notification-redis-7001 \
    redis-cli -p 7001 CLUSTER INFO \
    | grep -q 'cluster_state:ok'; then
  echo "redis cluster is already ready"
  exit 0
fi

docker exec \
  -e REDISCLI_AUTH="${password}" \
  notification-redis-7001 \
  redis-cli --cluster create \
  127.0.0.1:7001 \
  127.0.0.1:7002 \
  127.0.0.1:7003 \
  127.0.0.1:7004 \
  127.0.0.1:7005 \
  127.0.0.1:7006 \
  --cluster-replicas 1 \
  --cluster-yes

docker exec \
  -e REDISCLI_AUTH="${password}" \
  notification-redis-7001 \
  redis-cli -p 7001 CLUSTER INFO
