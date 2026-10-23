# 无人机集群巡检数据共享与证据溯源平台

面向园区委托方和无人机运营商，使用 Java 后端管理多机巡检任务、文件上传、版本、人工复核及授权共享，使用 Hyperledger Fabric 登记和查询文件摘要。

## 当前进度

- 三份首版文档已完成。
- T001 环境盘点与版本候选清单已完成，详见 [验证记录](docs/verification.md)。
- 本机 JDK 21、Maven、Docker CLI、Compose 插件及 Docker daemon 已验证可用。
- T002 工程骨架已完成：20 项契约测试通过，可执行 JAR 已验证 local/prod 启动及 UTC 时间输出。
- T003 基础设施已完成：MySQL、Redis、RabbitMQ、Garage S3 正在本机运行，读写、消息、私有对象及保留卷重建验证通过。
- T004 Fabric 开发网络已完成：两个 Peer 和一个 Orderer 健康，均已加入 mychannel；保留卷重建后通道高度、区块哈希和证书一致。
- T005 Java 链码已完成：50 项测试通过，已部署 evidence 0.1.0，双机构普通用户的登记、查询、幂等、冲突与越权拒绝实测通过。
- T006 Java Gateway 已完成：后端共 32 项测试通过；四个独立 JVM 完成交易检查点恢复、有效提交、双机构查询及幂等/拒绝用例。
- **M1 技术验证已通过**。T007 已完成 MySQL/MyBatis/Flyway 接入、五表迁移及 6 个账号/1 台设备初始化；重复启动、24 项真实约束检查和 UTC 毫秒往返通过。
- T008 认证与角色管理已完成：登录、退出、me、机构内角色修改可用；后端 56 项测试及 48 次真实 HTTP 验收通过，旧令牌失效、越权拒绝和并发 CAS 已验证。
- T009 任务与成员管理已完成：九项操作覆盖草稿、资料要求、成员、分配、子任务、初始批次和启动；66 项后端测试、90 次真实 HTTP 调用及 35 项数据库约束检查通过。
- T010 私有上传已完成：会话创建、原始分片上传和进度查询接入真实 Garage；72 项后端测试、68 次真实 HTTP 流程及 49 项数据库约束检查通过，原数据及凭据保留。
- T011 异步定版与 Outbox 已完成：99 项后端测试、42 次真实 HTTP 联调及 68 项数据库约束通过，已验证唯一版本、消息重复、无法路由重试和临时故障恢复。
- T012 业务存证已完成：129 项后端测试、45 次真实 HTTP 联调及 87 项数据库约束通过；两个版本真实上链，确认响应丢失后用原交易恢复成功。
- T013 文件核验已完成：148 项后端测试、62 次真实 HTTP 联调及 110 项数据库约束通过；原文件/副本比较、五种结果、历史权限及处理中撤销成员均已验证。
- **T014 已完成，M2 最小真实闭环已通过**：打包应用经正式接口自动完成任务、上传、定版、真实存证和核验；33 次 HTTP 验收及 15 次同版本复查通过，演示成果保留。
- 下一项为 **T015：设备管理、设备认证和上报**；完整 MVP 继续按任务清单推进。

## 项目文档

| 文档 | 内容 |
| --- | --- |
| [需求说明](docs/01-requirements.md) | 功能范围、权限及 12 项验收场景 |
| [技术设计](docs/02-design.md) | 模块、数据、接口、状态及异常恢复 |
| [开发任务](docs/03-tasks.md) | 阶段、依赖及完成条件 |
| [验证记录](docs/verification.md) | 实际环境、固定候选版本、执行证据及待验证事项 |
| [配置样例](.env.example) | 本地环境参数约定，后续由应用和部署配置接入 |
| [后端说明](backend/README.md) | 构建、运行、包结构和 HTTP 基础约定 |
| [T002 HTTP 示例](examples/t002.http) | 状态接口、请求标识及 404/405 调用 |
| [T008 HTTP 示例](examples/t008.http) | 登录、当前身份、角色修改、退出及令牌失效 |
| [T009 HTTP 示例](examples/t009.http) | 双机构创建/分配任务、维护成员、细化资料、分配设备及启动 |
| [T010 HTTP 示例](examples/t010.http) | 创建上传会话、上传附带 PNG、查询进度及同片重试 |
| [T011 HTTP 示例](examples/t011.http) | 请求异步定版、重复完成及轮询版本编号 |
| [T012 HTTP 示例](examples/t012.http) | 固定版本详情、存证状态、实际交易编号及越权拒绝 |
| [T013 HTTP 示例](examples/t013.http) | 原文件/相同副本/修改副本核验、历史查询与权限拒绝 |
| [T014 HTTP 示例](examples/t014.http) | 从登录到任务、上传、异步定版、真实存证和文件核验的完整调用顺序 |
| [M2 演示说明](docs/m2-demo.md) | 自动验收、保留结果复查和面试演示步骤 |
| [基础设施说明](deploy/README.md) | 四个业务服务、地址、持久卷及验证命令 |
| [Fabric 网络说明](deploy/fabric/README.md) | 固定版本、通道创建、证书及保留账本的启停 |
| [Java 链码说明](chaincode/README.md) | 两个合约接口、参数、权限、幂等及 CaaS 部署 |

