# Multi-Tenant Notification Platform

## 1. 项目介绍

面向企业内部业务系统的多租户统一消息通知平台，为不同租户和应用提供短信、邮件、站内信等统一发送能力，并通过消息队列实现异步处理、削峰填谷、失败重试、消费幂等、渠道路由和发送状态追踪。

## 2. 建设目标

- 提供统一的短信、邮件、站内信发送入口
- 支持多租户和多应用隔离
- 通过RocketMQ实现异步发送和削峰
- 支持消费幂等、失败重试和状态追踪
- 建立可监控、可压测、可部署的完整工程闭环

## 3. 首月范围

- 租户与应用管理
- 消息模板
- 单条和批量发送
- RocketMQ异步处理
- 消费幂等
- 失败重试
- Redis限流
- 短链
- Prometheus监控
- Docker Compose部署

## 4. 非目标范围

- 计费和套餐
- 复杂工作流
- 多厂商全面接入
- 独立短链微服务
- Kubernetes高可用集群

## 5. 总体架构

参见：[总体架构](docs/architecture.md)

## 6. 模块说明

## 7. 核心发送流程

## 8. 消息状态机

参见：[消息状态机](docs/message-state-machine.md)

## 9. 多租户隔离方案

参见：[多租户隔离方案](docs/tenant-isolation.md)

## 10. 技术栈

## 11. 数据库设计

参见：[数据库设计](docs/database-design.md)

## 12. 本地启动方式

### 1. 启动基础设施

```bash
docker compose -f deploy/docker-compose.yml up -d
```

### 2. 启动 Server

```aiignore
mvn -pl notification-server -am spring-boot:run
```

### 3. 启动 Worker

```aiignore
mvn -pl notification-worker -am spring-boot:run
```

### 4. 健康检查

```aiignore
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health
```

## 13. 项目路线图

## 14. 项目文档
