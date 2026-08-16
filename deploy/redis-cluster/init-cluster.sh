#!/usr/bin/env bash
set -euo pipefail
#
password="${REDIS_CLUSTER_PASSWORD:-notification123}"
ports=(7001 7002 7003 7004 7005 7006)

# 默认最多检查 60 次。允许通过环境变量缩短次数，方便测试失败场景。
max_attempts="${REDIS_CLUSTER_MAX_ATTEMPTS:-60}"
retry_interval_seconds=1

# 防止传入 0、负数或字符串，导致下面的算术比较出错。
if ! [[ "${max_attempts}" =~ ^[1-9][0-9]*$ ]]; then
  echo "REDIS_CLUSTER_MAX_ATTEMPTS 必须是正整数" >&2
  exit 2
fi

for port in "${ports[@]}"; do
  container="notification-redis-${port}"
  attempt=1
  response=""

  while true; do
    # 命令输出和错误信息都保存到 response。
    # 不能只检查 docker exec 的退出状态，因为 redis-cli 遇到认证错误时
    # 也可能正常退出，所以还必须判断返回内容是不是 PONG。
    if response="$(
      docker exec \
        -e REDISCLI_AUTH="${password}" \
        "${container}" \
        redis-cli -p "${port}" PING 2>&1
    )" && [[ "${response}" == "PONG" ]]; then
      echo "redis ${port} is ready"
      break
    fi

    # 已经达到最大次数，不再无限等待。
    if (( attempt >= max_attempts )); then
      echo "redis ${port} 启动检查超时，共尝试 ${attempt} 次" >&2
      echo "最后一次响应：${response:-<无响应>}" >&2

      # 容器存在并且 Docker 可访问时，输出状态及最后 50 行日志。
      if docker inspect "${container}" >/dev/null 2>&1; then
        docker inspect \
          --format='container status={{.State.Status}}, error={{.State.Error}}' \
          "${container}" >&2

        # 即使日志读取失败，也不能掩盖真正的超时原因。
        docker logs --tail 50 "${container}" >&2 || true
      else
        echo "无法查询容器 ${container}：容器可能不存在，或 Docker 不可访问" >&2
      fi

      # 非零状态表示脚本执行失败，CI、终端或其他脚本都能感知。
      exit 1
    fi

    echo "waiting for redis ${port} (${attempt}/${max_attempts})"
    sleep "${retry_interval_seconds}"

    # 不建议在 set -e 下写 ((attempt++))，原因见下文。
    attempt=$((attempt + 1))
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