## 本机 Java 选择

本次检测中，系统默认 Java 为 25.0.4，项目目标 JDK 21.0.12 也已安装。开发本项目时，在当前终端选择 JDK 21，并再次确认 Maven 使用同一 JDK：

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.12-tem"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
javac -version
mvn -version
```

这里的路径来自本机检查；其他机器应替换为实际 JDK 21 路径。当前核查仅对检查进程选择 JDK 21，没有修改 SDKMAN 的全局默认设置。

## 构建与启动后端

在项目根目录、已选择 JDK 21 的终端执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml clean verify
java -jar backend/target/inspection-backend-0.1.0.jar
```

默认监听 `127.0.0.1:8080`，使用 Ctrl+C 停止。另开终端验证：

```bash
curl -i -H 'X-Trace-Id: demo-001' http://127.0.0.1:8080/api/v1/system/status
```

状态接口返回应用 `RUNNING`、UTC 时间和请求标识。上述命令启动独立 HTTP 基础模式；认证及任务接口使用下文的数据库模式。环境覆盖及包边界详见 [后端说明](backend/README.md)。

## 环境参数

本地业务服务由 Compose 显式读取 `.env`。使用初始化脚本创建参数及随机凭证，已有文件会保留；Fabric 开发身份路径由准备脚本生成，Java 探针直接使用 gateway-paths.json。`.env` 已加入忽略规则。

```bash
python3 deploy/scripts/init-env.py
```

Spring Boot 从进程环境读取 `SPRING_PROFILES_ACTIVE`、`SERVER_ADDRESS`、`SERVER_PORT`，默认 `local`、`127.0.0.1`、`8080`。它不会自动加载 `.env`，初始化脚本也不会启动服务或切换 JAVA_HOME。后端启动器显式读取 DB_*、S3_*、UPLOAD_*、VERIFICATION_*、RABBITMQ_*、MESSAGING_*、FABRIC_*、JWT_SECRET；保留初始化生成的原凭据和 64 位小写十六进制密钥。对象存储在 T010 接入，T011 接入异步定版及消息，T012 通过 --ledger 显式启用真实业务存证。

后端固定 Spring Boot 4.1.1，已完成构建、测试及打包启动验证；四个业务服务镜像已通过 T003 验证，Fabric 2.5.16 节点、CLI 和开发通道已通过 T004 验证。Gateway Java 1.13.0 已通过 T006；MyBatis Starter 4.0.0、Flyway 12.4.0 和 Connector/J 9.7.0 已通过 T007，具体状态见验证记录。

## 启动数据库、存储与异步处理后端

MySQL、Garage、RabbitMQ 已启动且已选择 JDK 21 时，在项目根目录执行：

```bash
python3 deploy/scripts/init-demo-credentials.py
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-auth.py
python3 deploy/scripts/verify-tasks.py
python3 deploy/scripts/verify-upload.py
python3 deploy/scripts/verify-pipeline.py
python3 deploy/scripts/run-backend.py --demo
```

启动器读取本地 .env 的数据库、存储及 RabbitMQ 参数，开启 local,database,storage,messaging[,demo]。移除 --demo 只迁移结构、不插入演示账号。随机密码和设备令牌保存在 .local/demo-credentials.json（0600），不打印或提交；重复启动保留既有值。prod 组合由应用拒绝演示初始化。迁移、角色清单和验证方法见 [后端说明](backend/README.md)。

## 启用真实 Fabric 业务存证与文件核验

启动下文 Fabric 网络及链码后，复用原数据库、RabbitMQ、Garage 和普通 User1 身份：

```bash
python3 deploy/scripts/verify-anchor.py
python3 deploy/scripts/verify-verifications.py
python3 deploy/scripts/run-backend.py --demo --ledger
```

--ledger 追加 ledger profile，连接两个机构客户端并启动上链消费者及恢复扫描。local 为缺少的身份路径读取原 .local/fabric/gateway-paths.json；prod 必须配置明确的身份路径。省略 --ledger 可继续运行上传及消息模式。存证详情和状态查询见 [T012 示例](examples/t012.http)，只有有效账本结果才显示 CONFIRMED；超时保留 UNKNOWN 及原交易检查点。

