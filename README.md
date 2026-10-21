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
- **M1 技术验证已通过**，下一项为 T007：建立数据库迁移和最小初始化数据。

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

状态接口返回应用 `RUNNING`、UTC 时间和请求标识。当前后端可独立启动，业务服务连接随后续任务接入。环境覆盖及包边界详见 [后端说明](backend/README.md)。

## 环境参数

本地业务服务由 Compose 显式读取 `.env`。使用初始化脚本创建参数及随机凭证，已有文件会保留；Fabric 开发身份路径由准备脚本生成，Java 探针直接使用 gateway-paths.json。`.env` 已加入忽略规则。

```bash
python3 deploy/scripts/init-env.py
```

Spring Boot 从进程环境读取 `SPRING_PROFILES_ACTIVE`、`SERVER_ADDRESS`、`SERVER_PORT`，默认 `local`、`127.0.0.1`、`8080`。它不会自动加载 `.env`，初始化脚本也不会启动服务或切换 JAVA_HOME。数据库、消息、对象存储和 JWT 随对应任务接入；Fabric SDK 适配已验证，正式业务存证处理在 T012 接入。

后端固定 Spring Boot 4.1.1，已完成构建、测试及打包启动验证；四个业务服务镜像已通过 T003 验证，Fabric 2.5.16 节点、CLI 和开发通道已通过 T004 验证。Gateway Java 1.13.0 已通过 T006 双机构及跨进程检查点验证；MyBatis Starter 4.0.0 待 T007 接入，具体状态见验证记录。

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

默认 Web 应用可独立启动，不读取证书或连接 Fabric。T006 完成的是 SDK 及文件检查点验证，数据库中的交易尝试、Outbox 和故障恢复仍按后续任务实现。参数、分阶段命令及失败处理见 [后端说明](backend/README.md)。

## 开发与演示边界

首版使用两个业务组织、一个通道、一份链码，链上仅有 RegisterEvidence 和 QueryEvidence。图片、视频、报告及权限、复核记录均在链外管理。

无人机采用 3～5 台模拟器，不开发真实飞控、路径规划或自动缺陷识别。摘要核验用于检查文件与登记版本的一致性，不证明现场采集内容真实。
