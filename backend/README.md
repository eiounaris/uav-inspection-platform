# 后端工程

Spring Boot 4.1.1、Java 21、Maven 单工程。已实现 HTTP 基础能力和 T006 Gateway 适配与真实调用探针，后端可独立启动；后续按业务包补充功能。

## 构建与运行

在项目根目录先选择 JDK 21，确认 `mvn -version` 的 Java 版本，再执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml clean verify
java -jar backend/target/inspection-backend-0.1.0.jar
```

默认环境为 `local`，绑定 `127.0.0.1:8080`。使用 Ctrl+C 停止。通过进程环境变量或 Spring Boot 标准命令行参数覆盖配置，例如：

```bash
SPRING_PROFILES_ACTIVE=prod SERVER_PORT=18080 java -jar backend/target/inspection-backend-0.1.0.jar
```

`local` 开启项目包 DEBUG 日志，`prod` 使用 INFO；两个环境共用 UTC 时间和错误响应规则。认证在 T008 接入，当前仅提供应用状态接口。`.env.example` 用于说明参数，Spring Boot 不会自动读取 `.env`，运行前需按需设置环境变量。`PROJECT_JAVA_HOME` 也不会自动改变 `JAVA_HOME`。

## 当前接口与通用约定

```bash
curl -i -H 'X-Trace-Id: t002-demo-001' http://127.0.0.1:8080/api/v1/system/status
```

响应结构示例，时间为示意值：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "application": "uav-inspection-backend",
    "status": "RUNNING",
    "timestamp": "2026-10-19T14:00:00Z"
  },
  "traceId": "t002-demo-001"
}
```

- `RUNNING` 表示应用可响应 HTTP，不代表 MySQL、RabbitMQ 或 Fabric 已连接。
- JSON 响应统一为 `code/message/data/traceId`；失败保留实际 HTTP 状态和必要响应头，例如 405 的 `Allow`。
- `X-Trace-Id` 允许 1～64 位 ASCII 字母、数字、下划线、连字符；省略或不合法时生成 UUID。响应头、正文和请求内日志使用同一值；请求结束恢复线程原 MDC。
- 业务时间注入 UTC `Clock`，接口时间使用 `Instant`，序列化为带 `Z` 的 ISO 8601；后续数据库迁移继续采用设计中的 UTC 约定。
- 受控业务异常使用固定错误代码和消息；未知异常在服务端记录，响应不包含异常原文或堆栈。MVC 和 Servlet 错误分派共用响应格式。

可直接运行 [HTTP 示例](../examples/t002.http)。参数校验、异常及 MDC 清理通过测试专用控制器验证。

## 包结构

根包为 `com.uav.inspection`，同一进程内按业务拆包，模块间通过服务接口调用。

| 包 | 职责与当前状态 |
| --- | --- |
| `common.api/error/web` | 已实现统一响应、异常、Servlet 错误兜底、请求标识 |
| `config` | 已实现 UTC Clock；配置文件提供 local/prod 日志环境 |
| `system` | 已实现只读状态接口 |
| `identity` | 用户、角色及资源权限；预留包边界 |
| `inspection`、`device` | 巡检任务、执行批次及设备；预留包边界 |
| `evidence`、`review`、`sharing` | 文件、核验、人工复核及授权；预留包边界 |
| `blockchain` | 已提供 Gateway 连接、分步登记/提交/确认、查询及检查点作用域校验；数据库中的交易尝试恢复在 T012 等任务接入 |
| `messaging`、`audit` | Outbox、消息消费及审计；预留包边界 |

预留包只包含职责说明，不含模拟业务实现。数据库、缓存、消息和对象存储客户端随对应任务接入。Spring Boot 父 POM 固定为 4.1.1；Gateway 固定为 1.13.0，gRPC BOM 1.84.0 与 Protobuf BOM 4.36.2 在本模块显式管理，避免解析到不匹配的协议库。默认启动没有 Fabric Bean，也不会读取链身份或连接网络。

## Java Gateway 真实探针（T006）

前提是 T004 网络、mychannel 和 T005 evidence 链码已经启动。在项目根目录、选择 JDK 21 后执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P fabric-probe clean verify
python3 deploy/fabric/verify-gateway.py
```

Maven 的 fabric-probe profile 只生成探针 classpath；构建运行 20 项 HTTP 和 12 项检查点单元测试，不自动向链提交交易。Python 启动器才执行真实链调用，每次创建一个独立运行目录，并依次启动四个 JVM：

| 阶段 | 行为与检查点 |
| --- | --- |
| prepare | OrgA/B 查询目标为空；OrgB 背书后保存 transaction.bin、原 txId 和 PREPARED，退出进程 |
| submit | 新进程从 transaction.bin 恢复原交易；提交前写 SUBMITTING，提交后保存 commit.bin 和 SUBMITTED，退出进程 |
| confirm | 新进程从 commit.bin 查询有效提交；保存验证码和块号，双方查询五字段匹配后才标记 CONFIRMED |
| verify | 再次恢复原确认请求；验证同摘要新交易幂等、摘要冲突、OrgA 登记拒绝及原记录不变 |

启动器从 .local/fabric/gateway-paths.json 使用准备脚本生成的两个普通 User1 身份，设置 Peer 的 TLS server name，不读取根目录 .env，也不输出私钥。两个身份分别配置 Gateway；CA 校验及主机名校验保持启用。路径表以本项目开发网络为准，不用管理员身份代替业务验证。

每个阶段记录 JVM PID。检查点通过临时文件、fsync 和原子移动落盘，文件权限为 0600；JSON 报告只包含文件路径、长度、SHA-256 及交易信息。恢复前核对原 MSP、证书字节、通道和交易编号，交易检查点还核对链码名。SDK 通常在提交/查询时才签名，恢复时必须保留原证书和对应私钥；只有 txId 不能直接恢复确认请求。

运行目录位于 .local/fabric/gateway-probe/，latest.json 指向最近成功报告。需要逐阶段查看时，使用同一个新目录：

```bash
python3 deploy/fabric/verify-gateway.py --phase prepare --run-dir .local/fabric/gateway-probe/manual-run
python3 deploy/fabric/verify-gateway.py --phase submit --run-dir .local/fabric/gateway-probe/manual-run
python3 deploy/fabric/verify-gateway.py --phase confirm --run-dir .local/fabric/gateway-probe/manual-run
python3 deploy/fabric/verify-gateway.py --phase verify --run-dir .local/fabric/gateway-probe/manual-run
```

prepare 要求新目录。RPC 异常保留已有检查点并记录 UNKNOWN，启动器非零退出；有 commit.bin 时可重新执行 confirm。没有 commit.bin 的提交未知态，以及 verify 中未完成的重复交易，需要检查原检查点，探针不会自动创建新交易绕过它。完整业务恢复由 T012、T024、T027 实现和验收。

Peer Gateway 不转发链码错误 payload。探针只在 EndorseException、gRPC ABORTED 和 Peer ErrorDetail 的完整固定业务消息都匹配时，映射为 EVIDENCE_HASH_CONFLICT 或 FORBIDDEN；普通超时、连接错误及泛化 ABORTED 不算预期业务拒绝。

可复用接口为 EvidenceLedgerClient，生产适配为 GatewayEvidenceLedgerClient。探针位于 test 源码，不进入可执行应用 JAR，不提供 HTTP 写入口。T006 已实测通过，具体证据见 [验证记录](../docs/verification.md)；M1 已通过，M2 从 T007 数据库迁移开始。