文件核验及历史查询见 [T013 示例](examples/t013.http)，STORED 实际读取原对象，COPY 比较本次副本且不覆盖原文件。核验并发、文件上限及处理时限可由 VERIFICATION_* 配置。

真实探针使用独立业务数据和消息拓扑，结束后清理自己的链外夹具；已提交的测试证据保留在原账本，不重建或清空链网络。

## 最小真实闭环演示

依赖服务及链码已运行、已按上文构建带 database-probe 的后端时，先停止其他后端实例，再执行：

```bash
python3 deploy/scripts/verify-mvp.py
```

脚本使用现有演示账号，经正式 HTTP 接口创建并启动任务、单片上传图片，等待自动定版与真实存证，再核验原文件和副本。成功数据会保留；只读检查器核对数据库、私有对象和两个机构的实际账本。结果位于 `.local/mvp-demo/<run>/`，复查同一版本使用 `python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/<run>`。完整步骤见 [M2 演示说明](docs/m2-demo.md)。

## 启动业务基础设施

```bash
python3 deploy/scripts/init-env.py
docker compose --env-file .env -f deploy/compose.yml up -d --wait --wait-timeout 240
```

四个镜像均已固定版本和摘要。MySQL、Redis、RabbitMQ 的宿主端口分别为 3306、6379、5672，RabbitMQ 管理界面为 `http://127.0.0.1:15672`，S3 API 为 `http://127.0.0.1:9000`。凭证保存在本机 `.env`；所有宿主端口仅绑定 `127.0.0.1`。

MinIO 历史候选镜像获取失败，S3 实现改为 Garage 2.4.1。首次拉取、容器内地址、分阶段持久性验证及保留数据的停止方式见 [基础设施说明](deploy/README.md)。

## 启动 Fabric 网络

```bash
python3 deploy/fabric/prepare.py
python3 deploy/fabric/network.py up
python3 deploy/fabric/network.py channel
python3 deploy/fabric/network.py verify
```

工具与样例归档均校验 SHA-256，生成材料放在 `.local/fabric/`。日常可用 `network.py stop` 停止、`network.py up` 恢复，保留证书与账本。OrgA/OrgB 的 Peer 端点分别为 `localhost:7051`、`localhost:9051`。具体版本、私钥路径参考文件及重建验证方法见 [Fabric 网络说明](deploy/fabric/README.md)。

## 构建与部署 Java 链码

在选择 JDK 21、网络及通道已启动的情况下执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f chaincode/pom.xml clean verify
python3 deploy/fabric/chaincode.py deploy
python3 deploy/fabric/verify-chaincode.py
```

链码固定 Java shim 2.5.11，使用独立 CaaS 实例及双组织背书，无需在 Peer 挂载 Docker socket。部署后 `network.py` 自动统一管理三个节点与两个链码实例。链码服务的 9999 端口仅在本项目 Docker 网络内提供明文连接，不发布到宿主机，适用于本地开发验证。

脚本复用已有通道及定义，校验构建产物摘要以防止原版本下静默替换。T005 合约报告及实际交易编号位于 `.local/fabric/chaincode/`，结论见 [验证记录](docs/verification.md)。

## Java 双机构 Gateway 探针

选择 JDK 21，在上述网络和链码已启动的情况下执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P fabric-probe clean verify
python3 deploy/fabric/verify-gateway.py
```

构建不会自动向链提交交易。Python 启动器使用两机构 User1 身份，在四个独立 JVM 中验证背书后保存交易、恢复原 txId 提交、恢复确认请求、双方查询及幂等/拒绝场景。检查点和报告保存在 `.local/fabric/gateway-probe/`，latest.json 指向最近成功运行。

默认 Web 应用可独立启动，不读取证书或连接 Fabric。T006 完成的是 SDK 及文件检查点验证，基础 Outbox 已在 T011 接入；T012 接入数据库交易尝试和基础 UNKNOWN 恢复，完整故障矩阵由 M4 验收。参数、分阶段命令及失败处理见 [后端说明](backend/README.md)。

## 开发与演示边界

首版使用两个业务组织、一个通道、一份链码，链上仅有 RegisterEvidence 和 QueryEvidence。图片、视频、报告及权限、复核记录均在链外管理。

无人机采用 3～5 台模拟器，不开发真实飞控、路径规划或自动缺陷识别。摘要核验用于检查文件与登记版本的一致性，不证明现场采集内容真实。
