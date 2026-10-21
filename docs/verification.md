# 环境与验证记录

本文件记录实际执行结果。已安装工具、目标版本、候选镜像和已运行服务分别记录；尚未验证的部分不作为里程碑通过依据。

## T001：环境盘点与版本候选清单

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-19（Asia/Shanghai） |
| 任务 | [开发任务清单 T001](03-tasks.md) |
| 检查目录 | /home/eiou/workspace/tmpAi |
| 结果 | 环境盘点及候选版本记录完成，可进入 T002；M1 尚未通过 |
| 执行范围 | 读取工具版本、机器资源、端口与 Docker daemon 状态；按进程选择已安装 JDK 21 验证 Maven |

本次没有安装工具、拉取镜像、启动项目服务、下载 Fabric 样例或更改全局 JDK。候选版本的依赖解析、镜像获取、连接及运行兼容性分别在后续任务验证。

### 1. 实际机器与资源

| 检查项 | 实际结果 | 说明 |
| --- | --- | --- |
| 操作系统 | Ubuntu 22.04.5 LTS，Linux 6.8.0-138-generic | x86_64 |
| 逻辑 CPU | 8 | Docker daemon 同样报告 8 CPU |
| 内存 | MemTotal 16329240 kB，MemAvailable 13169692 kB | 检查时快照，约 15.57 GiB 总内存、12.56 GiB 可用 |
| 工作目录磁盘 | 总计 97.38 GiB，可用 48.73 GiB | /tmp 与工作目录位于相同容量的文件系统，不能相加为两份空间 |
| Docker 内存 | 16721141760 bytes | daemon 报告值，不代表项目独占资源 |

未在常见 cgroup v2 路径读取到限制文件，因此不据此推断没有其他资源限制。以上容量仅用于开发环境盘点，不是项目性能结论。

### 2. 工具检查

| 工具 | 实际结果 | 状态 |
| --- | --- | --- |
| 默认 java / javac | Temurin 25.0.4+7 / javac 25.0.4 | 可执行，非项目指定 JDK |
| 项目 java / javac | Temurin 21.0.12+8 / javac 21.0.12 | 已安装，单独执行通过 |
| Maven | 3.9.16 | 已执行；在指定 JDK 21 的环境中再次验证通过 |
| Docker CLI | 29.7.2，build a7dcaa6 | 已执行 |
| Docker daemon | 29.7.2 | 已连接并读取版本与资源信息 |
| Compose 插件 | v5.4.0 | docker compose version 成功 |
| 独立 docker-compose | PATH 中不存在 | 使用现有 Compose 插件，无需据此安装旧独立命令 |
| Git | 2.34.1 | 已执行；本次未初始化或变更仓库元数据 |
| curl | 7.81.0 | 已执行 |
| tar | GNU tar 1.34 | 已执行 |
| unzip | 6.00 | 已执行 |
| OpenSSL | 3.0.2 | 已执行 |

本机项目 JDK 路径为 /home/eiou/.sdkman/candidates/java/21.0.12-tem；默认 current 指向 25.0.4-tem。Maven 安装路径为 /home/eiou/.sdkman/candidates/maven/current。

实际执行并返回退出码 0 的项目工具验证命令：

```bash
/home/eiou/.sdkman/candidates/java/21.0.12-tem/bin/java -version
/home/eiou/.sdkman/candidates/java/21.0.12-tem/bin/javac -version
JAVA_HOME=/home/eiou/.sdkman/candidates/java/21.0.12-tem PATH=/home/eiou/.sdkman/candidates/java/21.0.12-tem/bin:$PATH mvn -version
docker --version
docker compose version
docker version --format '{{.Server.Version}}'
docker info --format 'cpus={{.NCPU}} memory_bytes={{.MemTotal}} root={{.DockerRootDir}}'
ss -ltn
```

实际检查由只读程序执行对应命令，Maven 的 JAVA_HOME/PATH 仅在子进程环境中设置。最初端口检查被沙箱的套接字限制拒绝，随后在获准的沙箱外只读检查中完成端口和 daemon 验证；未将第一次拒绝视为端口空闲。

### 3. 项目候选版本

下表固定的是后续任务拟使用的版本。除 JDK、Maven 和 Docker 工具外，本次没有执行依赖下载、镜像拉取或运行验证。

| 组件 | 固定候选 | 当前验证层级 | 下一步 |
| --- | --- | --- | --- |
| 业务 JDK | Temurin 21.0.12+8 | 本机已执行 | T002 构建并启动后端 |
| Maven | 3.9.16 | 本机已执行 | T002 固定构建方式 |
| Spring Boot | 4.1.1 | 官方版本与 Java/Maven 要求已查；未构建 | T002 解析依赖并验证启动 |
| MyBatis Starter | 4.0.0 | 官方 4.0 与 Boot 4.0+ 兼容表已查；未接入 | T002/T007 验证实际依赖组合 |
| MySQL | mysql:8.4.11 | 官方发行与镜像标签候选已查；本项目未拉取验证 | T003 拉取、启动并连接 |
| Redis | redis:7.4.11-bookworm | 官方镜像标签候选已查；本项目未拉取验证 | T003 拉取、启动并连接 |
| RabbitMQ | rabbitmq:4.2.9-management | 官方镜像标签候选已查；本项目未拉取验证 | T003 验证连接、收发及管理端口 |
| S3 兼容存储 | quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z | 历史发行存在；该远端镜像当前是否可拉取尚未核实 | T003 先验证获取及私有对象操作，必要时调整 S3 实现并更新记录 |
| Fabric Peer/Orderer/二进制 | 2.5.16 | 官方 2.5 发行版本已查；未下载或运行 | T004 匹配镜像与工具，启动网络 |
| Fabric Gateway Java | 1.13.0 | 官方仓库稳定发行候选已查；未解析 Java 依赖 | T006 验证 Maven 制品和双机构调用 |

版本依据：

