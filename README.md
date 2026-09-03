这是一款基于Netty实现的弹幕消息推送中台项目，具体的架构图：
<img width="2396" height="959" alt="b43ccbee6a27b3e7d2559324f4d22ea0" src="https://github.com/user-attachments/assets/e8cea824-106b-4497-b4fd-c34117c55eb9" />

其中对于上行流量计划使用gateway进行管理，将流量分给中台的不同节点，节点将消息发送给直播的业务层做业务处理，例如弹幕风控、账号经验增加等；业务层将消息传回中台后，根据中台维护的目标用户uid或者直播间zid与机器id的映射，转发到对应的机器上完成发送。

自测环节：
笔记本8G可用内存，启动nacos、server以及单push。
阿里云服务器2G4核，搭载kafka、redis、mysql
使用就jmeter进行压测，然后通过redis中的key得出目前配置最大连接数为6000+
<img width="1029" height="764" alt="image" src="https://github.com/user-attachments/assets/517565fa-8c23-4c25-93e5-054c97aa6148" />

日志落库：
- 建表 SQL：`BulletPushCommon/src/main/resources/sql/push_log.sql`
- MySQL 配置支持 JVM 参数：`push.log.mysql.url`、`push.log.mysql.user`、`push.log.mysql.password`
- MySQL 配置支持环境变量：`PUSH_LOG_MYSQL_URL`、`PUSH_LOG_MYSQL_USER`、`PUSH_LOG_MYSQL_PASSWORD`
- 节点名可通过环境变量 `PUSH_NODE_NAME` 配置

使用方式：
```java
private static final Log log = LogFactory.getLog(CurrentClass.class);

log.Info("server started, port:{}", port);
log.Warn("heartbeat timeout, channelId:{}", channelId);
log.Error("push message failed, msgId:{}", msgId, exception);
```

日志查询：
```java
PushLogRepository repository = new PushLogRepository();
List<PushLogRecord> records = repository.queryByMachineId(
        new PushLogSearchParam()
                .setMachineId(1)
                .setLevel(LogLevel.ERROR)
                .setKeyword("Timeout")
                .setLimit(100)
                .setOffset(0)
);
```

管理后台点击节点实例时，查询条件应使用该节点的 `machine_id` 对应 `push_log.machine_id`。

管理接口：
- 推送服务启动后默认监听 `9090`，可通过 `-Dpush.admin.port=...` 或 `PUSH_ADMIN_PORT` 修改。
- `GET /admin/api/nodes`：当前运行实例的真实 `NodeMetrics`。
- `GET /admin/api/logs?mode=logId&logId=...`：按 `push_log.trace_id` 查询真实日志。
- `GET /admin/api/logs?mode=machineId&machineId=...`：按 `push_log.machine_id` 查询真实日志。
- `GET /admin/api/log-analysis?logId=...&model=auto`：真实 LogID 工具调用分析。
- `GET /admin/api/node-analysis?model=auto`：当前实例真实节点分析。
- `GET /admin/api/client-nodes`：用户客户端可随机选择的真实 WebSocket 节点。

前端默认请求 `http://localhost:9090/admin/api`。需要部署到其他地址时，配置：

```bash
VITE_PUSH_ADMIN_API_BASE_URL=http://host:port/admin/api
```

管理接口使用 JDK `HttpServer`，运行 Java 进程时需确保启用 `jdk.httpserver` 模块。

Redis 配置：

```bash
# 默认连接 47.102.43.148:6379；若 Redis 开启认证，必须由部署环境提供密码。
PUSH_REDIS_HOST=47.102.43.148
PUSH_REDIS_PORT=6379
PUSH_REDIS_PASSWORD=your-redis-password
```

用户 WebSocket 客户端：
- 前端目录：`BulletPushUserClient`
- Gateway 默认监听端口：`8082`，WebSocket 路径：`/ws`
- 用户消息仅在 Kafka 上行 Topic 确认写入后返回 `MESSAGE_ACCEPTED`
- Kafka、对外节点地址未配置时，Gateway 会明确拒绝消息或用户客户端显示无可用节点

Gateway 配置：

```bash
# 当前 Kafka 集群，无认证。
PUSH_KAFKA_BOOTSTRAP_SERVERS=172.23.218.49:9092
PUSH_KAFKA_SECURITY_PROTOCOL=PLAINTEXT

# 浏览器 WebSocket 上行消息写入此 Topic。
PUSH_KAFKA_UPSTREAM_TOPIC=message_in
# 所有推送节点共用该 Consumer Group，确保每条下行消息只由一个节点从 Kafka 获取。
PUSH_KAFKA_DOWNSTREAM_TOPIC=message_out
PUSH_KAFKA_DOWNSTREAM_CONSUMER_GROUP=push-message-out-consumer
PUSH_WEBSOCKET_PORT=8082

# 本地启动默认使用 ws://localhost:8082/ws；多节点部署时覆盖为真实节点列表。
PUSH_WEBSOCKET_NODE_ENDPOINTS=ws://10.0.1.101:8082/ws,ws://10.0.1.102:8082/ws
```

以上 Kafka 参数已作为 Gateway 默认值写入代码；环境变量或 JVM 参数
`push.kafka.bootstrapServers`、`push.kafka.securityProtocol`、`push.kafka.upstreamTopic`
可在不同环境中覆盖。未配置 `PUSH_WEBSOCKET_NODE_ENDPOINTS` 时，Gateway 默认对外公布
`ws://localhost:8082/ws`。

下行路由：
- 推送节点消费 `message_out` 时共用 `push-message-out-consumer`，一条消息只由一个节点消费。
- WebSocket 客户端首次向房间发送消息时，推送节点将该房间成员与节点租约登记到 Redis。
- 消费节点在本机有该房间连接时直接广播；目标在其他节点时发布到对应机器的 Redis 专属频道。
- 目标节点收到专属频道消息后向本机房间连接发送 `MESSAGE_DELIVERED`。
- 房间节点租约默认 90 秒，每 30 秒续租，可通过 `PUSH_ROOM_ROUTE_LEASE_SECONDS`、
  `PUSH_ROOM_ROUTE_REFRESH_SECONDS` 调整。

业务 Service：
- 模块：`BulletPushService`
- 启动入口：`com.GinElmaC.PushServiceStart`
- 所有 Service 实例使用 Consumer Group `push-business-service` 消费 `message_in`
- 当前处理器仅打印消息，并在 Kafka 确认写入后将原始消息体写入 `message_out`
- 写入失败时不提交 `message_in` offset，消息会按至少一次语义重试

业务 Service Kafka 配置：

```bash
PUSH_KAFKA_BOOTSTRAP_SERVERS=47.102.43.148:9092
PUSH_KAFKA_SECURITY_PROTOCOL=PLAINTEXT
PUSH_KAFKA_INPUT_TOPIC=message_in
PUSH_KAFKA_OUTPUT_TOPIC=message_out
PUSH_KAFKA_BUSINESS_CONSUMER_GROUP=push-business-service
```


用户前端需要使用其他管理接口地址时，配置：

```bash
VITE_PUSH_ADMIN_API_BASE_URL=http://host:9090/admin/api
```
