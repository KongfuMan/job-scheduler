# HTTP 任务执行服务

该服务使用 Spring Boot、PostgreSQL 和可选的 Citus 扩展，保存任务定义并异步向目标地址发送 JSON。API 和工作线程运行在同一个进程中。以下内容以 `src/main` 中的现有实现为准。

## 本地数据库初始化

以下示例假设本地 Citus 协调节点已在 `localhost:5442` 运行，且 `postgres` 用户有创建数据库和扩展的权限。使用独立的 `job_scheduler` 数据库；**不要在已有的 `postgres` 数据库中执行迁移或创建业务表**。先确认目标数据库名未被使用：

```sh
psql -h localhost -p 5442 -U postgres -d postgres -l
```

仅当 `job_scheduler` 不存在时创建它，然后在新数据库中启用 Citus：

```sh
psql -h localhost -p 5442 -U postgres -d postgres -v ON_ERROR_STOP=1 -c 'CREATE DATABASE job_scheduler'
psql -h localhost -p 5442 -U postgres -d job_scheduler -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION IF NOT EXISTS citus'
```

第一条命令连接 `postgres` 仅用于创建独立数据库，不修改其 schema。Citus 必须已安装并在服务器端正确配置；如使用多节点集群，还需事先配置 worker 节点。不要删除或重建已有数据库来运行这些命令。如果不启用 Citus，迁移会创建普通 PostgreSQL 表。

应用启动时 Flyway 自动执行 `src/main/resources/db/migration` 中的 V1，创建业务表和 Flyway 历史表；检测到 Citus 扩展时，两个业务表会转换为引用表。应使用对专用数据库具有足够迁移权限的账号。不要将连接指向现有应用的数据库或 schema。

V1 已合并早期开发版的 V2 和 V3。如果数据库已经执行过旧版迁移，Flyway 校验会失败；请改用全新的专用数据库。原数据库不会自动删除或修改，保留数据时需单独规划迁移。

V1 约束状态、尝试次数、租约与重试时间的一致性，以及非空创建时间、非空白标识、非负 fencing token、幂等键长度和对象类型的 metadata。数据库不验证完整 URL 格式或身份，也不限制租约必须位于未来：过期租约需要被恢复。SQL 约束只检查行当前状态，不保证状态转换历史或时间戳单调递增。修改过 V1 的已有数据库同样需要单独规划迁移，不能直接依靠重新执行 V1 升级。

## 测试

`mvn clean test` 默认运行无需数据库的测试。可选的数据库完整性测试只在显式设置 `JOBS_CONSTRAINT_TEST_URL` 时运行；请使用新建的独立临时数据库，不要指向共享或生产数据库。测试在事务中执行 V1 和约束测试并回滚，不删除数据库或表；目标搜索路径中已有业务表时会拒绝运行。Citus 测试需预先在该数据库启用扩展并配置所需节点。连接通过 `JOBS_CONSTRAINT_TEST_USER` 和 `JOBS_CONSTRAINT_TEST_PASSWORD` 配置，默认为 `postgres`。

```sh
JOBS_CONSTRAINT_TEST_URL='jdbc:postgresql://localhost:5442/disposable_test' mvn test
```

## 启动

默认连接为 `jdbc:postgresql://localhost:5442/job_scheduler`，用户名和密码均为 `postgres`。可通过 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 覆盖，例如：

```sh
export DB_URL='jdbc:postgresql://localhost:5442/job_scheduler'
export DB_USERNAME='postgres'
export DB_PASSWORD='postgres'
```

需要 Java 17 或更高版本和 Maven。在项目目录运行：

```sh
mvn spring-boot:run
```

默认 HTTP 端口为 Spring Boot 的 `8080`，可用 `--server.port=8081` 覆盖。下面示例使用 `http://localhost:8080`。启动进程默认也会启动 worker；仅运行 API 可添加 `--jobs.worker.enabled=false`。

## API

请求和响应使用 JSON；ID 为 UUID。创建者当前固定为 `unauthenticated`，不代表已验证身份。

| 方法和路径 | 功能 | 成功响应 |
| --- | --- | --- |
| `POST /v1/jobs` | 创建任务定义 | `201`，`Location: /v1/jobs/{jobId}`，返回任务 |
| `PUT /v1/jobs/{jobId}` | 替换任务名称、元数据和目标 URL | `200`，返回任务；不存在时 `404` |
| `GET /v1/jobs/{jobId}` | 查询任务 | `200`；不存在时 `404` |
| `POST /v1/executions` | 创建或幂等重放执行 | `202`，`Location: /v1/executions/{executionId}`，返回执行 |
| `GET /v1/executions/{executionId}` | 查询执行 | `200`；不存在时 `404` |

任务请求包含非空白 `jobName`、可省略的对象 `metadata`（省略时为 `{}`）及 `targetUrl`。字符串和 JSON 键值不能包含 NUL 字符。URL 必须是带主机名的绝对 HTTP(S) URL，不含用户信息、首尾空白，长度不超过 2048。更新是完整替换，省略 `metadata` 会重置为 `{}`。任务响应字段为 `jobId`、`jobName`、`metadata`、`targetUrl`、`createdAt`、`createdBy`。