- Spring Boot 版本与基础要求见[官方系统要求](https://docs.spring.io/spring-boot/system-requirements.html)；MyBatis Starter 见[官方兼容表与依赖示例](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。
- MySQL 8.4.11 见[发行说明](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/news-8-4-11.html)及[官方镜像清单](https://github.com/docker-library/official-images/blob/master/library/mysql)；Redis、RabbitMQ 候选分别见[Redis 镜像清单](https://github.com/docker-library/official-images/blob/master/library/redis)和[RabbitMQ 镜像清单](https://github.com/docker-library/official-images/blob/master/library/rabbitmq)。
- MinIO 候选对应[历史发行](https://github.com/minio/minio/releases/tag/RELEASE.2025-09-07T16-13-09Z)。历史源码标签存在不等于该镜像仍可获取，不将其记为镜像拉取验证通过。
- Fabric 见[2.5.16 发行说明](https://github.com/hyperledger/fabric/releases/tag/v2.5.16)，Gateway 见[官方发行列表](https://github.com/hyperledger/fabric-gateway/releases)。

Fabric samples 的实际可用引用及提交尚未核实，T004 获取时必须固定实际 commit；不能假定 samples 存在与 Peer 同名的 2.5.16 标签。Java 链码构建工具和运行镜像由 T005 根据匹配样例固定，不能把业务 JDK 或 Peer 标签直接套用到链码镜像。

### 4. 端口快照

检查目标为本机 127.0.0.1，并结合 ss -ltn 检查监听列表：

| 用途 | 计划端口 | 本次结果 |
| --- | --- | --- |
| 业务 HTTP | 8080 | 未发现监听，TCP 连接被拒绝 |
| MySQL | 3306 | 未发现监听，TCP 连接被拒绝 |
| Redis | 6379 | 未发现监听，TCP 连接被拒绝 |
| RabbitMQ / 管理界面 | 5672 / 15672 | 未发现监听，TCP 连接被拒绝 |
| 对象存储 / 候选管理界面 | 9000 / 9001 | 未发现监听，TCP 连接被拒绝 |
| Fabric 排序 / 管理 / Peer | 7050 / 7053 / 7051 / 9051 | 未发现监听，TCP 连接被拒绝 |
| Fabric 可选 CA 预留 | 7054 / 8054 / 9054 | 未发现监听，TCP 连接被拒绝；首版不因此增加 CA 部署 |

这是检查时快照，不保证后续绑定一定成功。主机存在其他监听服务，本次未停止、重配置或复用它们；T003/T004 启动前再次核对本项目端口。

### 5. T001 结论与待验证事项

- T001 的环境检查、候选版本选择、命令证据及配置样例位置已完成；本次没有发现需要先安装 JDK、Maven 或 Docker 的工具缺口。
- JDK 21 必须在项目构建进程中明确选择；直接使用默认 mvn 时仍可能运行在 Java 25。
- T002 仍需实际创建工程、解析依赖、构建并验证启动；本次的 mvn -version 成功不能替代这些检查。
- T003 仍需验证镜像可获取、私有对象读写、消息收发、持久化及端点连接；MinIO 候选的获取问题是明确的待核实事项。
- T004 仍需固定 samples commit、获取二进制/镜像并验证实际 Docker/Fabric 兼容性；Docker daemon 可访问不代表链网络已运行。
- T006 才验证双机构有效提交、查询、拒绝登记及交易恢复；当前 M1 未通过，所有业务 AC 场景均未执行。

后续任务在本文件追加记录，不覆盖本次快照。

## T002：Spring Boot 工程骨架

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-19（Asia/Shanghai） |
| 任务 | [开发任务清单 T002](03-tasks.md) |
| 产物 | backend/、examples/t002.http；更新 README、配置样例及设计中的 Boot 固定版本 |
| 结果 | 构建、20 项契约测试、local/prod 可执行 JAR 启动通过，T002 完成 |
| 当前边界 | 只验证 HTTP 基础能力；MySQL、Redis、RabbitMQ、对象存储及 Fabric 尚未接入，M1 未通过 |

### 1. 实际构建版本与命令

使用本机 Temurin 21.0.12+8、Maven 3.9.16。POM 固定 Spring Boot 父版本 4.1.1，接入 Web MVC、Validation 和对应测试 Starter；间接依赖及插件由该父版本管理。

打包制品中实际包含 Spring Framework 7.0.9、Jackson Databind 3.1.5、Tomcat 11.0.24、Hibernate Validator 9.1.3.Final。JAR Manifest 记录 Java-Version=21、Build-Jdk-Spec=21、Spring-Boot-Version=4.1.1；检查确认不包含测试控制器。

在项目根目录实际执行：

```bash
JAVA_HOME=/home/eiou/.sdkman/candidates/java/21.0.12-tem PATH=/home/eiou/.sdkman/candidates/java/21.0.12-tem/bin:$PATH mvn -B -ntp -Dmaven.repo.local=/home/eiou/workspace/tmpAi/.local/m2 -f backend/pom.xml clean verify
```

实际结果：退出码 0，`BUILD SUCCESS`，20 项测试，失败 0、错误 0、跳过 0。产物为 backend/target/inspection-backend-0.1.0.jar，大小 21,586,254 bytes。

首次依赖解析在沙箱内因 DNS 受限失败，随后获准访问依赖仓库后成功；本次依赖按已有 Maven 镜像配置从阿里云公共仓库解析。本地依赖放在 .local/m2，没有写入用户目录或修改全局 Maven/JDK 设置。完整测试需要本机随机监听端口，已在获准环境中实际执行。

### 2. 自动测试结果

测试文件为 backend/src/test/java/com/uav/inspection/HttpContractTest.java，使用真实 Spring Boot 上下文、MockMvc 及一个随机端口 HTTP 调用。参数化输入计入 20 项测试。

| 验证内容 | 实际结果 |
| --- | --- |
| GET /api/v1/system/status | 200，统一响应，应用 RUNNING，timestamp 为可解析且带 Z 的 Instant |
| 合法 traceId | 1 位、普通标识及 64 位边界值均原样保留；头、正文和请求内 MDC 一致 |
| 不合法或缺失 traceId | 空值、空格、斜线、中文、超长值被替换；缺失时生成合法标识 |
| MDC 生命周期 | 成功与异常结束后恢复原上下文；连续请求不串用标识 |
| 资源不存在 | 404、RESOURCE_NOT_FOUND；Accept:text/html 时仍返回统一 JSON |
| 请求方法错误 | 405、METHOD_NOT_ALLOWED，保留包含 GET 的 Allow 头 |
| DTO 校验与坏 JSON | 400、INVALID_ARGUMENT；校验返回字段错误，坏 JSON 不回传解析详情或输入内容 |
| 不支持的 Content-Type | 415、UNSUPPORTED_MEDIA_TYPE |
| 受控业务异常 | 409、VERSION_CONFLICT |
| 未知异常 | 500、INTERNAL_ERROR；响应不包含异常消息、类名或堆栈 |
| 真实 Servlet 错误分派 | 测试控制器 sendError(400) 实际触发 /error，返回统一 JSON，保留原 traceId，不回传容器错误消息 |

原始 Surefire 摘要：backend/target/surefire-reports/com.uav.inspection.HttpContractTest.txt。错误触发、校验及 MDC 探针控制器仅存在于 src/test，未进入可执行 JAR。

### 3. 可执行 JAR 启动验证

另外使用 JDK 21 启动打包后的 JAR，分别设置以下进程环境和 JVM 参数；SERVER_PORT=0 让系统分配空闲端口，SERVER_ADDRESS=127.0.0.1。临时验证程序通过真实 HTTP 请求检查结果，在 finally 中停止自己创建的应用进程。

| 环境 | JVM 时区 | 实际监听端口 | 状态接口实际时间 | 结果 |
| --- | --- | --- | --- | --- |
| SPRING_PROFILES_ACTIVE=local | -Duser.timezone=Asia/Shanghai | 34213 | 2026-10-19T14:18:13.113476209Z | 启动成功，200/404/405/500 检查通过 |
| SPRING_PROFILES_ACTIVE=prod | -Duser.timezone=America/Los_Angeles | 34703 | 2026-10-19T14:18:15.604031801Z | 启动成功，200/404/405/500 检查通过 |

每次启动均调用 GET /api/v1/system/status、GET /api/v1/not-found、POST /api/v1/system/status 及 GET /error。最后一个为直接访问错误入口，缺少容器错误状态时安全回退为 500；405 同时检查 Allow。各响应均验证请求 traceId 与响应头、正文一致。

local 状态响应的实际 data：

```json
{
  "application": "uav-inspection-backend",
  "status": "RUNNING",
  "timestamp": "2026-10-19T14:18:13.113476209Z"
}
```

对应 traceId 为 t002-local-status；prod 为 t002-prod-status。两个 JVM 时区下接口均输出 UTC。进程 2152406、2152465 已由验证程序停止，当前没有为本次验证留下常驻后端。临时启动日志位于 .local/t002-local-startup.log 和 .local/t002-prod-startup.log，不作为需要提交的源码。

### 4. 下一步

T003 验证业务基础设施，T004～T006 验证真实 Fabric 网络和双机构调用。认证、数据库迁移及业务接口仍按任务清单实施；本次状态接口不判断外部依赖健康，尚未执行业务 AC 场景。

## T003：业务基础设施与持久卷验证

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-19（Asia/Shanghai） |
| 任务 | [开发任务清单 T003](03-tasks.md) |
| 产物 | deploy/compose.yml、garage.toml、部署说明、init-env.py、verify-infra.py；更新配置样例和进度 |
| 结果 | 四服务健康；实际读写、私有对象、签名访问/到期拒绝、持久消息及保留卷重建通过，T003 完成 |
| 当前边界 | 后端数据库、缓存、消息及 S3 客户端尚未接入；Fabric 和业务 AC 未验证，M1 未通过 |

### 1. 镜像选择与实际版本

开始前确认本项目不存在同名 Compose 容器，3306、6379、5672、15672、9000、9001 均可绑定。实际部署仅发布前五个端口，未发布 9001。Docker daemon 29.7.2、Compose 5.4.0；Docker socket 在沙箱内不可访问，后续部署和本机网络验证均经获准环境实际执行。

原 MinIO 候选在 Quay 返回 `no such manifest`，同名 Docker Hub 标签返回 `denied/unauthorized`，未将它记为可运行。改为实际可获取的 Garage 2.4.1，仍满足设计中的 S3 兼容私有对象存储接口；部署采用[官方单节点启动方式](https://garagehq.deuxfleurs.fr/documentation/quick-start/)。

四个镜像均已成功拉取，并将以下内容摘要与版本标签一起固定到 .env.example 及本机 .env：

| 镜像版本 | 实际 RepoDigest（sha256） |
| --- | --- |
| mysql:8.4.11 | 24989179f58856db763f418010c1169fecc944e3ec1ea7e921572d83eb070f70 |
| redis:7.4.11-bookworm | 4fa24486b8bcca8eec45ee0eb166edc674795e53a2b53d1a9ef263eecebaac85 |
| rabbitmq:4.2.9-management | 7ce498399b07a9594a3d23e5a7920edf233edb888e82503c35357c23cefc2e61 |
| dxflrs/garage:v2.4.1 | 9c96caa2612d3411acc5b0e6701fb238dbfba33e533a6d7d3d811a4b12d0d020 |

容器内实际执行版本命令，分别得到 MySQL 8.4.11、Redis 7.4.11、RabbitMQ 4.2.9、Garage 2.4.1。MySQL 查询同时确认 time_zone=+00:00、character_set_server=utf8mb4、collation_server=utf8mb4_0900_ai_ci。

初始化脚本创建了权限为 0600 的 .env，密码及密钥随机生成且未输出。再次执行初始化时文件摘要未变，确认不会覆盖已有配置。Compose config --quiet 及两个 Python 脚本的语法检查通过。

### 2. 实际执行流程

在项目根目录执行以下流程，所有命令退出码最终均为 0；镜像拉取使用前述固定标签，最终复验使用标签与摘要组合：

```bash
python3 deploy/scripts/init-env.py
docker compose --env-file .env -f deploy/compose.yml config --quiet
docker compose --env-file .env -f deploy/compose.yml pull
docker compose --env-file .env -f deploy/compose.yml up -d --wait --wait-timeout 240
python3 deploy/scripts/verify-infra.py --phase seed
docker compose --env-file .env -f deploy/compose.yml down
docker compose --env-file .env -f deploy/compose.yml up -d --wait --wait-timeout 240
python3 deploy/scripts/verify-infra.py --phase check
```

第一次检查发现 Garage 的签名到期返回 400，原探针仅允许 401/403，因此该次检查未通过；未将此失败记为成功。核实 XML Code=InvalidRequest、Message=Bad request: Date is too old 后修正脚本，要求明确的到期错误，不能把任意 400 当作通过。随后验证成功，并将数据库探针改为每次独立建表与清理，重新完整执行以上 seed → down → up → check 流程。

### 3. 最终探针与结果

最终 runId：`9a5b0db2d834470e85ca1d3fe682b2b1`。载荷 SHA-256：`4325c04816fe4cd309f96e9a1fb38a2e57fc00fbed4bb30aefcafdd17affe4d8`。

seed 时间为 2026-10-19T14:36:58.943215Z，最终 verified 时间为 2026-10-19T14:38:29.488795Z。以下检查均使用 seed 时留下的数据；check 不重新插入记录、写键、发布消息或上传对象来代替旧数据。

| 验证项 | 实际结果 |
| --- | --- |
| 宿主机 MySQL/AMQP 端口 | 可收到 MySQL 协议 greeting 和 AMQP Connection.Start 响应；此项不声称完成应用客户端认证 |
| MySQL | 容器内非 root 应用账号真实建表、写入和查询；容器重建后原记录内容相同 |
| Redis | 带认证 SET/GET 成功，开启 AOF；容器重建后原键内容相同 |
| RabbitMQ | 管理 HTTP API 创建独立 durable 队列、发布 delivery_mode=2 消息且 routed=true；重建后取回原消息，内容及 delivery_mode 相同 |
| S3 私有对象 | 使用应用访问密钥签名 PUT/GET 成功，重建后对象字节与原载荷完全相同 |
| 匿名访问 | 对该已存在对象的无签名 GET 被拒绝 |
| 签名访问 | 按 S3_PUBLIC_ENDPOINT 签名的 GET 可读取原对象，不替换已签名 Host |
| 签名到期 | 同一条 2 秒有效期 URL 在到期后返回 400/InvalidRequest、明确的 Date is too old 消息 |
| 清理范围 | 仅清理本 runId 的独立表、键、队列及对象；RabbitMQ 检查读取后重新入队，避免检查中断时丢失探针 |
| 数据库后续迁移前提 | 清理后查询业务 schema 的表数为 0；最初测试遗留的空 infra_probe 表也已确认无行后移除 |

RabbitMQ 收发使用管理 HTTP API，AMQP 端口另行检查协议响应；尚未验证 Java AMQP 客户端、Outbox、publisher confirm 或业务消费幂等，这些属于 T011 及后续任务。

### 4. 容器重建及运行状态

最终复验中，down 移除容器和项目网络，未删除卷。重建前后容器标识确实不同，最终均为 healthy：

| 服务 | 重建前 ID | 重建后 ID | 宿主访问地址 |
| --- | --- | --- | --- |
| MySQL | 076c7284d245 | 9decc4de56c9 | 127.0.0.1:3306 |
| Redis | 482a1c8d6788 | 34d1521bcc33 | 127.0.0.1:6379 |
| RabbitMQ | e4242d8e55cf | 8bdb125b2c26 | 127.0.0.1:5672、127.0.0.1:15672 |
| Garage S3 | 64b11609b386 | ab235384ace8 | 127.0.0.1:9000 → 容器 3900 |

持久卷分别为 uav-inspection_mysql-data、uav-inspection_redis-data、uav-inspection_rabbitmq-data、uav-inspection_s3-meta、uav-inspection_s3-data。Garage 索引和对象均有独立持久卷。

最终状态文件 .local/infra-probe.json 记录 verified=true、status=CLEANED，四项探针均已清理。四个基础设施服务保留运行，所有宿主端口仅绑定 127.0.0.1；未操作其他项目容器、卷或服务。容器内地址及浏览器签名地址区别、停止/恢复和复验方式详见 [部署说明](../deploy/README.md)。

### 5. 下一步

进入 T004：固定 Fabric 工具、镜像和 samples commit，启动两个业务组织及排序服务，建立 mychannel。T005、T006 再验证链码和 Java 双机构调用；不能将本次四个业务服务健康视为 M1 已通过。

## T004：双机构 Fabric 开发网络

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-19（Asia/Shanghai） |
| 任务 | [开发任务清单 T004](03-tasks.md) |
| 产物 | deploy/fabric/ 下的版本锁、Compose、准备与管理脚本、健康检查及说明；更新配置样例、设计与进度 |
| 结果 | 两个 Peer、一个 Orderer 健康；mychannel 创建并加入成功；保留卷重建后原通道、账本高度/哈希及证书一致，T004 完成 |
| 当前边界 | 仅初始通道配置块；未部署业务链码，未完成 Java Gateway 调用及业务证据持久性验证，M1 未通过 |

### 1. 实际版本及来源

本机 Python 3.10.12、Docker daemon 29.7.2、Compose 5.4.0。开始前检查 7050、7051、7053、9051 及计划 operations 端口均可绑定；最终仅发布前四个宿主端口，全部绑定 127.0.0.1。

| 内容 | 实际固定值 |
| --- | --- |
| Peer、Orderer、主机 CLI | v2.5.16，Commit SHA f871cf9，Go 1.26.4，linux/amd64 |
| Peer 镜像摘要 | hyperledger/fabric-peer@sha256:09ee75042de9983bfde31ca88a5bf033386351f10a990e4c48264ee50172dee0 |
| Orderer 镜像摘要 | hyperledger/fabric-orderer@sha256:e322c57331d37e0a35ffae3cb3d3265a0e852211c0f801f2514cc15b964ffc93 |
| 官方工具归档 | hyperledger-fabric-linux-amd64-2.5.16.tar.gz，127,799,186 bytes |
| 工具归档 SHA-256 | 18c91e7f2f11b601e6622cc70454d568af897707ee9adf111e9fa91a233881bf |
| samples 实际 commit | 5789681b4f4d24e58fa40f19a69f5496892374b6 |
| samples 归档 SHA-256 | 476b2038a518c2e8fedf234ada62ef86db5d151549e8a5e858705e811773b600 |

工具从 [Fabric v2.5.16 官方发行](https://github.com/hyperledger/fabric/releases/tag/v2.5.16)下载，其 SHA-256 与 GitHub 官方发行元数据一致。samples 通过实际 git ls-remote 解析 main 后固定完整 SHA，再获取该提交归档，不声称存在同名 v2.5.16 样例标签。已实际执行主机 peer version、容器 peer version 和 orderer version，版本一致。

下载、二进制、证书及通道文件均位于忽略提交的 .local/fabric/；未更改全局工具或 JDK。准备脚本从固定官方配置生成两个业务组织及单个排序组织的身份，将 Org1/Org2 锚节点加入 genesis，并创建无系统通道的单节点 etcdraft 配置。

### 2. 运行配置及启动修正

最初的 Docker socket 挂载方案被自动审批拒绝，原因是 Peer 容器可借此控制宿主 Docker。该方案未执行；随后移除挂载和特权需求，使用本项目独立 Compose 启动。

第一次节点健康检查未通过：固定 Fabric 镜像没有 curl；换用镜像已有 Bash 查询内部 HTTP 后，又发现默认 Docker 构建后端因无 socket 返回不健康。最终依据官方 core.yaml 注释及 [2.5.16 节点源码](https://github.com/hyperledger/fabric/blob/v2.5.16/internal/peer/node/start.go)，将运行配置的 vm.endpoint 置空，保留默认外部链码构建器。修正后真实 `/healthz` 返回成功，三个容器均 healthy；未忽略失败组件或将失败检查写成通过。

最终配置无 Docker socket 挂载、无 privileged 模式、无全局容器清理。T005 仍须验证 Java 链码与外部运行方式的匹配，T004 不宣称已支持自动 Docker 链码构建。

### 3. 实际命令与通道结果

配置修正后完成以下流程，最终各步退出码为 0：

```bash
python3 deploy/fabric/prepare.py
python3 deploy/fabric/network.py up
python3 deploy/fabric/network.py channel
python3 deploy/fabric/network.py verify --output .local/fabric/before-recreate.json
python3 deploy/fabric/network.py remove
python3 deploy/fabric/network.py up
python3 deploy/fabric/network.py verify --compare .local/fabric/before-recreate.json --output .local/fabric/after-recreate.json
```

另在重建前重复执行 prepare 和 channel：已有身份、创世块及通道成员关系复用，没有重新生成材料或重复创建通道。重建后直接 verify，**没有再次调用 channel**，避免通过重新创建或加入通道掩盖账本丢失。

| 检查内容 | 实际结果 |
| --- | --- |
| 排序节点 | Participation API 使用 mTLS，mychannel 状态 active，consensusRelation=consenter，height=1 |
| Org1MSP | Peer 已加入 mychannel，height=1 |
| Org2MSP | Peer 已加入 mychannel，height=1 |
| 业务组织与共识 | 创世配置中只有 Org1MSP、Org2MSP 两个业务组织；共识 etcdraft，consenters 数量 1 |
| 区块读取 | 分别以两个组织管理员身份从 Orderer 取回 block 0，其区块头与原创世块一致 |
| Peer 账本信息 | 两个 Peer 的 getinfo 实际高度及 currentBlockHash 一致 |
| 重建后比较 | 通道、三个节点高度、Peer 当前区块哈希、创世头、创世文件摘要及证书指纹全部保持一致 |

两个 Peer 的实际 currentBlockHash（CLI Base64）均为：

```text
FGEK7VBqFPes3NJf8JprH59kS5Q6o2mKNXqsUpppDcE=
```

原创世块文件 SHA-256 为 `49d72ac9fff081581626f9dcd48b2f0e81753514a7d6b8eb0a758eb1c3904a44`，开发证书集合指纹为 `5f2f652c8f6045781536c9130c9567cac6e278d82a9eea209c031a25ec7937ad`。文件摘要与账本区块哈希是不同字段，不混用。

before-recreate.json 的记录时间为 2026-10-19T15:01:30.646517Z；after-recreate.json 为 2026-10-19T15:02:57.480534Z。报告保存在 .local/fabric/，不包含私钥内容。

### 4. 账本卷及最终运行状态

remove 只执行本项目 Compose down，移除三个容器及网络，保留账本卷；up 沿用原证书和卷。实际容器标识已变化：

| 节点 | 重建前 ID | 重建后 ID | 最终状态 |
| --- | --- | --- | --- |
| Org1 Peer | d59e0c23ee6c | 4008454e54d1 | healthy |
| Org2 Peer | 1ca86f50f8d9 | 9c6fa8fa3fb9 | healthy |
| Orderer | b5297bbdf978 | c355f91e162c | healthy |

已实际确认保留以下卷：uav-inspection-fabric_org1-peer-ledger、uav-inspection-fabric_org2-peer-ledger、uav-inspection-fabric_orderer-ledger。原组织证书与通道文件同时保留。节点及卷均按 uav-inspection-fabric 项目隔离，未运行官方样例的全局清理或删卷逻辑。

三个 Fabric 节点保持运行。日常 stop/up、restart、容器 remove/up 及快照比较方式见 [网络说明](../deploy/fabric/README.md)。本次实际完成的是容器移除后保留卷重建验证；restart 包装使用 stop/up，不将它表述为单独执行过的额外测试。

### 5. 下一步

进入 T005：固定并验证 Java 链码构建与运行方式，部署仅含 RegisterEvidence、QueryEvidence 的合约，并验证 OrgB 写入、OrgA/B 查询、幂等、冲突及拒绝越权登记。T006 再完成 Java Gateway 双机构真实调用和交易检查点验证。

## T005：最小 Java 链码与真实合约验证

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-19（Asia/Shanghai） |
| 任务 | [开发任务清单 T005](03-tasks.md) |
| 产物 | chaincode 独立 Maven 工程、合约与测试、Dockerfile；chaincode.compose.yml、部署和验证脚本；更新说明及设计 |
| 结果 | 50 项测试通过；evidence 0.1.0/sequence 1 部署成功；普通用户真实登记、查询、幂等、冲突及越权拒绝通过，T005 完成 |
| 当前边界 | 本次通过 CLI 验证真实 Fabric 合约，尚未接入 Gateway Java；交易序列化及确认恢复属于 T006，M1 未通过 |

### 1. 构建与运行基线

使用本机 JDK 21.0.12、Maven 3.9.16，独立链码固定 Java shim 2.5.11、org.json 20260814、JUnit Jupiter 5.13.4 和 Mockito 5.20.0；Peer、Orderer 和 CLI 延用 T004 的 Fabric 2.5.16。shim 与节点采用各自的发行版本，未假定二者版本号必须相同。

运行入口是官方 ContractRouter，使用 Java shim 原生 CaaS server 模式。[官方 Java CaaS 说明](https://hyperledger-fabric.readthedocs.io/en/release-2.5/cc_basic.html)、[shim 2.5.11 发行](https://github.com/hyperledger/fabric-chaincode-java/releases/tag/v2.5.11)

| 固定产物 | 实际值 |
| --- | --- |
| JRE 镜像 | eclipse-temurin:21.0.12_8-jre-jammy |
| JRE RepoDigest | sha256:61d6c7b34d36aee3f45d043101259f97f3c6d428dc2a6f75513789983c5e254f |
| 本地链码镜像 | uav-inspection/evidence-chaincode:0.1.0 |
| 本地镜像 ID | sha256:b9b7ef7708ad7bf5409fc8f713fa6a680c8c4760b6d1869789298e5a19e5730b |
| 最终 JAR SHA-256 | 612301eb01625740f04dae8dae55fb1d8c1ce62f9f5bfb6f09ea1d81ed35bb17 |
| Org1 package ID | evidence_0.1.0:78095d05ad50c31b627df3a75121b2c2cd2f3ef568e478647d514d65c949207e |
| Org2 package ID | evidence_0.1.0:1552d309caf3639193376b2e86464ea1e850e23dadb152f62b4440d699e89591 |

两个连接包分别指向 evidence-org1:9999、evidence-org2:9999，所以 package ID 不同；两个实例使用同一份 JAR 和同一个已提交的合约定义。链码级背书策略实际解码为 2-of-2，两个 principal 分别为 Org1MSP/PEER、Org2MSP/PEER，即 AND('Org1MSP.peer','Org2MSP.peer')；两个 Peer 查询到相同定义，插件为 escc/vscc，无 Init 要求及私有集合。

Peer 未挂载 Docker socket。两个链码容器实际 USER=10001:10001、privileged=false、无挂载、无宿主端口。CaaS 内网连接使用明文，仅用于本机开发；Peer、Orderer 客户端 TLS 继续启用。服务 TCP 健康只代表可监听，合约可用性另由真实调用验证。

### 2. 最终执行与构建结果

在已选择 JDK 21 的项目根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f chaincode/pom.xml clean verify
python3 deploy/fabric/chaincode.py deploy
python3 deploy/fabric/verify-chaincode.py
```

首次测试运行通过；随后在受限沙箱离线复验时，Mockito 动态自附加 agent 被限制，测试报初始化错误。已改为 Surefire 启动时显式加载 Mockito agent，最终在沙箱内离线 clean verify 成功：Tests run=50、Failures=0、Errors=0、Skipped=0。测试覆盖 MSP 权限、鉴权顺序、规范参数和 int32 边界、空查询、证据/版本隔离、首次交易编号、重复与冲突不写入。

部署脚本的策略解码最初使用了错误的 Protobuf 名称，并在重复部署时误调用当前 sequence 的 checkcommitreadiness；这些失败没有记为成功。已按实际 CLI 输出修正为 protos.ApplicationPolicy、common.MSPRole，并只在尚未提交定义时检查 readiness。最终 deploy 退出码为 0，核对了两个组织的已批准 package ID 及实际策略。

为防止 CaaS 连接包未变化时静默替换 Java 代码，脚本在构建和启动前持久化 JAR/Dockerfile 摘要锁，并预检已有定义；部分部署失败也保留锁。Maven 固定输出时间戳并强制重新生成未着色 JAR，避免再次 package 时对已有 fat JAR 二次处理；同源码重复 package 的 SHA-256 已实测保持上述值。Python 语法检查与合并 Compose config --quiet 通过。

构建日志在 .local/fabric/chaincode/maven-verify.log；部署及锁分别在 deployment.json、artifact-lock.json。源码和本地构建版本由这些摘要关联，package ID 自身不绑定 Java 字节码。

### 3. 真实业务用例及交易证据

验证脚本业务调用全部使用双方 User1；部署审批使用 Admin。验证时间为 2026-10-19T15:21:38.175442Z，证据编号为：

```text
f00fb02d-05f3-47b2-b39c-c069aa4c000d
```

| 用例 | 实际结果 |
| --- | --- |
| 两机构查询未登记键 | found=false、record=null |
| OrgB 登记版本 1 | 两个 Peer 的对应区块验证码均为 VALID=0 |
| OrgA/B 查询版本 1 | 五字段及类型完全相同，submitterMsp=Org2MSP，首次 registeredTxId 与有效交易编号一致 |
| 同键同摘要再次提交 | 产生新的有效交易，但证据命名空间写集为空；查询仍返回首次交易编号 |
| 同键不同摘要 | 链码错误 payload=EVIDENCE_HASH_CONFLICT；提交前拒绝，双方账本高度和哈希不变，原记录未覆盖 |
| OrgA 登记新证据 | 链码错误 payload=FORBIDDEN；双方账本不变，目标键仍不存在 |
| version=01 | 链码错误 payload=INVALID_VERSION；双方账本不变 |
| 同证据版本 2 | 独立保存另一摘要，版本 1 的摘要和首次交易编号保持不变 |

| 交易 | 双方 Peer 区块号 | 实际交易编号 |
| --- | --- | --- |
| 版本 1 首次登记 | 4 | afbc661ea9688e3430faee065f04f7d7ebab34990ef877ab2dccd640180c2694 |
| 版本 1 同摘要重复 | 5 | b7b8a7c87e628791179b548d8cbd57768b0fed81b7eac10be504482acbb32315 |
| 版本 2 首次登记 | 6 | 9cc768e89181fd63f39cda633f47933fbc60081bb1b8c16588d57eb4257293d2 |

版本 1 摘要为 08e3b5cdebc7adbc02c35d2c093f69e5c805e3b5ec9be16f4487a939b533a53a；版本 2 摘要为 4eec2bd3647b2c4f6403a75acda771d90fbd24147e57d8768cbbfc33f7023409。载荷由验证脚本计算 SHA-256，未声称已完成无人机上传业务。

成功提交先等待两个 Peer 事件，再通过 QSCC GetBlockByNumber 读取双方已验证账本；CLI 使用 --hex 获取 protobuf 字节，解码后匹配合约、参数、调用组织、交易编号及交易验证码。Orderer 返回的块不带 Peer 验证结果，未用它代替有效提交证据。拒绝用例精确匹配链码错误 payload，连接故障不会被算作预期拒绝。

完整报告在 .local/fabric/chaincode/verification.json，保留两版记录、三个交易及双方回执。脚本按调用前高度定位交易块，应在没有其他并发提交的本地环境运行；本次没有测量并发性能或注入 MVCC 冲突。

### 4. 重复部署与最终状态

已在完成业务登记后记录 before-redeploy.json，再执行一次 deploy，随后核对 after-redeploy.json，并以双方 User1 重查原来的两版证据。结果为：

- 已安装包、批准及定义被复用，没有新增交易、重新创建通道或重新生成身份。
- 两个 Peer 和 Orderer 高度均保持 7；双方 currentBlockHash 均保持 iE1bhJmc9eLnmEi1HZpTN3jTIs55JBTfn2IFaGrYqb8=。
- 创世头及证书指纹保持不变；两版证据的全部字段及首次交易编号保持不变。
- 两个 CaaS 容器沿用已有实例；最终五个 Fabric 项目容器均 healthy，原四个业务基础设施服务未被此次部署操作。

管理脚本在 runtime.env 存在时自动合并链码 Compose，后续 stop/up/remove 统一管理本项目的节点和链码服务，remove 仍不删除卷。此次新增验证是重复部署保留现有账本与证据；未将它写成已经额外执行过的全网业务账本重建或应用检查点恢复。

### 5. 下一步

进入 T006：固定并接入 Gateway Java，使用 OrgB User1 提交并确认有效 commit，OrgA User1 查询；验证交易对象与确认请求的序列化和恢复 API，形成后续 UNKNOWN 状态恢复所需的真实检查点。T005 的 CLI 验证不代替 Java 调用验证，M1 尚未通过。

## T006：Java Gateway 双机构调用与检查点恢复

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-20（Asia/Shanghai） |
| 任务 | [开发任务清单 T006](03-tasks.md) |
| 产物 | backend/blockchain 适配与恢复校验、12 项边界测试、test-scope 四阶段 CLI 探针、verify-gateway.py、Maven fabric-probe profile 及说明 |
| 结果 | 后端 32 项测试通过；Java OrgB 有效提交、OrgA/B 查询、重复幂等及拒绝用例通过；四个独立 JVM 恢复交易和确认检查点，T006 完成，M1 通过 |
| 当前边界 | 文件检查点及 SDK API 验证；数据库尝试记录、Outbox、租约、提交响应丢失及完整 UNKNOWN 故障恢复留 T012/T024/T027，未完成业务 MVP |

### 1. 实际版本与连接方式

使用 Temurin 21.0.12+8、Maven 3.9.16、Spring Boot 4.1.1。本次实际解析和打包的相关库为：

| 库 | 实际版本 |
| --- | --- |
| org.hyperledger.fabric:fabric-gateway | 1.13.0 |
| fabric-protos | 0.3.7 |
| grpc-api/core/context/stub/protobuf/protobuf-lite/netty-shaded/util | 1.84.0 |
| protobuf-java | 4.36.2 |
| Gson（Boot 依赖管理） | 2.13.2 |
| Bouncy Castle bcpkix/bcprov | 1.86 |

gRPC 与 Protobuf BOM 在后端 POM 显式固定，Gateway 客户端与链码 shim 分开构建。核对了正式 1.13.0 Maven POM、实际 JAR API 和[该版本源码](https://github.com/hyperledger/fabric-gateway/tree/v1.13.0/java)，没有将 main 文档中的快照版本当成本次版本证据。

两个 Gateway 分别使用 Org1MSP/User1 和 Org2MSP/User1，连接 localhost:7051、localhost:9051；TLS 根证书来自原 Peer CA，server name 分别为 peer0.org1.example.com、peer0.org2.example.com。没有关闭 TLS 校验。启动器从已有 gateway-paths.json 读取身份路径，只允许本项目 User1 材料，不加载根目录 .env 或输出私钥。

生产侧以 EvidenceLedgerClient 分开 prepareRegistration、submitPrepared、queryCommit 和 queryEvidence。prepare 在背书后取得原 txId 与 transactionBytes；submitAsync 只等待提交 RPC，不代表有效提交；newCommit(...).getStatus() 提供实际验证码及块号；最终还需查询业务键核对五字段。

### 2. 构建、测试与默认启动

在选择 JDK 21 的项目根目录执行以下命令，最终退出码均为 0：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P fabric-probe clean verify
python3 deploy/fabric/verify-gateway.py
python3 deploy/fabric/network.py verify --output .local/fabric/gateway-probe/network-after-t006.json
```

Maven fabric-probe profile 生成 target/fabric-probe.classpath，构建阶段不会提交链交易；Python 启动器才运行真实探针。测试结果来自 Surefire：

| 测试 | 数量 | 失败/错误 |
| --- | --- | --- |
| 原 HTTP 契约测试 | 20 | 0/0 |
| 新检查点边界测试 | 12 | 0/0 |
| 合计 | 32 | 0/0 |

新增测试用 protobuf 构造数据，不访问网络或真实密钥，覆盖有效交易/确认检查点、跨组织、同 MSP 不同证书、错误通道/链码、外部/protobuf/header 的 txId 不一致、空白编号、损坏编码及凭据数组被修改等情况。恢复校验不是只比较 MSP，也不是仅信任检查点外围 JSON 的 txId。

已检查可执行 JAR 包含生产适配与上述正式依赖，不包含 test-scope FabricGatewayProbe。另实际启动 prod 可执行 JAR，使用随机本机端口，显式将 FABRIC_ORGA_CERT_PATH 设为不存在的路径：应用仍正常启动，/api/v1/system/status 返回 OK/RUNNING，随后关闭该进程。证明默认 Web 启动未读取 Fabric 身份；未把状态接口的 RUNNING 当作链健康。日志在 .local/fabric/gateway-probe/packaged-startup.log。

### 3. 四 JVM 检查点实测

本次运行目录为：

```text
.local/fabric/gateway-probe/20261020T030326Z-cb3d70ca/
```

业务证据编号为 76768f53-ec5b-40f7-b1aa-080c430f5447，版本 1，文件摘要为 7d93242720f120408ea1321e5ff339d0a950d13de1605568e16dca42edf89a7d。时间记录采用 UTC：

| 阶段 | 实际 JVM PID | 完成时间 | 结果 |
| --- | --- | --- | --- |
| prepare | 2480605 | 2026-10-20T03:03:28.206861010Z | 双方查询为空；OrgB 背书后保存交易及 PREPARED，尚未提交，进程退出 |
| submit | 2480685 | 2026-10-20T03:03:30.432551026Z | 从磁盘恢复原交易；提交前 OrgA 仍查不到；保存 commit.bin 和 SUBMITTED 后退出 |
| confirm | 2481069 | 2026-10-20T03:03:32.881459728Z | 从磁盘恢复确认请求，验证码 VALID=0、块号 7；双方查询五字段相同后保存 CONFIRMED |
| verify | 2481193 | 2026-10-20T03:03:37.414276394Z | 再次恢复原确认请求，并完成重复、冲突及越权用例 |

原交易编号在所有阶段保持：

```text
79f32f3bd15a06768cccc2a88994fe6461fa98bbacba0951ce569a39d03e4503
```

| 持久化检查点 | 长度 | SHA-256 |
| --- | --- | --- |
| transaction.bin | 4630 bytes | bc0fab7fd6e4e743d2d9c79db48ccd8a3742687d094cd4fa29375710e25893ef |
| commit.bin | 905 bytes | 63474b7b80f5470adfa8c2d859f484db26a0bb6d4dc033f02a88bae07d1f1fad |

每次启动都会新建 Gateway 与 gRPC Channel，不复用上一进程内的交易对象。检查点使用临时文件、fsync 和 atomic move 保存，恢复时核对长度及 SHA-256；上述文件和 run.json、verification.json 的实际权限均为 0600，运行目录为 0700。JSON 仅保存摘要与元数据，不打印二进制正文或私钥。

正式 SDK 的 getBytes() 序列化当前对象，并不保证已包含客户端签名：本流程交易在 submitAsync 时、确认请求在 getStatus 时由原身份签名。Gateway 重建对象不自行验证原签名身份，因此适配层核对原 MSP、证书、通道和 txId；恢复交易还检查链码名。证书和对应私钥需要保留，不能仅凭 txId 调用 newCommit 恢复查询。

本次验证的是各阶段正常退出后的磁盘恢复；没有注入断电、进程强杀或提交响应丢失，也未验证数据库检查点原子性。

### 4. 实际业务用例

| 用例 | 实际结果 |
| --- | --- |
| OrgB User1 登记 | 恢复原交易后提交有效；OrgB commit 状态 VALID=0、block=7 |
| OrgA/B 查询 | 编号、版本、摘要、submitterMsp 和 registeredTxId 全部一致，首次交易编号等于上述有效交易 |
| 同键同摘要新交易 | 新 txId=b1522a550d20cce08f3ba26589db1bae5f4947126998206439f08cb35f361517，VALID=0、block=8；双方记录仍保留首次 registeredTxId |
| 同键不同摘要 | EndorseException、ABORTED 及精确固定链码消息匹配，映射为 EVIDENCE_HASH_CONFLICT；原记录不变 |
| OrgA 登记新键 | EndorseException、ABORTED 及精确固定权限消息匹配，映射为 FORBIDDEN；目标键在双方仍不存在，原记录不变 |
| 已保存 commit 再次查询 | verify JVM 用同一个 commit.bin 取得相同原 txId、VALID 状态和块号 |

Peer Gateway 在错误详情中只转发链码状态和 message，不转发 ChaincodeException.payload。探针检查的完整消息分别为：

- chaincode response 500, A different file hash is already registered for this evidence version.
- chaincode response 500, The calling organization is not permitted to perform this operation.

因此报告明确标注 mappedCode 来自固定 ErrorDetail.message 映射，未声称 SDK 直接取得 payload 业务码；只有 EndorseException、ABORTED、已知 Peer 和完整固定消息匹配才算拒绝用例通过。一般 RPC 错误、超时及其他 ABORTED 不作业务拒绝。[Fabric 2.5.16 Gateway 错误构造源码](https://github.com/hyperledger/fabric/blob/v2.5.16/internal/pkg/gateway/apiutils.go)

探针代码对无法确认的 RPC 保留检查点和 UNKNOWN，明确无效回执保存 INVALID；有效 commit 后先保留 VALID 事实，查询核对成功才标记业务 CONFIRMED。本次实际执行了有效及业务拒绝路径，没有把未注入的网络故障分支记为已实测通过。

### 5. 最终状态及里程碑

verification.json 的 passed=true，验证时间为 2026-10-20T03:03:37.417862423Z；latest.json 指向该报告并记录 JVM 路径与实际 Gateway 库版本。最终网络核对两个 Peer 和 Orderer 高度均为 9，双方 currentBlockHash 为：

```text
2RFWFvzhEC52vMk0m1l1jty34ZKOduK8BoZusWAHChY=
```

现有四个业务基础设施容器、三个 Fabric 节点和两个 CaaS 实例均 healthy。本轮未重建通道、身份、账本卷或链码；只增加探针实际提交的两笔有效交易。T005 的历史证据继续保留。

T001～T006 的技术验证条件均已满足，M1 通过。下一项为 T007：引入数据库客户端与 Flyway，建立空库/重复启动均可执行的迁移，以及最小双机构初始化数据。完整业务闭环、AC-01～AC-12 和故障恢复验收仍按后续任务推进。