执行请求包含 `jobId`、非 null 的 JSON `payload` 和可选的正整数 `maxAttempts`（默认 3，包含首次尝试）。必须提供 `Idempotency-Key` 请求头：非空白、最多 255 个字符且无首尾空白。该键在所有执行之间全局唯一；同一键和相同的 `jobId`、JSON payload、有效 `maxAttempts` 重放时返回已有执行，参数不同返回 `400`。任务不存在且没有对应键时返回 `404`。输入校验失败返回 `400`。

执行响应包含 `executionId`、`jobId`、`payload`、`status`、`attempts`、`maxAttempts`、`nextRetryAt`、`leaseUntil`、`workerId`、`fencingToken`、`externalIdempotencyKey`、`lastError`、`updatedAt`、`createdAt`、`createdBy`。新执行的外部键独立随机生成，与客户端键分开存储；重放和重试均保持不变。状态为 `PENDING`、`RUNNING`、`RETRY`、`SUCCEEDED` 或 `FAILED`；可通过查询接口轮询结果。

### 调用示例

目标地址应为受信任且从 worker 可访问的 HTTP 服务。下面的端口 `9000` 需要另行提供接收服务；执行 ID 和任务 ID 请替换为实际响应中的值。

```sh
curl -i -X POST http://localhost:8080/v1/jobs \
  -H 'Content-Type: application/json' \
  -d '{"jobName":"示例任务","metadata":{"team":"ops"},"targetUrl":"http://localhost:9000/webhook"}'

curl -i http://localhost:8080/v1/jobs/JOB_UUID

curl -i -X PUT http://localhost:8080/v1/jobs/JOB_UUID \
  -H 'Content-Type: application/json' \
  -d '{"jobName":"更新后的任务","metadata":{"team":"ops"},"targetUrl":"http://localhost:9000/webhook"}'

curl -i -X POST http://localhost:8080/v1/executions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: example-execution-001' \
  -d '{"jobId":"JOB_UUID","payload":{"message":"hello"},"maxAttempts":3}'

curl -i http://localhost:8080/v1/executions/EXECUTION_UUID
```

## Worker 配置与投递

通过 Spring 配置属性或命令行参数设置以下属性，例如 `--jobs.worker.concurrency=8`。时间单位均为毫秒。

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `jobs.worker.enabled` | `true` | 是否启动 worker |
| `jobs.worker.id` | `worker-` 加随机 UUID | worker 标识，不能为空白或包含 NUL，最多 255 个字符 |
| `jobs.worker.concurrency` | `4` | 每个进程同时执行的最大数量，至少 1 |
| `jobs.worker.poll-interval-ms` | `1000` | 轮询间隔，至少 1 |
| `jobs.worker.lease-ms` | `30000` | 租约时长，至少 2 |
| `jobs.worker.heartbeat-interval-ms` | `10000` | 续租间隔，至少 1 且小于租约时长 |
| `jobs.worker.retry-delay-ms` | `1000` | 首次失败后的重试延迟，至少 1 |
| `jobs.worker.max-retry-delay-ms` | `300000` | 重试延迟上限，不小于初始延迟 |
| `jobs.worker.http-timeout-ms` | `600000` | HTTP 连接和单次请求超时，至少 1 |

worker 认领任务时递增尝试次数和 fencing token，定期续租，并向认领时读取的任务 URL 发送 POST，body 是执行的 `payload`，请求头包含 `Content-Type: application/json`、以该执行的 `externalIdempotencyKey` 为值的 `Idempotency-Key` 和递增的 `X-Fencing-Token`。更新任务 URL 会影响之后的认领，但不会改变已经认领的请求。

只有 HTTP 2xx 被视为成功；非 2xx、连接错误和超时都算失败。未达到 `maxAttempts` 时以指数退避重试：首次失败等待初始延迟，之后每次翻倍，最高不超过上限，没有随机抖动；实际执行时间还受轮询和可用并发影响。达到次数上限后标记 `FAILED`。worker 中断或租约丢失时远端结果可能未知，租约过期后可重新认领；最后一次尝试的租约过期会被标记失败。

投递语义为 **至少一次，不是恰好一次**：超时、进程故障、续租失败或结果写入失败都可能导致远端已处理但请求再次发送，也可能出现重叠请求。API 的幂等键只防止重复创建执行，不能保证远端副作用恰好一次。接收方必须根据 `Idempotency-Key` 实现持久化去重；若依赖 fencing token 防止旧请求覆盖新结果，也必须由接收方自行检查和执行。

## 安全与扩展限制

- **没有认证或授权**：任何能访问 API 的人都可创建、修改任务并触发请求。仅在可信网络使用，并在对外部署前加入认证、授权和网络隔离。
- **SSRF 风险**：URL 校验只检查格式，不阻止内网、回环、云元数据等地址，也不做目标地址白名单或 DNS 安全校验。示例的 localhost URL 仅用于受控本地测试。不要让不可信用户指定目标；生产环境需在应用及网络出口实施严格目标限制。
- Citus 模式下 `job_definitions` 和 `job_executions` 都是引用表，复制到节点而非按执行分片；写入、存储和全局轮询会成为扩展瓶颈，节点越多复制成本越高，不应视为水平扩展的分布式队列。应在目标 Citus 版本和拓扑上验证迁移、锁和认领查询的兼容性及性能。
- 当前只有创建、更新和按 ID 查询接口，没有列表、删除、取消或手动重试接口；没有内建调度时间或周期任务接口。数据库和下游服务的可用性、数据保留及监控需要另行运维。
