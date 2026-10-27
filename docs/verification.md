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

## T007：数据库迁移与最小初始化数据

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-21（Asia/Shanghai） |
| 任务 | [开发任务清单 T007](03-tasks.md) |
| 产物 | 五表 V1 迁移、MyBatis Mapper、UTC TypeHandler、database/demo 配置、初始化与启动脚本、真实数据库探针及说明 |
| 结果 | 空库迁移、演示初始化、重复启动、24 项约束回滚及 UTC 毫秒往返通过；后端原有 32 项测试及打包启动通过，T007 完成 |
| 当前边界 | M1 已通过，M2 仅完成数据基础；登录/角色接口在 T008，任务接口在 T009，未创建业务任务或伪造完成状态 |

### 1. 实际依赖与数据模型

本机继续使用 Temurin 21.0.12+8、Maven 3.9.16、Spring Boot 4.1.1 及现有 MySQL 8.4.11。实际可执行 JAR 包含：

| 库 | 实际版本 |
| --- | --- |
| mybatis-spring-boot-starter / autoconfigure | 4.0.0 |
| mybatis / mybatis-spring | 3.5.19 / 4.0.0 |
| Flyway core / mysql | 12.4.0 |
| MySQL Connector/J | 9.7.0 |
| HikariCP | 7.0.2 |
| spring-security-crypto | 7.1.1 |

MyBatis Starter 4.0.0 显式固定，其余由 Boot 父 POM 管理。仅引入 BCrypt 所需 crypto 模块，未启用 Web Security；原 HTTP 行为未变。[MyBatis 官方兼容表](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)、[Flyway MySQL 模块说明](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql)

V1__identity_and_task_scope.sql 建立 organization、app_user、inspection_task、task_member、drone。迁移只预置 OrgA/Org1MSP 和 OrgB/Org2MSP 两个固定机构；账号与设备须显式启用本地 demo，任务与成员仍为空。

约束覆盖机构映射、大小写敏感的唯一用户名、BCrypt 格式、固定且不重复的 JSON 角色数组、机构角色白名单、委托方创建人复合外键、固定任务双方机构、设备摘要、非负版本和 RESTRICT 历史引用。所有表使用 InnoDB，业务时间为 DATETIME(3)，身份 UUID 使用 ascii_bin。

### 2. 首次空库迁移与执行流程

迁移前通过 MySQL 应用账号读取 information_schema，业务 schema 没有表；原服务为 8.4.11、全局时区 +00:00。没有删除原数据库或重建卷。

先运行实际打包应用 local,database，不启用 demo。日志 .local/database-probe/first-packaged-migration.log 明确记录：

- flyway_schema_history 尚不存在，当前版本为 Empty Schema。
- 2026-10-21T10:01:54.611+08:00 开始 V1 迁移。
- Successfully applied 1 migration，当前版本 v1；随后真实 HTTP 状态接口返回 200/OK。

应用检查后正常停止。MySQL DDL 不具备整份迁移的事务回滚能力，未声称迁移失败可以自动回滚；已应用 V1 不再修改，后续结构变化追加新版本。Flyway validate-on-migrate 开启，baseline-on-migrate=false、clean-disabled=true，不使用手工建表替代迁移。

在已选择 JDK 21 的项目根目录执行：

```bash
python3 deploy/scripts/init-demo-credentials.py
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
```

原 20 项 HTTP 契约及 12 项 Fabric 检查点测试均通过，合计 32 项、失败/错误/跳过均为 0。数据库探针是显式 CLI，不计入 JUnit 数量，也不在普通构建时自动连库。新增探针完成后另执行 test-compile 验证编译；日志分别在 .local/t007-maven-verify.log 和 .local/t007-probe-compile.log。

### 3. 实际三阶段报告

运行目录为 .local/database-probe/20261021T020400Z-0600c978/，migrate、seed、verify 均由独立应用进程完成并输出 passed=true：

| 阶段 | PID | UTC 完成时间 | 机构/用户/任务/成员/设备行数 |
| --- | --- | --- | --- |
| migrate（无 demo） | 140920 | 2026-10-21T02:04:03.863508105Z | 2 / 0 / 0 / 0 / 0 |
| seed | 141085 | 2026-10-21T02:04:09.733372957Z | 2 / 6 / 0 / 0 / 1 |
| verify（再次启用 demo） | 141638 | 2026-10-21T02:04:16.124534285Z | 2 / 6 / 0 / 0 / 1 |

三次应用启动的 Flyway 历史均只有一条成功 V1，checksum=-539134757，validate 成功。该值是 Flyway 的有符号整数校验值，不是 SHA-256。

seed 与 verify 对五张表的全部字段排序后计算的整体 SHA-256 完全相同：

```text
d870185664ccb78f71abaace3865d50ce6b9941190a7fce7fa798b0f1b5fabec
```

因此不仅行数相同，密码摘要、设备摘要、角色、版本、启用状态和创建/更新时间也未被重复初始化改写。凭据文件 SHA-256 同样保持不变；报告保存整体摘要，不打印单个密码摘要或设备令牌。

IdentityMapper 实际读取六个固定账号并验证 BCrypt.matches；DroneMapper 实际读取 UAV-DEMO-001 并验证其 token_hash 等于本地随机令牌的 SHA-256。设备 last_seen_at 保持 NULL，用户 token_version/lock_version 及设备 lock_version 初始为 0。五表扫描未发现本次生成的任何明文演示密码或设备令牌。

### 4. 真实约束及 UTC 检查

verify 执行 24 项数据库拒绝检查，包括重复用户名、非法/重复/非数组/空角色及跨机构角色、错误任务双方机构及创建者、悬空或重复成员、重复设备编号、非法设备/密码摘要、负版本，以及删除被引用的机构/任务/用户。

每项只接受预定的 MySQL vendor code 和 SQLState：唯一键 1062/23000，CHECK 3819/HY000，悬空外键 1452/23000，引用删除 1451/23000。正常临时任务/成员准备操作在预期失败捕获区之外，连接失败或准备失败不能被算作约束通过。

所有约束探针及时间写入使用回滚事务，之后重新计算五表全字段摘要，确认与之前相同；未留下测试任务、成员或修改后的业务值。

| 时间检查 | 实际结果 |
| --- | --- |
| JVM 默认时区 | Asia/Shanghai |
| Hikari 会话时区 / 隔离级别 | +00:00 / READ-COMMITTED |
| 输入 Instant | 2026-10-21T00:00:00.123Z |
| MySQL 原始 DATETIME(3) | 2026-10-21T00:00:00.123 |
| 按列号及列名读回 Instant | 都与输入完全相同，毫秒保留 |
| NULL 字段 | 写入及读回均保持 NULL |

此项使用实际生产 UtcInstantTypeHandler 和真实 JDBC 连接，不以 Clock.systemUTC 或 JSON 配置推断数据库正确。[Connector/J 时间属性](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html)

### 5. 初始化限制与打包启动

演示初始化仅允许 local,database,demo；静态 profile guard 在常规 Bean 初始化前检查，任何 prod+demo 组合均拒绝。已直接运行可执行 JAR 的 prod,database,demo 组合，观察到预期拒绝并非零退出；日志为 .local/database-probe/prod-demo-rejected.log。

还实际验证了：

- 普通 prod、不提供 DB 凭据：可执行 JAR 启动，状态接口 HTTP 200；日志 plain-prod-startup.log。
- run-backend.py --demo：读取本地 .env 的数据库设置，启动实际打包 Web 应用，复用六账号一设备并返回 HTTP 200；检查后停止应用，日志 demo-packaged-startup.log。
- 再次运行 init-demo-credentials.py：保留原文件，内容摘要不变。
- 运行 JAR 包含 V1 迁移和数据库依赖，不包含数据库/Fabric 的 test-scope 探针。

数据库连接使用 Connector/J sslMode=REQUIRED；连接 URL、用户名和密码分别配置，密码只通过进程环境传递，未放入命令行。启动脚本按字面读取配置，不执行 .env 中的 shell 内容；MyBatis 参数日志关闭，凭据 JSON 解析失败不附带原内容或 cause。

本地 .local/demo-credentials.json 实际权限为 0600，保存随机密码及设备令牌，不提交版本库；三阶段报告和日志也写入忽略目录。数据库仅保存 BCrypt cost=12 及设备令牌 SHA-256。保留原凭据文件，重复初始化校验而不覆盖；没有创建默认公共密码，也没有提前实现登录或设备认证接口。

T007 已完成。M1 仍为通过状态，M2 尚未完成；下一项 T008 基于这些真实数据实现登录、退出、me、机构内角色修改和 tokenVersion 校验。

## T008：用户认证与机构内角色管理

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-21 |
| 任务 | [开发任务清单 T008](03-tasks.md) |
| 产物 | 四个身份接口、Spring Security/Nimbus JWT、数据库当前身份校验、角色 CAS、V2 审计表、调用示例及认证探针 |
| 结果 | 56 项后端测试、48 次真实 HTTP 调用及 V1/V2 迁移复验通过；原演示数据和凭据保留，T008 完成 |
| 当前边界 | M1 已通过；M2 完成 T007、T008，任务成员及资料权限从 T009 起实现，完整业务闭环尚未完成 |

### 1. 实现与运行

database 模式提供 POST /api/v1/auth/login、POST /api/v1/auth/logout、GET /api/v1/users/me、PUT /api/v1/users/{id}/roles。密码使用已有 cost=12 BCrypt；登录返回 30 分钟有效的 HS256 Bearer JWT，业务声明仅含用户编号和 tokenVersion，机构与角色每次从数据库读取。角色修改锁定调用者和目标后重验管理员权限、机构及版本，成功同时递增目标 tokenVersion/lockVersion；退出只更新请求原 tokenVersion，不影响之后创建的新版本会话。

使用 Boot 管理的 Spring Security 7.1.1 Resource Server 和 Nimbus，没有自写签名算法。JWT_SECRET 是原 .env 中生成并保留的 64 位小写十六进制密钥，通过进程环境传递。JWT 锁定 HS256、typ=JWT、issuer、audience，校验必填声明、规范 UUID、非负整数版本、未来 iat、最长 30 分钟及精确到期边界，clock skew=0。签发响应 expiresAt 与 JWT 秒级 exp 一致。

新增 V2__identity_audit.sql，成功登录、实际生效的退出、角色修改分别写 AUTH_LOGIN、AUTH_LOGOUT、USER_ROLES_CHANGED；修改及成功审计处于同一事务。审计不含密码、BCrypt 摘要、JWT 或请求正文，应用 Mapper 只提供追加；审计查询留待 T021。HTTP/Security 诊断日志保持 INFO，密码和令牌 DTO 的 toString 脱敏。认证过滤器与 MVC 数据库异常都返回固定 503，数据库诊断仅记录类型，未将账号数据库故障放行为匿名访问。

复现命令（项目根目录，已选择 JDK 21）：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-auth.py
python3 deploy/scripts/run-backend.py --demo
```

最后一项用于手动启动；认证探针会自行启动随机端口的 Web 应用，无需先占用 8080。[HTTP 示例](../examples/t008.http)使用占位凭据，由本地忽略配置提供实际值。普通 local/prod 无 database 模式仍可独立运行基础接口，不注册身份控制器，也不生成默认用户名/密码。

### 2. 构建及故障边界测试

最终构建日志：`.local/t008-build.log`，JDK 21、Maven 3.9.16、Boot 4.1.1。Surefire 在启动时加载 Mockito agent。首次受限沙箱回归因禁止绑定本地监听端口而失败；获执行权限后在本机随机端口运行成功，最终 clean verify 结果如下：

| 测试 | 数量 | 主要验证 |
| --- | --- | --- |
| HttpContractTest | 20 | 原 HTTP 响应、异常、traceId 及基础模式回归 |
| CheckpointValidatorTest | 12 | 原 Fabric 检查点作用域与恢复边界回归 |
| JwtTokenServiceTest | 14 | 真实 Nimbus 签发/解码、篡改/错误密钥、算法/类型、声明、时间和密钥边界 |
| IdentityServiceTest | 6 | 锁后降权/旧版本、跨机构、过期 lockVersion、CAS 失败事务回滚、延迟退出保留新版本 |
| SecurityFailureContractTest | 4 | 实际 Bearer 过滤器及 MVC 的统一 401/503、traceId 与敏感诊断隐藏 |
| 合计 | 56 | 失败 0、错误 0、跳过 0 |

4 项故障契约测试通过受控替身抛出数据库异常，经过真实 Security 过滤器或 MVC 处理；未停掉共享开发 MySQL。特别验证 Spring 默认失败处理器不会将 AuthenticationServiceException 重抛为 Servlet 500：显式配置失败处理器后返回 DEPENDENCY_UNAVAILABLE/503，401 使用固定 Bearer challenge。

打包检查确认可执行 JAR 含 V1/V2 迁移和身份控制器，不包含数据库、认证及 Fabric 的 test-scope 探针。Python 启动脚本语法检查与 git diff --check 通过。

### 3. 迁移和原演示数据复验

三阶段报告目录：`.local/database-probe/20261021T094625Z-0f42193e/`。migrate、seed、verify 为三个独立 JVM，均 validate 成功；历史现在包含成功 V1 和 V2，完整迁移校验和在重复进程间一致：

| 迁移 | Flyway checksum |
| --- | --- |
| V1__identity_and_task_scope.sql | -539134757，保持 T007 原值 |
| V2__identity_audit.sql | 1185045295 |

五张原业务表完整快照仍为 `d870185664ccb78f71abaace3865d50ce6b9941190a7fce7fa798b0f1b5fabec`，与 T007 相同。24 项约束拒绝检查及 UTC 毫秒/NULL 往返再次通过；JVM 为 Asia/Shanghai，MySQL 会话为 +00:00、READ-COMMITTED。未清库、未重建卷、未重写 V1 或随机凭据文件。

### 4. 真实 HTTP 与 MySQL 认证验收

报告：`.local/auth-probe/20261021T094659Z-8193886c/report.json`。运行开始为 2026-10-21T09:46:59.824059048Z，完成为 09:47:10.025803041Z。探针直接启动实际 Servlet 应用，连接 MySQL 8.4.11，不启用 demo；创建 6 个随机账号及仅存内存的随机密码，覆盖双机构管理员、普通用户、角色目标及停用账号。

总计 48 次真实 HTTP 请求、522 次断言；断言次数不计为独立测试数。以下情况均按预期通过：

| 场景 | 实际结果 |
| --- | --- |
| 正常登录及 me | 200；返回字段无密码/摘要，JWT 仅含约定声明，30 分钟 exp 与响应一致 |
| 错误密码、不存在账号、停用账号 | 统一 401，错误内容相同 |
| 匿名业务访问或篡改签名 | 401；统一错误结构、traceId 和 Bearer challenge |
| 登录伪造 orgId、roles、tokenVersion | 未知字段统一 400 |
| 非管理员、跨机构或不可管理账号 | 403，不产生角色变更成功审计 |
| 机构不适用、空、重复或未知角色 | 400，不改变目标用户 |
| 双机构管理员修改本机构角色 | 200；数据库角色与响应一致，两个版本均递增 |
| 角色修改、管理员自行降权 | 原令牌 401；降权后重新登录的令牌无法管理角色，返回 403 |
| 旧 lockVersion 及两个并发角色请求 | 旧版本 409；并发请求恰为一个 200、一个 409，两个版本各只增一次，仅一条成功审计 |
| 同版本两枚不同令牌退出 | 一次退出使两枚令牌都 401；再次退出 401；重新登录可正常访问 |
| 数据库停用账号 | 已签发令牌下一次请求立即 401 |
| 成功审计 | 14 条本次审计保留，操作者/机构/动作/资源/traceId 对应，无密码、JWT 或凭据摘要 |

finally 清理本次 6 个测试账号，原业务五表全字段快照核对一致。验证前后都是 2 个机构、6 个演示账号、0 个任务、0 个任务成员和 1 台设备；审计追加保留。启动器另核对原凭据文件 SHA-256 未变，文件权限为 0600；本地核查认证日志未含原数据库密码、JWT 密钥、演示密码/设备令牌或 JWT 字符串。日志留在忽略目录，未复制原文到文档。

T008 已完成。下一项 T009 实现任务、必交资料、成员维护及初始执行批次；完整资料权限、设备认证、上传、消息和正式业务存证继续按任务清单推进。

## T009：任务、资料要求、成员与初始执行批次

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-21 |
| 产物 | 九项任务操作、ResourcePermissionService、V3 三表、事务审计、HTTP 示例及真实任务探针 |
| 结果 | 后端 66 项测试、90 次真实任务 HTTP 调用、35 项数据库约束检查通过；既有认证探针 48 次调用回归通过 |
| 当前边界 | 已实现 DRAFT → ASSIGNED → RUNNING；T009 完成，M2 尚未完成，下一项 T010 私有存储及单分片上传 |

### 1. 实现和复现

database 模式新增任务创建/列表/详情、整批资料要求维护、运营商分配、成员维护、子任务创建、初次分配设备和启动。接口及请求字段见 [后端说明](../backend/README.md)和 [T009 HTTP 示例](../examples/t009.http)。所有任务写入与对应成功审计处于同一事务，七种动作均已实现；生成的证据编号、执行编号及用户机构不由客户端指定。

权限以数据库当前账号、业务角色和显式成员为准，ORG_ADMIN 不能自动读取任务。已有任务先锁父任务，再重查并锁定操作者；MyBatis 的 local-cache-scope=STATEMENT 避免重查权限复用会话旧值。成员维护只管理本机构，保留任务创建人和另一机构成员；列表在 SQL 中先过滤范围，再计算 total 与分页。

初次分配只创建一个 READY 批次。启动验证非空子任务、每个子任务至少一项必交资料、所有资料已绑定、每个当前批次为 READY、设备启用且机构正确，然后更新原批次为 RUNNING。设备行锁按编号去重排序获取；任务、子任务及执行版本相应递增。执行分配请求使用子任务 lockVersion，其他关键写使用任务 lockVersion。格式非法的 UUID 在 SQL 前返回 400；合法但不存在或不可访问的任务资源统一 403。

在项目根目录选择 JDK 21，使用现有 MySQL、.env 和演示凭据文件：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-tasks.py
python3 deploy/scripts/verify-auth.py
python3 deploy/scripts/run-backend.py --demo
```

各探针自行启动应用，任务和认证探针使用随机 HTTP 端口。数据库约束探针要求保留演示基线；任务探针使用独立随机账号及任务，并比较运行前后全部八张业务表。手动 HTTP 示例则通过正式接口创建并保留任务，供后续上传使用。

### 2. 构建和事务边界

最终日志为 `.local/t009-build.log`，JDK 21、Boot 4.1.1，clean verify 成功：

| 测试 | 数量 | 验证范围 |
| --- | --- | --- |
| HttpContractTest / CheckpointValidatorTest | 20 / 12 | 原 HTTP 及 Fabric 检查点回归 |
| JwtTokenServiceTest / IdentityServiceTest | 14 / 6 | 原 JWT、角色及退出版本回归 |
| SecurityFailureContractTest | 5 | 原 401/503 故障契约及认证后撤销仍返回 Bearer challenge |
| ResourcePermissionServiceTest | 5 | 机构管理员不自动获权、业务角色仍需成员、读写角色区分、重读数据库身份及锁后版本失效 |
| TaskServiceTest | 4 | 子项修改后父 CAS 失败回滚、审计失败回滚、READY 原编号启动、锁后角色撤销拒绝写入 |
| 合计 | 66 | 失败 0、错误 0、跳过 0 |

事务边界单测使用真实 Spring TransactionInterceptor 和记录型事务管理器验证提交/回滚决策，持久化及并发由真实探针验证。401 的 MVC 业务异常补齐固定 WWW-Authenticate: Bearer，与安全过滤器一致。打包检查确认 V1/V2/V3 均进入可执行 JAR，test-scope 探针不进入应用 JAR；Python 语法检查及 git diff --check 通过。

### 3. V3 与真实数据库约束

报告目录：`.local/database-probe/20261021T125352Z-dc1568fe/`。三个独立 JVM 完成 migrate、seed、verify，完整迁移校验和与重复启动结果一致：

| 迁移 | Flyway checksum |
| --- | --- |
| V1__identity_and_task_scope.sql | -539134757，保持原值 |
| V2__identity_audit.sql | 1185045295，保持原值 |
| V3__task_execution_scope.sql | -394507728 |

V3 建立 inspection_subtask、material_requirement、task_execution。复合外键限制资料项与子任务同任务、当前执行与子任务一致；生成列唯一键限制每子任务一个 READY/RUNNING 批次，同时允许 CLOSED 历史保留。没有修改已应用的 V1/V2，也没有重建数据库或卷。

原 24 项约束检查保留，新增 11 项：跨任务资料绑定、他子任务批次指针、第二活跃批次、READY 带开始/关闭时间、RUNNING 缺开始或带关闭时间、负进度、进度超过 100、零版本分配值、重复稳定证据编号。分别核对 MySQL 1452/1062/3819 与 SQLState，合法 setup 在预期异常 catch 之外，每项事务全部回滚，总计 35 项通过。

快照范围扩展到八张业务表全部字段，包含执行生成列；三个进程重复校验、约束检查和 UTC 往返后数据一致。当前八表快照 SHA-256 为 `e99f03d15ecbe8874f27a5b700a7469aeec4a8b7bc593d4ae57f421e17f4eec8`。该值使用新的八表摘要范围，不与 T007 的五表摘要直接比较。UTC 毫秒及 NULL 往返再次通过，JVM 为 Asia/Shanghai，MySQL 会话为 +00:00 / READ-COMMITTED。

### 4. 真实 HTTP 任务验收

最终任务报告：`.local/task-probe/20261021T130046Z-49a622ce/report.json`，2026-10-21T13:00:46Z 至 13:00:56Z。通过真实 Servlet、HttpClient、MyBatis 和 MySQL 执行 90 次请求、639 次断言；断言次数不计为独立测试数。使用本次随机 7 个账号（含 ORG_ADMIN-only）、2 台设备、3 个任务，未运行 demo 初始化。

| 场景 | 实际结果 |
| --- | --- |
| 委托方创建、维护要求、分配运营商、运营商细化/分配/启动 | 依次 DRAFT、ASSIGNED、RUNNING，创建人及指定运营商管理员自动加入成员 |
| 匿名、非成员、仅机构管理员、业务角色不足 | 分别按契约 401/403；有业务角色但非成员的列表为空 |
| 已有多个任务的分页 | 只返回本用户任务，total 在权限过滤后计算，无另一任务信息 |
| 两机构分别维护成员，含空列表 | 创建人及对方成员始终保留；跨机构候选被拒绝，移除成员后下一请求无任务范围 |
| 资料要求维护与绑定 | 现有 evidenceId 稳定；可在 ASSIGNED 修改未绑定内容，不能删除已绑定项；运营商修改要求被拒绝 |
| 伪造创建人、错误机构/候选、跨任务资料、重复编号、非法/Unicode UUID | 400；重复绑定及已绑定删除按契约 409，不出现数据库错误响应 |
| 空任务、无必交项、未全部绑定、缺少 READY、READY 设备停用 | 启动 409，不改变任务或批次 |
| 同版本并发成员维护 | 一个 200、一个 409，任务版本只递增一次，仅一条成功审计 |
| 同子任务并发初次分配 | 一个 200、一个 409，只有一个执行记录和当前指针，仅一条成功审计 |
| 启动、再次启动及运行中再次分配 | 启动复用原两个 READY 批次编号并设置开始时间；重复启动及再次分配 409，不新增执行 |
| 审计和响应 | 七种任务动作均验证，成功 trace 对应唯一审计；任务业务响应及审计不包含密码、设备摘要或 JWT |

该次保留 25 条安全审计（包含登录和任务写入），删除自己的 3 个任务、7 个账号、2 台设备及子记录。清理先置空 current_execution_id，再删除执行、资料项、子任务、成员和任务，最后删除账号/设备，未关闭外键。原八表全字段快照保持不变，运行前后均为 2 个机构、6 个演示用户、1 台设备，其余业务表为空。任务报告中的任务和批次编号属于已清理的测试数据，不能当作后续上传目标。

启动器确认原凭据文件不变，报告、日志及凭据权限为 0600；本地核查任务日志未含数据库密码、JWT 密钥、演示密码/设备令牌或 JWT 字符串。认证回归报告为 `.local/auth-probe/20261021T130228Z-09951cc2/report.json`，48 次调用通过，原演示五表及凭据保持不变。

T009 已完成。下一项 T010 接入私有对象存储及单分片上传，T011/T012 再接异步定版和正式业务存证；M2 尚未通过。

## T010：私有对象存储与上传入口

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-21 |
| 产物 | V4 上传/分片/版本表、S3 适配、上传会话/分片/进度接口、流式临时文件、真实上传探针及 PNG 示例 |
| 结果 | 72 项后端测试、68 次真实业务 HTTP 请求、49 项数据库约束检查通过，原十一表及凭据保留 |
| 当前边界 | T010 完成，上传仍为 UPLOADING、versionId=null；正式定版、完成接口、Outbox 由 T011 实现，M2 尚未完成 |

### 1. 实现与运行方式

启用 database、storage 后，提供 POST /api/v1/upload-sessions、PUT /api/v1/upload-sessions/{id}/parts/{partNo}、GET /api/v1/upload-sessions/{id}。创建请求包含 clientRequestId、requirementId、executionId、filename、contentType、size；规范 UUID、文件名/类型及大小先校验。用户须为 OrgB 的 OPERATOR_USER、任务成员和会话所有者；机构管理员或任务管理员身份不能单独获得上传权限。

StorageConfiguration 使用固定 AWS SDK Java 2.56.0、UrlConnectionHttpClient、静态原凭据及 Garage 2.4.1 私有桶。使用路径式地址、关闭 SDK 分块编码、WHEN_REQUIRED 校验和策略，并通过 RequestBody.fromFile 让 SDK 重试重读同一文件；业务摘要在此之前独立计算。实现参考 [SDK 上传请求体](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/best-practices-s3-uploads.html)与[校验和配置](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html)，实际兼容性由本次 Garage 验收证明。

UploadService 不持有数据库事务：短事务预检后，以固定缓冲流式接收至 0600 临时文件，限定精确长度并计算 SHA-256、检查首片类型特征，再写私有唯一对象键。独立 UploadTransactions Bean 在最终短事务中重新检查权限、任务/批次、所有者、状态、数据库时间期限及分片竞争；分片登记、会话版本递增和成功审计原子提交。首片特征检查不表述为完整文件解析，后者在 T011 定版阶段补齐。

创建幂等按 (actor_type,actor_id,client_request_id) 及长度前缀请求摘要判断，同请求返回原会话且不延长期限。上传同片同摘要返回原记录，不同内容 409；已冻结、过期或任务已完成均拒绝写入。最终提交异常时保留候选对象；只有事务正常返回并确认本次键未被引用时，才删除自己的竞争失败对象。T025 再实现按引用和保留期回收，未声称当前已具备完整孤立对象补偿。

项目根目录选择 JDK 21，复用现有 .env、演示凭据与业务服务：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-upload.py
python3 deploy/scripts/run-backend.py --demo
```

启动器默认启用 local,database,storage[,demo]，读取 DB_*、S3_*、UPLOAD_*、JWT_SECRET；原 .env 不重建，新增上传限制使用 application-storage.yml 默认值。数据库探针与旧认证/任务验证仍可使用 database-only 模式。手动示例见 [t010.http](../examples/t010.http)，先通过 T009 创建并启动任务，再将 operator-user 加入任务；附带 PNG 为 79 字节，SHA-256 为 `ec81ce39789f63a8f58cd7c6ecaef171b4b944d665ea92d79b2c92175ea2726f`。

### 2. 构建、事务和打包

最终构建日志 `.local/t010-build.log`：clean verify 成功，72 项测试、失败/错误/跳过均为 0。原 66 项 HTTP、JWT、权限、角色、任务事务与 Fabric 检查点测试保留，新增 UploadServiceTest 的 6 项关键边界：

- 存储调用经过 Spring 事务拦截验证处于事务外，实际读取临时文件，结束后文件删除。
- 最终提交结果不明时不删除候选对象，仍清理临时文件。
- 明确已存在另一个获胜对象时，只删除本次未被采用的候选。
- 请求体读失败时清理临时文件，未调用存储写入。
- 预检后会话冻结，最终登记重新检查并回滚。
- 预检后权限撤销，最终登记重新授权并回滚。

这些单测使用存储/Mapper 替身及真实 Spring 事务 advice 验证边界；真实持久化和网络由下节探针覆盖。JAR 检查确认 V1～V4、storage 配置及上传控制器均进入应用，test-scope 探针未进入 JAR。Python 语法检查、git diff --check 通过。

### 3. V4 与数据库复验

目录 `.local/database-probe/20261021T152431Z-a0e8a2d4/`，migrate、seed、verify 三个独立 JVM 全部通过。完整迁移校验和重复一致：

| 迁移 | Flyway checksum |
| --- | --- |
| V1 | -539134757，原值保留 |
| V2 | 1185045295，原值保留 |
| V3 | -394507728，原值保留 |
| V4__upload_sessions_and_versions.sql | -1021150431 |

V4 追加 upload_session、upload_part、evidence_version，资料项增加范围复合唯一键，复合外键限制任务/子任务/资料/证据/执行及未来版本会话匹配。原 35 项检查保留，新增 14 项涉及请求键唯一、摘要格式、范围外键、会话几何、租约成对、到期时间、分片编号/重复及对象键唯一、版本会话匹配/上传者互斥/同会话定版唯一，总计 49 项。setup 位于预期异常 catch 之外，检查准确 MySQL vendorCode/SQLState，全部事务回滚。

业务快照扩为十一表所有字段，domain=T010-full-eleven-table-snapshot-v1，SHA-256=`8793b5cd1fe6dc8e5e64c9781a6b29b16391a2abc035ceaf32403f11f919b044`。该范围与之前八表不同，不直接比较旧摘要。重复启动、约束回滚及 UTC 毫秒/NULL 往返后十一表一致。没有删除卷、清库或修改已应用 V1～V3。DDL 探针中的版本插入仅为回滚约束检查，不计作正式业务定版。

### 4. 真实 HTTP、MySQL 和 Garage 验收

最终报告 `.local/upload-probe/20261021T153630Z-581fedc1/report.json`，2026-10-21T15:36:31.184126523Z 至 15:36:42.634302327Z。报告记录 68 次业务 HTTP 请求、584 次断言；断言不计为独立测试数。探针使用 5 个随机账号和隔离设备，通过正式 T009 接口完成任务、两个子任务、成员和启动，再调用上传 API，未运行 demo 初始化。

| 场景 | 实际结果 |
| --- | --- |
| 创建会话、上传小 PNG、查询进度 | 单片数据进入 Garage，MySQL 中长度与 SHA-256 对应；进度为 UPLOADING，versionId=null |
| 同请求键重复或并发创建 | 两请求 200，复用同会话及 expiresAt，只一条创建记录和成功审计；不同请求内容 409 |
| 同片同内容重复/并发 | 两请求 200，同一分片主键、原对象和一次成功审计 |
| 同片不同有效 PNG 并发 | 一个 200、一个 409；唯一正式分片，获胜对象及摘要保持一致 |
| 未登录、无任务范围、非 OPERATOR_USER、非会话所有者 | 401/403，不返回上传记录或对象键 |
| 类型/扩展名不匹配、错误文件头、非法编号、长度错误 | 类型/短内容等 400，超长 413；不登记成功分片 |
| 过期、冻结、非当前执行及任务完成 | 按约定 409，未增加分片或成功审计 |
| 私有对象读回与匿名访问 | 通过真实 SDK 读取字节核对长度、SHA-256；匿名 HEAD 和 GET 均 403 |
| 基础两片几何/乱序进度 | 合法 PNG 8388747 字节、partSize=8388608，按第 2 片后第 1 片上传并查到进度；T017 全量续传/版本场景仍未完成 |
| 存储不可达 | 另起应用指向本机端口 1，PUT 返回 503，无分片/对象/成功审计；未停 Garage，正常应用可继续上传 |

过期/冻结/任务终态等负例仅修改本次隔离数据以验证拒绝路径，不作为业务完成或定版证据。上传探针产生 0 个 evidence_version，保留 24 条安全审计（含前置登录/任务操作）；清理自己的 7 个会话、关联分片、1 个任务、5 个账号、测试设备及 4 个私有对象，逐前缀确认空且 objectCleanupFailures=0，未列举或删除其他上传前缀。

清理后原十一表全字段快照不变，仍为 2 个机构、6 个演示账号、1 台设备，其余业务表为空。启动器验证原凭据文件不变，报告/日志/凭据权限为 0600。审计不含密码、JWT、存储凭据或对象键；本地另核查上传日志也不含原数据库/JWT/S3/演示秘密、JWT 字符串及分片对象键，未复制日志原文到文档。

T010 已完成。下一项 T011 实现完成接口、异步合并定版、Outbox 与 RabbitMQ；T012 再登记真实链摘要。M2 尚未通过。


## T011：异步文件定版与 Outbox 消息链路

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-22 |
| 产物 | V5 Outbox/消费/存证表、202 完成接口、流式合并校验与固定版本、消息发布确认、手动 ACK、持久重试扫描、隔离真实探针及 t011.http |
| 结果 | 99 项后端测试、42 次真实 HTTP/404 次断言、68 项数据库约束及租约条件更新检查通过；旧上传 68 次请求回归通过 |
| 当前边界 | T011 完成；正式版本已生成，但存证保持 PENDING，上链队列等待 T012 消费，M2 尚未通过 |

### 1. 实现与复现

完成接口为 POST /api/v1/upload-sessions/{id}/complete，无正文，返回 202 及 UploadProgress。首次请求在短事务内重查权限、所有者、任务/批次、有效期和全部分片，将 VERIFYING、FILE_FINALIZE_REQUESTED 与审计一起提交；缺片返回 UPLOAD_PARTS_INCOMPLETE/409。重复请求返回同一进度及已有 versionId，不追加事件或版本。

独立 Worker 在事务外按分片编号实际读取 Garage 字节，重新核对每片长度/摘要并计算完整摘要，校验文件后写私有最终对象。最终事务检查任务和有效租约，分配 next_version_no，并原子提交 evidence_version、PENDING chain_submission、EVIDENCE_ANCHOR_REQUESTED、上传 COMPLETED 及审计。上链事件 bizId 为 chain_submission.id；不存在伪造 CONFIRMED 或交易编号的路径。

PNG/JPEG 进行完整结构检查及受限解码（最多 2500 万像素）；PDF 检查头尾、startxref 及引用位置，MP4 检查 box 边界及基本容器结构。PDF/MP4 不包含完整文档渲染或视频解码。摘要/内容错误转 FAILED；临时文件或存储错误保留 VERIFYING 并保存 next_action_at。候选对象在数据库提交结果不明时保留，引用感知回收留 T025 实现。

Spring AMQP 4.1.1 由现有 Boot 父 POM 管理。Outbox 在事务外发送持久消息，等待 correlated confirm 且无 mandatory return，随后按发送租约条件写 SENT；超时或无法路由保留同一 eventId 退避。消费者在最终结果或持久恢复检查点之后才手动 ACK；非法消息进入持久死信队列，数据库无法提交时不 ACK，并关闭当前消费通道等待容器恢复。VERIFYING 扫描默认 10 秒一次，独立于 consumed_event.DONE；完整续租、统一存证扫描及系统故障矩阵仍由 T023/T027 完成。实现所依据的返回顺序见 [CorrelationData API](https://docs.spring.io/spring-amqp/docs/current/api/org/springframework/amqp/rabbit/connection/CorrelationData.html)。

复用原 .env、私有桶、RabbitMQ 与演示凭据，在项目根目录选择 JDK 21：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-upload.py
python3 deploy/scripts/verify-pipeline.py
python3 deploy/scripts/run-backend.py --demo
```

启动器启用 local,database,storage,messaging[,demo]，读取既有 RABBITMQ_* 以及可选 MESSAGING_*。默认 HTTP 模式和旧 database/storage 探针不连接 RabbitMQ。手动流程见 [t011.http](../examples/t011.http)，先通过 T009/T010 创建任务并上传全部分片，再请求完成并轮询。

### 2. 构建、迁移及回归

完整构建日志为 `.local/t011-build.log`：clean verify 成功，99 项测试，失败/错误/跳过均为 0。新增 18 项定版测试及 9 项消息测试，覆盖事务外存储 I/O、实际摘要、未知提交保留对象、失效租约拒绝、DONE 后恢复、分片完整性、confirm/return/NACK/确认未知态，以及 ACK/拒绝顺序。

V5__finalization_outbox_and_submission.sql 的实际校验和为 `-507899413`，保留 V1～V4。数据库最终报告 `.local/database-probe/20261022T150645Z-8a9d8a02/verify.json`：68 项约束通过；额外通过真实 OutboxMapper SQL 验证旧 token、到期 token、被替换的原持有者不能写 SENT 或重试检查点，有效持有者可以提交，测试写入全部回滚。重复 migrate/seed/verify、UTC 毫秒/NULL 往返和凭据保留均通过。

本轮全十四张业务表的摘要范围为 T011-full-fourteen-table-snapshot-v1，SHA-256=`6a8a27f713d374d37df21c36570f47717629adeeaa7f6e37c9aa6a153549de9a`。同时单独重算原十一表，仍为 `8793b5cd1fe6dc8e5e64c9781a6b29b16391a2abc035ceaf32403f11f919b044`；升级对比报告 `.local/database-probe/20261022T145652Z-2ffe9ebe/verify-with-leases.json` 与 T010 基线一致。

旧上传回归报告 `.local/upload-probe/20261022T150702Z-f80b64a2/report.json`：原 68 次 HTTP/584 次断言通过，证明仅 database/storage 模式的上传、权限、并发和 Garage 读写仍可用。断言数不计为独立测试数。

### 3. 真实 HTTP、MySQL、Garage 与 RabbitMQ 联调

最终报告 `.local/pipeline-probe/20261022T150509Z-73b9f4d6/report.json`，2026-10-22T15:05:10.096625694Z 至 15:05:22.642621149Z。真实 Servlet、MySQL、Garage 和 RabbitMQ 执行 42 次 HTTP 调用、404 次断言；使用随机账号、设备、任务和专属消息拓扑。为准确观察 202/检查点，探针关闭自动派发和自动扫描，并显式调用同一生产派发器及恢复处理器。

| 场景 | 实际结果 |
| --- | --- |
| 匿名、其他上传者、非成员、未知会话和缺片 | 401/403/409；缺片仍 UPLOADING，没有 Outbox |
| 并发完成及已冻结写片 | 两个 202，只一条定版事件；冻结后写片 409 |
| 两片倒序上传后由真实队列驱动 Worker | 合并字节、每片及完整 SHA-256 一致，只有一个版本，版本序号只递增一次 |
| 重复完成及重复消息 | versionId 保持一致，只有一条消费回执；消息重放不会增加版本或推进版本号 |
| 无法路由与发布确认 | 暂时解除本次 anchor 路由，真实 mandatory 返回使事件保留 PENDING；恢复路由后同事件 SENT |
| 损坏对象及截断 PNG | Worker 复算摘要或检查结构后 FAILED，各自没有正式版本及存证 |
| 临时读取故障 | 在真实 Garage 流上注入一次 IOException，持久化 VERIFYING/重试计划及 DONE；重放不绕过检查点，扫描实际恢复至 COMPLETED |
| 手动 ACK 与死信 | 单消费者、prefetch=1；同通道重放后发送坏 JSON，用后续死信到达作为处理屏障，证明重复消息已处理并 ACK；坏消息进入专属死信队列 |
| 后续上链事件 | 实际从持久 anchor 队列读取最小四字段 JSON 并检查持久标志；chain_submission 仍 PENDING、confirmed_tx_id 为空 |

实际生成 3 个版本（同一资料项第 1/2 版，另一资料项第 1 版），2 个内容确定失败；新版本未覆盖旧对象。WAITING_ACCEPTANCE → RUNNING 分支使用本次隔离任务的人工前置状态，只验证新版本回退规则，不代表 T019 验收流程已实现。退避时间在本次隔离数据中提前到期以触发恢复，没有手工写入业务成功状态。

保留 36 条安全审计；清理本次 1 个任务、5 个账号、6 个会话、9 个私有对象及专属 Exchange/队列，原十四表全字段快照和演示凭据一致。报告中的测试版本所属夹具已清理，手动演示应通过正式接口重新生成。

### 4. 打包启动与保留范围

正式 run-backend.py --demo 已用最终 JAR 启动，报告 `.local/t011-packaged.json`、日志 `.local/t011-packaged.log`：HTTP 200、请求标识一致，真实持久队列存在，定版队列有 1 个手动 ACK/prefetch=1 消费者，上链队列消费者为 0；原 .env 和演示凭据 SHA-256 不变，应用检查后正常停止。默认拓扑保留供后续开发使用。

本轮日志、报告和凭据为 0600；本地检查没有输出原数据库、JWT、S3、RabbitMQ 或演示秘密。未清库、删除卷、变更 Fabric 账本或重建凭据。T011 已完成，下一项 T012 接入真实业务存证消费及状态查询；T022～T027 的完整故障恢复与清理验收保持待开始。


## T012：真实业务存证、状态查询与原交易恢复

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-22 |
| 产物 | V6 交易尝试表、双机构客户端、数据库交易/确认检查点、真实上链消费与基础扫描、版本及存证查询、隔离真实探针与 t012.http |
| 结果 | 129 项后端测试、45 次真实 HTTP/407 次断言、87 项数据库约束通过；两个版本真实确认，确认响应丢失后的 UNKNOWN 原交易恢复通过 |
| 当前边界 | T012 完成；T013 文件/副本核验及 T014 最小闭环验收尚未完成，完整续租、人工重检及故障矩阵由 M4 验收 |

### 1. 实现与复现

`run-backend.py --ledger` 显式加入 ledger profile，创建 OrgA、OrgB 各一个可复用 Gateway，并启用上链队列的手动 ACK 消费者。默认启动器仍可独立运行 T011 上传和消息模式。local 缺少身份路径时读取原 gateway-paths.json 补齐普通 User1 路径；prod 要求显式身份路径。没有重建身份或覆盖 .env，TLS CA 和服务端主机名校验保持开启。

AnchorProcessor 的所有 Fabric RPC 均在数据库事务外。数据库短事务先锁任务、再锁存证，并领取 120 秒租约；后续写入核对租约 token 和数据库时间。交易经过 PREPARED（txId/transactionBytes 已提交）、SUBMITTING（提交意图已提交）、RPC 提交、SUBMITTED（commitBytes 已提交），然后才查询确认结果。有效确认并重查账本五字段一致后写 CONFIRMED；已存在同摘要有效记录时保存其首次 registeredTxId，明确摘要冲突不覆盖。

提交或确认不明时持久化 UNKNOWN、原尝试和下一次检查计划。恢复优先使用原 commitBytes；缺少确认检查点时复用原 transactionBytes 与 txId，不盲目新建交易。恢复身份/通道校验被拒绝也保持 UNKNOWN。只有原交易明确无效、属于允许重试的并发冲突，且业务键仍不存在，才允许新尝试。消费 DONE 与最终结果或持久恢复检查点一起提交后才 ACK；基础恢复扫描绕过 DONE。完整进程崩溃矩阵、续租及人工入口仍在 T023/T024/T027。

GET /api/v1/evidence-versions/{id} 返回固定版本元数据和 anchor，GET /api/v1/evidence-versions/{id}/anchor 返回存证状态、首次确认交易编号、最新尝试、验证码及区块号。查询只访问数据库，重查当前账号、业务角色及显式任务成员；未知或不可见版本统一 403。响应不包含对象键、下载地址、证书、私钥或检查点字节。

选择 JDK 21，复用原业务服务、Fabric 网络及 evidence 链码：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-pipeline.py
python3 deploy/scripts/verify-anchor.py
python3 deploy/scripts/run-backend.py --demo --ledger
```

接口示例为 [t012.http](../examples/t012.http)，先经 T009/T010/T011 生成固定版本，再查询版本及存证状态。UNKNOWN 表示等待检查，不能视为失败或有效确认。

### 2. 构建、迁移与旧链路回归

完整构建日志 `.local/t012-build.log`：JDK 21 clean verify 成功，129 项测试，失败/错误/跳过均为 0。本轮新增 22 项存证核心测试、4 项版本权限查询和 4 项上链 ACK 测试，覆盖检查点顺序、原交易恢复、有效结果与业务记录核对、冲突分类、数据库提交异常、失效租约、当前权限及安全 DTO。

V6__chain_attempts.sql 的实际校验和为 `186672578`，V1～V5 保持不变。chain_attempt 对 tx_id、(submission_id,attempt_no)、外键、阶段、非空二进制检查点、确认结果配对和安全错误码设置约束。最新数据库报告 `.local/database-probe/20261022T153352Z-8ca0dcfb/verify.json`：87 项真实约束通过；另有二进制检查点从 UNKNOWN（无 commit）到 SUBMITTED、VALID 的原字节往返，以及旧/到期/被替换 Outbox 租约拒绝更新检查，全部测试写入回滚。

十五表摘要范围为 T012-full-fifteen-table-snapshot-v1，SHA-256=`e283c2a09709d9ae3993e04dfd5790ac980610c25f3fadc4bfc708d6ba79178f`。BLOB 按实际二进制值参与摘要，不输出字节正文。原十四表单独重算仍为 `6a8a27f713d374d37df21c36570f47717629adeeaa7f6e37c9aa6a153549de9a`；跨版本对比报告 `.local/database-probe/20261022T152350Z-0813418f/t011-preservation.json` 证明原十四表、五版迁移及演示凭据未变。

旧 T011 回归报告 `.local/pipeline-probe/20261022T153410Z-0b0a789b/report.json`：未启用 ledger 的模式仍通过 42 次 HTTP/404 次断言，定版和持久消息不依赖 Fabric 消费者；原业务数据及凭据保持不变。

### 3. 真实业务存证及响应丢失验证

最终报告 `.local/anchor-probe/20261022T153155Z-d9b1845b/report.json`，2026-10-22T15:31:56.327499537Z 至 15:32:13.708734274Z。使用真实 Servlet、MySQL、Garage、RabbitMQ、两个 Gateway 与原 Fabric 网络，共 45 次 HTTP、407 次断言；断言数不计为独立测试数。探针使用随机业务夹具和独立消息拓扑，关闭自动派发及扫描，显式调用同一生产派发器和恢复处理器以观察检查点。

| 场景 | 实际结果 |
| --- | --- |
| 正式任务/上传/定版接口到真实上链队列 | 两个固定版本分别形成一个交易尝试，保存非空 transactionBytes/commitBytes，真实 commit VALID=0 后核对账本并 CONFIRMED |
| 两机构查询同一业务键 | evidenceId、version、fileHash、submitterMsp、registeredTxId 五字段一致；摘要与服务端计算及 Garage 原文件一致 |
| 数据库检查点恢复 | 原字节通过 T006 CheckpointValidator 的身份、通道、交易和链码检查；再次用保存的 commitBytes 查询真实链，结果及区块号一致 |
| 确认响应丢失 | 包装真实客户端，在实际 queryCommit 返回有效结果后注入一次 DEADLINE_EXCEEDED，业务持久化 UNKNOWN、原检查点和恢复计划，而非伪造链结果 |
| UNKNOWN 后恢复 | 提前本次 next_action_at 以触发同一恢复处理器；原 attempt、txId、transactionBytes、commitBytes 不变，重新查询并收敛 CONFIRMED；prepare/submit 次数未增加 |
| 重复消费及坏消息 | 重放上链事件不新增尝试、版本或覆盖首次交易；单消费者/prefetch=1 的后续坏 JSON 死信屏障证明前一消息已处理并 ACK |
| 权限及查询响应 | 匿名 401；非成员、仅机构管理员、未知版本 403，非法编号 400；委托方及运营商有范围成员可读；未返回对象键或字节检查点 |

本轮真实账本记录如下，版本均为 1、submitterMsp 均为 Org2MSP，摘要均为 `f254fb4b7683aaf00155ebf1a3358dd6ed17f30bcc76d92adb94eb8f89ef1b78`：

| evidenceId | 首次 registeredTxId | 区块号 |
| --- | --- | --- |
| 887941e5-a3d5-4038-b3e3-566aacb5f366 | ce2e0405e77ae5733009ab277a1a7cf53277601eca0addbfdbbefbcac9aa0453 | 9 |
| e9bdfa95-2fbe-45f0-8a6d-86ff6785edeb | 42fb2f4ee6407bdd31bb7445d91aba603ef082d4aaa952d467ccb306339e2ae0 | 10 |

报告只保存检查点长度和 SHA-256，交易字节长度分别为 4629/4630，确认请求长度为 905；未输出字节或私钥。上述两条已提交登记保留在原链上，不删除或重建账本。链外测试夹具已清理，不能以报告中的版本 UUID 继续调用业务接口。

保留 25 条安全审计，清理本次 1 个任务、6 个账号、2 个上传会话、4 个私有对象、关联 SQL 数据和独立消息拓扑。原十五张表全字段快照及演示凭据一致，原机构 2、演示账号 6、设备 1，其余业务表为空。确认响应丢失是本次受控 RPC 故障，未声称已完成跨进程崩溃或网络分区全矩阵。

### 4. 打包启动与保留范围

正式 `run-backend.py --demo --ledger` 已使用最终 JAR 在本次随机隔离消息拓扑启动，关闭自动派发及全部恢复扫描，未发送测试消息。报告 `.local/t012-packaged.json`、日志 `.local/t012-packaged.log`：HTTP 200 与 trace 一致，定版/上链队列各有一个手动 ACK、prefetch=1 消费者；原 .env、演示凭据和身份路径清单摘要不变。应用正常停止，隔离队列及 Exchange 已删除。

本轮私有日志、报告和凭据权限为 0600；核查未含原数据库/JWT/S3/RabbitMQ/演示秘密、JWT 字符串或私钥正文。没有清库、删除卷、回改历史迁移或重建凭据。T012 已完成，下一项 T013 实现存储文件和上传副本核验；M2 尚未通过。


## T013：原文件、上传副本与核验历史

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-23 |
| 产物 | V7 核验记录表、原对象/副本核验接口、分页历史、有界文件/链处理、multipart 权限及限制、真实核验探针与 t013.http |
| 结果 | 148 项后端测试、62 次真实 HTTP/588 次断言、110 项数据库约束通过；五种结果、只读副本和 I/O 期间成员撤销均验证通过 |
| 当前边界 | T013 完成，M2 尚须通过 T014 最小真实闭环验收；共享权限路径、进程残留清理及完整并发/故障矩阵按后续任务实现 |

### 1. 实现与复现

核验在 database、storage、ledger profiles 下启用，沿用 `run-backend.py --demo --ledger`。POST /api/v1/evidence-versions/{id}/verifications?source=STORED 无正文；source=COPY 接受 multipart/form-data 且仅有一个名为 file 的文件部分。GET 同路径使用 page/pageSize 查询该版本历史，默认 1/20、pageSize 最大 100，按 verifiedAt DESC、id DESC 排序。

每次先鉴权，再读取真正的对象字节或本次副本；SHA-256 计算完成后，按当前用户机构选择 Gateway 查询同 evidenceId/versionNo，核对登记字段格式和 OrgB 提交机构，再比较摘要。数据库原摘要不参与本次比较。副本只经过请求暂存及私有业务临时文件，不写 S3、不新增版本或存证事件。相同副本 MATCH，修改一个字节且保持类型特征的副本 MISMATCH。

链成功查询但未找到登记返回 ANCHOR_UNCONFIRMED；文件缺失/部分读取失败返回 FILE_READ_FAILED，部分摘要不入库；链查询异常/超时/非法响应返回 CHAIN_QUERY_FAILED，不冒充内容不一致。本地仍 PENDING 而实际账本已有有效记录时可 MATCH，核验不修复本地状态，也不降低已 CONFIRMED 的状态。

核验处理使用固定线程数和无等待队列，默认并发 2、文件处理与链查询共用 60 秒期限；忙时 503。超时取消并尝试关闭流，实际未退出的 Worker 继续占用容量，不能另写结果。该期限不包括 multipart 接收、初始鉴权及最终数据库事务；接收阶段受文件/请求大小、独立并发及连接空闲超时限制。COPY 默认上限 200 MiB，请求总上限为文件上限加 1 MiB。

结果保存短事务先锁任务，再锁账号并重新检查身份、角色和成员关系；处理期间失去权限时不保存、不披露核验结果。verification_record、FILE_VERIFICATION_COMPLETED 审计与数据库 UTC 时间一起提交。历史读取也检查当前权限。副本元数据、类型/文件头错误及超限属于 400/413 输入错误，不创建记录；五种已形成的核验结果均为 HTTP 200。

选择 JDK 21，复用原凭据、MySQL/Garage/RabbitMQ、Fabric 网络及 evidence 链码：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-verifications.py
python3 deploy/scripts/run-backend.py --demo --ledger
```

调用示例为 [t013.http](../examples/t013.http)，沿用 T010 的 79 字节 PNG 和 T011/T012 的固定版本。示例命令在 .local 生成修改副本，原样本及正式对象不变。VERIFICATION_* 控制大小、并发、时限、私有目录及允许类型；原 .env 使用应用默认值，无需重新生成。

### 2. 构建与迁移

最终日志 `.local/t013-build.log`：JDK 21 clean verify 成功，148 项测试，失败/错误/跳过均为 0。本轮新增 16 项服务/事务测试与 3 项 HTTP 编排测试。测试覆盖实际字节（包含与数据库元数据长度不同的对象）、当前机构查询、五种结果、只读副本、最终权限复核、事务与审计失败回滚，以及真实阻塞读取/关闭/RPC 的超时和容量保持；HTTP 测试验证权限检查在请求解析之前，重复来源及多个文件不能被默默接受。

V7__verification_records.sql 校验和为 `-763051633`，V1～V6 保持不变。新增 verification_record，保存版本、执行人、来源、完整实际摘要/大小、链摘要/交易编号、结果、安全原因码与核验时间。约束限制五种结果和摘要字段一致性，失败不能残留不完整摘要或伪造链字段。

最终数据库报告 `.local/database-probe/20261023T022529Z-0c67f82a/verify.json`：110 项真实约束通过，包含本轮 23 项；另验证两种来源和五种结果的正向记录、历史及数据库 UTC 默认值，全部测试写入回滚。原二进制检查点、Outbox 旧/过期租约及 UTC 毫秒往返检查继续通过。

十六表摘要范围为 T013-full-sixteen-table-snapshot-v1，SHA-256=`cad84c194e754e49aad94bec38fb7b992221b45a51c24aac37cd00619c8bd34e`。原十五表摘要仍为 `e283c2a09709d9ae3993e04dfd5790ac980610c25f3fadc4bfc708d6ba79178f`；跨版本报告 `.local/database-probe/20261023T021133Z-489cd210/t012-preservation.json` 证明六版迁移、原十五表和演示凭据一致。

### 3. 真实文件与账本核验

最终报告 `.local/verification-probe/20261023T022312Z-6599de70/report.json`，2026-10-23T02:23:12.869722460Z 至 02:23:28.914446950Z。真实 Servlet、MySQL、Garage、RabbitMQ、双机构 Gateway 与原 Fabric 网络完成 62 次 HTTP、588 次断言；断言数不计为独立测试数。随机消息拓扑中关闭自动派发及恢复扫描，显式驱动同一生产处理器，生成一个已真实上链版本和一个未登记版本。

| 场景 | 实际结果 |
| --- | --- |
| 原对象与相同副本 | 实际读取 Garage/请求字节，摘要与实际链记录一致，返回 MATCH |
| 副本只修改一个字节 | MISMATCH；正式业务十一表（含版本、计数和二进制检查点）及正式对象未变，不新增对象或上链事件 |
| 临时修改本次正式对象后核验 | MISMATCH；恢复本次对象后再次核对原始字节，证明核验没有使用数据库摘要代替读取 |
| 本次对象缺失与部分读取异常 | FILE_READ_FAILED，无实际/链摘要；一次异常由真实 Garage 流中受控注入 IOException，随后恢复本次对象 |
| 实际链查询响应故障 | 在真实查询成功后丢弃一次响应，返回 CHAIN_QUERY_FAILED，保留完整实际摘要，本地 CONFIRMED 不被降级 |
| 本地 PENDING 但账本已有记录 | 返回 MATCH，核验不修复本地状态；只使用本次隔离前置状态测试该规则 |
| 真实未登记版本 | 暂停本次上链消费者后经正式定版产生版本，实际 QueryEvidence found=false，返回 ANCHOR_UNCONFIRMED |
| 机构选择 | 委托方核验确实使用 OrgA 客户端，运营商使用 OrgB 客户端；均核对有效登记记录 |
| multipart 大小及权限顺序 | 限制覆盖为 1024 字节后，1025 字节 COPY 实际 413；坏 multipart 正文对匿名先 401、非成员先 403，不被解析错误抢先处理 |
| 历史及并发权限 | 分页 total、排序和版本范围正确；对象读取被阻塞时撤销本次成员，恢复读取后 403，没有新增核验记录或成功审计，失权用户不能读历史 |

共创建 10 条核验记录：MATCH 4、MISMATCH 2、FILE_READ_FAILED 2、CHAIN_QUERY_FAILED 1、ANCHOR_UNCONFIRMED 1。输入错误和权限拒绝未计入。流程中使用的人工改动仅限本次负例前置状态及故障恢复，没有手工写入业务成功状态或伪造正向链结果。

本次新增并保留的真实链记录：evidenceId=`3893fccb-2cee-4a79-82ca-a5db70db9660`，version=1，fileHash=`2186de62beddca248e3b906c5a7e1a0fd8301e0b2e3c2a6f15b31075d649456c`，submitterMsp=Org2MSP，首次 registeredTxId=`be0ab3399dbb0e99e5fe1ec1afc4ed6026c5927bf53fa1654892223c663486fd`，区块号 11。检查点长度为 4631/905，报告只保存长度和 SHA-256，不保存字节正文。

保留 33 条安全审计；清理本次 1 个任务、6 个账号、2 个上传会话、10 条核验记录、4 个私有对象和独立消息拓扑。原十六表全字段快照及演示凭据一致；原机构 2、演示账号 6、设备 1，其余业务表为空。已提交的测试登记保留在原账本，链外夹具已清理，不能用报告中的版本 UUID 继续调用业务接口。

### 4. 最终 JAR 与保留范围

正式 `run-backend.py --demo --ledger` 已在随机隔离拓扑上启动，关闭派发及全部恢复扫描，不发送消息、不提交链交易。报告 `.local/t013-packaged.json`、日志 `.local/t013-packaged.log`：HTTP 200；向坏 multipart 正文发起匿名及不可见版本请求，分别得到 401/403，私有暂存文件数为 0；定版和上链队列各有一个手动 ACK、prefetch=1 消费者。原 .env、演示凭据及身份清单摘要一致；应用正常停止，空临时目录和本次拓扑均删除。该检查另产生一条正常演示登录审计。

本轮日志、报告与凭据权限为 0600，检查未含原数据库/JWT/S3/RabbitMQ/演示秘密、JWT 字符串或私钥正文。未清库、删除卷、回改迁移或重建凭据。T013 已完成，下一项 T014 验收最小真实业务闭环，M2 尚未标为通过。

## T014：打包应用的最小真实闭环与保留式演示

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-23 |
| 产物 | verify-mvp.py、只读 MvpEvidenceInspector、独立 t014.http、m2-demo.md 及进度说明 |
| 结果 | 148 项后端回归通过；正常流程 33 次 HTTP/205 项检查、同版本复查 15 次 HTTP/113 项检查通过；对象、数据库、双机构真实账本对应 |
| 里程碑 | T014 完成，M2 通过；下一项 T015，完整 MVP 尚未完成 |

### 1. 复现方法和执行边界

复用原 .env、演示凭据、九个健康依赖容器、Garage 私有桶、Fabric 原通道与 evidence 链码，选择 JDK 21。在项目根目录执行，运行自动验收前停止其他后端实例：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-mvp.py
python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/20261023T074934Z-34a10f3c
```

末条路径指本机本轮成功成果，其他机器使用第一条验收命令实际输出的运行目录。流程与手动请求见 [M2 演示说明](m2-demo.md) 和 [t014.http](../examples/t014.http)。手动示例独立包含登录及全部业务步骤，不依赖前几份 HTTP 文件的响应变量。

自动脚本持有本机独占锁，检查没有其他项目 Java 后端或 Rabbit 消费者，且数据库无待处理上传、Outbox、消费或存证工作，再启动打包 JAR。应用使用随机本机 HTTP 端口和本次独立消息拓扑；正常流程使用默认自动发布器、消费者及恢复扫描，没有探针直接驱动业务 Bean，也没有 SQL 修改业务成功状态。

复查仍使用必要的完整 profiles，显式关闭发布器及两个恢复扫描，在独立空拓扑中通过正式接口读取同一资源并追加核验。检查器是 test 源码中的显式 CLI，不进入应用 JAR，不启动 Spring/Flyway/Worker；数据库连接为只读、一致快照事务，只执行 SELECT，对存储和 Fabric 仅作读取及查询。

### 2. 构建、真实流程和对应关系

构建日志 `.local/t014-build.log`：JDK 21 clean verify 成功，148 项测试，失败/错误/跳过均为 0。原 110 项 MySQL 约束在演示前通过，报告 `.local/database-probe/20261023T031645Z-e1cd6173/verify.json`，全部测试写入回滚。随后收紧只读检查器，要求每轮恰好新增本轮三个 HTTP 响应中的核验编号，重新编译通过，日志 `.local/t014-inspector-build.log`；正常流程与复查均使用该版本。

正常流程报告 `.local/mvp-demo/20261023T074934Z-34a10f3c/report.json`，时间为 2026-10-23T07:49:34.305608Z 至 07:50:00.296205Z。33 次 HTTP、205 项检查通过；检查数不计为独立单元测试或性能测量。

| 验收点 | 实际结果 |
| --- | --- |
| 正式业务创建 | 现有 owner-admin、operator-admin、operator-user 登录；正式接口创建资料项、指派、成员、子任务、设备批次及启动，任务为 RUNNING |
| 单片上传与异步定版 | 实际上传 79 字节 PNG；完成返回 202，再轮询得到 COMPLETED 和固定 versionId，版本号为 1、复核仍 PENDING |
| 自动消息链路 | 定版和存证各一个真实消费者，manual ACK、prefetch=1；两条 Outbox 为 SENT、对应消费为 DONE；本次队列最终排空 |
| 私有文件 | 分片及正式对象实际 GET 的长度/摘要与样本一致；两个对象的匿名 HEAD/GET 均为 403 |
| 真实存证 | 一条原始尝试为 VALID、validationCode=0、区块号 13；保存的原 commit 检查点再次向 Peer 查询得到同一有效结果 |
| 双机构一致性 | 原交易包含 Org1MSP、Org2MSP 背书；OrgA、OrgB 实际查询五字段完全一致，首次登记 txId 与数据库和核验记录一致 |
| 原文件与副本 | 委托方 STORED 为 MATCH，运营商相同 COPY 为 MATCH，修改一个字节的 COPY 为 MISMATCH；三条结果及成功审计持久化 |
| 原数据与成果保留 | 十六表原有每行全字段和二进制摘要保持；新增资源限于本任务，原机构、六账号、一设备及凭据不变；本次业务数据和对象保留 |

原样本/对象/版本/链上摘要为 `ec81ce39789f63a8f58cd7c6ecaef171b4b944d665ea92d79b2c92175ea2726f`；修改副本摘要为 `df7e4e69a38e8896cdba132dda911dc642e8bfb9a39e3bf6cb0da5871ec4eba7`。副本核验没有覆盖正式对象或生成新版本。

可继续复查的主要资源如下，完整关系见本轮 resources.json 和 inspection.json：

| 资源 | 实际编号 |
| --- | --- |
| taskId | c642c556-3e99-4451-9f31-019bb35da2d6 |
| uploadId | 08ec37f5-2ea1-4c32-a06c-14cf6bc1bf8f |
| versionId | 3c98a12a-5bab-481b-9971-42fd128cceda |
| evidenceId | bfa31685-7110-4136-8e7a-4d2d2977384a |
| 首次 registeredTxId | 6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799 |

原交易/确认检查点长度为 4630/905，报告仅保存长度及 SHA-256，不保存检查点正文、私钥、对象键或下载地址。

### 3. 同版本复查和收尾修正

复查报告 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261023T075043Z-b48b7706/report.json`：15 次 HTTP、113 项检查通过，仍为原 versionId 和首次登记交易；与复查前十六表快照比较，仅 verification_record 新增本轮三条，其他表无新增且原行未变。该版本核验历史由 3 条变为 6 条，正式对象字节及实际账本登记不变。

首轮 `.local/mvp-demo/20261023T032925Z-206bdf17/` 已完成业务及只读对应核对，但应用退出后 Rabbit 管理统计暂时仍显示消费者，原脚本即时清理检查失败，report.json 保留失败状态。已增加最多 20 秒等待，仍保留服务端 if-empty/if-unused 删除条件；确认首轮队列为空且无人使用后，仅删除首轮随机拓扑，恢复证据为该目录 cleanup-recovered.json。修复后重新执行完整正常流程及同版本复查，均自动完成收尾。

首轮已完成的任务、文件和登记也保留，没有通过删除成功数据重跑。其 evidenceId 为 `bd526eb3-1f9b-4eb7-ae66-a7e95c4f621e`，版本 1、Org2MSP、区块号 12，首次 txId 为 `5f140e6c54f219cca1c3ceafbf8b23d6c9e11247867b26b7335b13b7c720ca63`，摘要与上文原样本相同。最终保留两条演示任务/上传/版本/存证、四条 Outbox/消费记录、两次有效尝试和九条核验记录；原两个机构、六账号及一设备未变。

两次完成流程及复查的自有应用均已停止，随机消息拓扑均已删除，业务数据、对象、审计和原 Fabric 账本保留。58 份本轮私有报告/日志检查通过，权限为 0600，未含已知服务/演示秘密、JWT 或私钥正文，证据 `.local/t014-artifact-audit.json`；.env、演示凭据及身份清单仍为 0600。未清库、删除卷、回改迁移或重建凭据。

T014 完成，M2 最小真实闭环通过。当前任务 RUNNING、版本复核 PENDING 符合阶段范围；人工复核、最终任务验收、多机和共享等仍按后续任务实现，下一项为 T015。

## T015：设备管理、独立认证、心跳与进度上报

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-23 |
| 产物 | V8 设备事件表、设备管理与独立 SecurityFilterChain、双键幂等报告、Redis 在线缓存、设备上传入口及 t015.http |
| 结果 | 185 项后端测试、79 次真实 HTTP/606 项检查、129 项数据库约束通过；最终 JAR 原 M2 复查 15 次 HTTP/118 项检查通过 |
| 当前边界 | T015 完成；下一项 T016 多机模拟、人工换机与旧批次迟到隔离，M3 尚未完成 |

### 1. 实现和复现

运营商 OPERATOR_TASK_ADMIN 可以登记、分页查询和启停本机构设备，启停使用 expectedLockVersion 并记录事务内 USER 审计；ORG_ADMIN 单独不具备该权限。登记生成 32 字节随机设备令牌，首次响应返回 deviceToken，数据库只保存 SHA-256，后续读取不含令牌或摘要。

设备通过 X-Device-Id/X-Device-Token 访问 /api/v1/device/**，用户 JWT 不能代替该凭据。每请求校验当前数据库身份，写事务取得设备锁后再次校验原摘要及启用状态。设备不获得用户角色，不能访问用户、复核、共享或版本核验路径；对尚未实现的复核和共享路径，验证的是其用户认证边界，不代表这些业务已完成。

报告包含 bootId、seq、eventId、executionId、type，PROGRESS 提供 0～100 的 progress，HEARTBEAT 不接受 progress。seq 限制为 JSON 安全整数范围，禁止小数截断和字符串强转。服务端锁定任务、子任务、执行和设备，校验机构、分配设备及当前 RUNNING 批次。双唯一键限制设备内 bootId/seq 和 eventId；重复请求返回原 receivedAt、executionProgress，既不刷新在线时间，也不增加进度、事件或审计；同键不同内容返回 409。

新事件、数据库 UTC 最近接收时间、单调执行进度及 DEVICE 审计同事务提交，100% 不改变任务完成状态。默认 20 秒无新报告即离线。提交后尽力写入带 TTL 的 Redis 缓存，Lua 防止旧时间覆盖新时间；详情仍读取数据库身份和元数据，不让缓存制造心跳，Redis 不可达时回源。连接和命令时限为 300 毫秒，缓存失败不能回滚已提交事件。

设备上传复用用户上传的字节校验和对象 I/O，仅新增独立身份入口及作用域判断，支持会话、进度、分片及 messaging 下的完成入口。设备必须是该执行分配设备和会话所有者，I/O 后再次鉴权；会话保存 DEVICE，异步定版沿用 uploader_drone_id 来源。旧批次补传与人工换机仍由 T016 实现。

复用原依赖和凭据，选择 JDK 21 后执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-devices.py
python3 deploy/scripts/run-backend.py --demo
```

手动顺序见 [t015.http](../examples/t015.http)：登记新设备后，通过 T009 接口为其创建并启动新任务，再提交心跳、进度或上传。使用原 M2 成果复查时仍启动 --ledger；本次设备探针只启用 local,database,storage，没有启动共享后台消费者或新增链登记。参数和接口细节见 [后端说明](../backend/README.md)。

### 2. 构建、迁移和原成果保留

最终日志 `.local/t015-build-final.log`：JDK 21 clean verify 成功，185 项测试，失败/错误/跳过均为 0。相较 T014 新增 37 项测试，覆盖设备认证与状态重验、真实安全链隔离和晚期 401 提示、数据库故障 503、双键幂等、原结果快照、单调进度、禁用竞争、缓存提交边界、数据库在线事实，以及设备上传 I/O 后鉴权和完成事件。

V8__device_reports.sql 校验和实际为 `1053255897`，V1～V7 保持不变。新增 device_event 以及 task_execution 的 `(id, drone_id)` 唯一索引，通过复合外键限制事件的设备批次归属；历史引用使用 RESTRICT。报告 `.local/database-probe/20261023T082043Z-e8702158/verify.json`：129 项数据库约束通过，其中新增 19 项，测试写入全部回滚。另验证启动后换 bootId 可以重用 seq、不同设备的事件编号作用域、原结果与 UTC 毫秒时间往返。

十七表摘要域为 T015-full-seventeen-table-snapshot-v1，SHA-256=`26b42188c51b62d48c578d78961e610a7d909464ef8e9a273328e5857cbe97f4`。只读保留报告 `.local/t015-migration-preservation.json` 使用 T014 同一行摘要算法，与其最终复查结果逐字节相等，证明 V8 后原十六张表每行、每列和二进制检查点未变；新增索引未改写原业务内容。T014 检查器已同步验证 V1～V8，仍兼容原十六表基线。

### 3. 真实 HTTP、Redis 和私有对象验证

报告 `.local/device-probe/20261023T082538Z-e8a49eb6/report.json`，时间为 2026-10-23T08:25:39.066202895Z 至 08:25:53.236731538Z。真实 Servlet、MySQL、Redis 和 Garage 完成 79 次 HTTP、606 项检查；检查数不计为独立单元测试数或性能指标。

| 场景 | 实际结果 |
| --- | --- |
| 管理与令牌 | 正式创建两设备，仅首次返回令牌，MySQL 摘要与实际令牌 SHA-256 一致；列表/详情无令牌或摘要；越权角色、重复代码、过期 CAS 被拒绝 |
| 独立身份和批次 | 未知/错误/禁用设备 401，用户 JWT 不能报告，设备凭据不能登录用户路径；他设备、他子任务和他任务执行为 403 |
| 非法报告 | 负序号、超安全范围序号、小数及字符串序号、小数进度、缺失/越界进度均为 400，未创建事件或刷新心跳 |
| 去重及原结果 | 重放返回原编号/接收时间/当时进度，MySQL 时间、事件数及 Redis TTL 不刷新；更换内容或复用同 boot/seq 的其他 eventId 返回 409 |
| 并发和进度 | 两个并发相同报告均为 200，一个受理、一个重复，数据库只增加一事件；低进度不回退，100% 后任务和执行仍 RUNNING；新 boot 从 0 开始有效 |
| 真实 Redis | 检查实际毫秒值与 TTL；第二应用指向不可达 Redis 端口，报告和详情仍成功读取 MySQL；本次缓存命名空间中的未来值不能伪造心跳 |
| 在线阈值 | 测试覆盖为 2 秒，新报告在线，等待 2.2 秒后离线，不通过 SQL 改写离线状态；默认 20 秒及恰好阈值由单元用例验证 |
| 设备上传 | 正式创建 DEVICE 会话、幂等重试、单片写入、查询进度；其他设备/用户不能取得所有权，越执行上传拒绝；实际 Garage 字节、数据库与 HTTP 摘要相同 |

本次形成 7 条设备事件并保留 32 条安全审计。结束清理本次 2 个任务、6 个账号、2 台设备、7 条事件、1 个上传会话、1 个私有分片对象和 1 个 Redis 键，原十七表全字段快照及演示凭据一致。设备上传探针不启用 messaging，实际到单片持久化为止，未生成固定版本或链交易；设备完成入口的鉴权、冻结、设备审计及事件顺序由上述单元用例验证。

### 4. 最终 JAR 兼容性与收尾

最终 JAR 使用原 M2 版本完成复查，报告 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261023T082742Z-3d43ed0e/report.json`：15 次 HTTP、118 项检查通过。使用独立空消息拓扑并关闭派发/恢复，只有三条实际核验和对应审计新增；原任务、上传、版本、存证尝试、检查点及对象均不变。两机构仍查询首次 txId=`6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799`、区块号 13 的同一登记，没有新的链交易。该版本核验历史从 6 条变为 9 条，全库核验记录由 9 条变为 12 条，均为本次正式核验的预期追加。

自有应用均已退出，复查的独占空消息拓扑已删除。29 份本轮私有日志/报告检查通过，权限为 0600，未含已知服务/演示秘密、JWT 或私钥正文，证据 `.local/t015-artifact-audit.json`。原 .env、演示凭据和身份文件保留；没有清库、删除卷、回改历史迁移或重建凭据。

T015 已完成。下一项 T016 实现 3～5 台设备模拟、人工换机和旧批次迟到隔离；人工复核、任务完成及共享仍按各自任务推进，M3 尚未完成。

## T016：多机模拟、人工换机与历史批次隔离

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-24 |
| 产物 | 3～5 台设备模拟器、V9 换机记录、子任务 CAS 换机、执行历史、当前批次进度和资料汇总、历史报告及补传隔离、t016.http |
| 结果 | 199 项后端测试、9 项模拟器离线测试、144 项数据库约束、84 次真实 HTTP/473 项检查通过；最终 JAR 原 M2 复查 15 次 HTTP/112 项检查通过 |
| 当前边界 | T016 完成，下一项 T017 多分片续传与多版本归档；M1、M2 保持通过，M3 和完整 MVP 尚未完成 |

### 1. 实现和复现

模拟器支持 3～5 台设备并行，各设备独立保存私有状态。报告先持久化 bootId、seq、eventId 和完整请求，再发起 HTTP；重启先重试未完成的原报告，之后使用新 bootId 从 seq=0 开始。上传保存同一 clientRequestId 和 CREATE/PART/COMPLETE 阶段，重试或重启继续原会话。状态、统计及标准输出不保存设备令牌，配置仅从本机私有文件读取。

运营商任务管理员可对活动任务使用子任务 expectedLockVersion 换机，必须填写原因并选择不同的启用设备。成功事务关闭旧批次、创建进度为 0 的新 RUNNING 批次、切换 currentExecutionId，并保存调整记录及审计。历史查询保留原设备、操作者、原因和时间；当前资料仅在 currentExecutionId 范围内选择最新固定版本。

原设备可在活动任务下向非当前 CLOSED 批次提交历史报告或补传。新历史报告记录事件并刷新原设备在线时间，旧执行进度冻结，也不能推进新执行；已受理事件重放返回原结果，不刷新在线时间。换机前已进入 VERIFYING 的上传继续归属旧执行。任务完成后的新增写入限制保持有效。

复用原依赖、凭据及保留成果，选择 JDK 21 后在项目根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 -m unittest discover -s simulator -p 'test_swarm.py' -v
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-swarm.py
python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/20261023T074934Z-34a10f3c
```

末条使用本机原 T014 成功目录；其他环境替换为实际保留目录。模拟器配置和私有状态说明见 [simulator/README.md](../simulator/README.md)，手动请求见 [t016.http](../examples/t016.http)。真实集群探针使用隔离账号、设备、Redis 前缀和 RabbitMQ 拓扑，启用生产自动派发与单消费者定版，不启动 Fabric 存证处理器。

### 2. 构建、迁移和数据保留

最终日志 `.local/t016-build.log`：JDK 21 clean verify 成功，199 项后端测试，失败、错误和跳过均为 0。相较 T015 新增 14 项测试，覆盖换机权限、CAS、事务记录、历史及当前批次汇总，以及旧设备补传、历史报告和迟到定版的批次隔离。

模拟器日志 `.local/t016-simulator-tests.log`：9 项标准库 unittest 全部通过。覆盖 3/5 台设备配置及真实线程池并行、报告重启与响应丢失恢复、上传三个阶段继续原请求和会话、创建响应后磁盘保存失败、样本改变拒绝、模拟断网先持久化，以及令牌和供应商错误详情不进入状态或输出。测试禁止实际网络连接，真实 API 行为由下述集群探针验证。

V9__execution_adjustments.sql 实测校验和为 `72853277`，V1～V8 校验和保持不变。新增 execution_adjustment，分别唯一限制旧、新执行，通过复合外键保证任务、子任务和两个执行归属一致；旧新执行必须不同，原因不能为空白，历史引用使用 RESTRICT。迁移只新增表和索引，不改写既有业务行。

数据库报告 `.local/database-probe/20261024T023645Z-df265872/verify.json`：144 项真实约束全部通过，本轮新增 15 项；全部探针写入回滚。另验证 A→B→C 连续换机、最长中文原因、数据库 UTC 时间及原二进制检查点、Outbox 租约往返。十八表摘要域为 `T016-full-eighteen-table-snapshot-v1`，SHA-256=`c8b07dbe8d3c815d60c28e98f56eba872c17e9584ed0991cff6018d53c6dae94`；原十七表摘要域及旧版比较继续兼容。

迁移保留报告 `.local/t016-migration-preservation.json` 的 matchesT015FinalRecheck=true，原十六表摘要为 `1b928fa3a9e5eff1043a27674859f421fceab69263a96572076e9de28531ff45`，与 T015 最终 M2 复查完全一致，包含每行全字段和 BLOB 实际字节。原 device_event 仍为空；演示账号、设备、两条 M2 任务及其原有成果保持。M2 只读检查器已支持九版迁移，仍使用原十六表快照域。

### 3. 真实多机、并发换机和历史隔离

报告 `.local/swarm-probe/20261024T023750Z-1ce2538c/report.json`：真实 Servlet、MySQL、Redis、Garage 和 RabbitMQ 全流程通过。Java 探针发起 57 次 HTTP，三台模拟设备实际发起 27 次 HTTP，合计 84 次；Java 检查为 473 项，检查数不计为独立测试数或性能指标。

创建五台隔离设备，其中三台并行运行、两台备用。模拟器运行六轮，包含两轮模拟断网；每台成功发送 4 个新报告、重放 2 次、提交 1 个单片上传，共 12 个不同的初始设备事件。每台 9 次实际 HTTP，断网轮跳过网络，最终没有待重试报告或上传。

| 场景 | 实际结果 |
| --- | --- |
| 当前进度与权限 | 三个当前执行汇总进度为 60%；非成员和仅机构管理员不能读取业务汇总，普通成员不能换机，缺失原因或选择相同设备被拒绝 |
| 并发 CAS | 两个并发换机请求恰好一个 200、一个 409；只生成一个新当前执行、一条调整记录和一条成功审计，新执行为 RUNNING/progress=0 |
| 正在校验时换机 | 使用生产默认单定版消费者，在真实 S3 读取已取得流后暂停；此时旧上传为 VERIFYING，正式接口完成换机，再恢复读取，旧上传最终定版为旧执行的 v2 |
| 当前和迟到版本 | 新当前执行生成 v3，原设备随后向 CLOSED 批次补传生成更大的历史 v4；当前汇总仍选 v3，旧批次资料不能满足当前批次的验收条件 |
| 旧设备历史报告 | 原设备的新历史报告为 200，设备恢复在线并保存事件；旧执行及新执行进度均不被该报告推进；原事件重放不刷新设备在线时间，也不新增事件 |
| 设备归属与禁用 | 新设备不能报告或上传旧执行，返回 403；原设备禁用后不能再读取其历史上传，返回 401 |
| 历史与业务状态 | 历史分页保留旧新执行、当前标志、调整人、原因和时间；新执行上报 100% 不自动完成任务或接受资料 |

本次真实生成 6 个固定版本，逐个 GET 正式私有对象核对原始 79 字节 PNG，SHA-256 均为 `ec81ce39789f63a8f58cd7c6ecaef171b4b944d665ea92d79b2c92175ea2726f`。6 条存证消息均核对本次持久化且已 SENT 的 Outbox、原事件编号和持久消息属性后才由探针 ACK；存证状态仍为 PENDING，未创建 chain_attempt，也没有向 Fabric 提交交易或制造 CONFIRMED 状态。该检查验证定版和持久消息，不将队列接纳计为真实存证成功。

保留 36 条安全审计，清理本次 1 个任务、5 个账号、5 台设备、6 个上传会话、12 个私有对象、4 个 Redis 键及关联 SQL 夹具，独占消息拓扑已删除。清理后原十八张业务表完整快照一致，包括原两条 M2 任务、12 条核验记录和二进制检查点；原 .env 与演示凭据保持不变。

### 4. 最终 JAR 对原 M2 的兼容性

最终 JAR 复查报告 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261024T023822Z-33285126/report.json`：15 次 HTTP、112 项检查通过。该目录 inspection.json 的 migrationsValidated=9、originalBusinessRowsUnchanged=true；与本轮复查前基线比较，仅追加三个正式核验结果及审计，目标版本历史由 9 条变为 12 条，全库 verification_record 由 12 条变为 15 条。

原 versionId、正式对象、transactionBytes 和 commitBytes 保持；双机构真实查询仍对应首次 txId=`6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799`、区块号 13，原尝试为 VALID、validationCode=0，没有新增链交易。复查使用独占空消息拓扑，关闭自动派发和恢复扫描；自有应用已停止，emptyOwnTopologyDeleted=true。

私有产物检查 `.local/t016-artifact-audit.json` 通过：本轮 32 份私有 JSON/日志均为 0600，未含已知服务及演示秘密、JWT 或 PEM 私钥正文；原 .env、演示凭据和 Gateway 身份清单仍为 0600。含本次新设备令牌的临时模拟器配置已删除，没有清库、删除卷、回改历史迁移或重建凭据。

T016 已完成。下一项 T017 验证多分片乱序上传、刷新或重启后续传、重复分片冲突、并发完成唯一版本及新旧文件独立保留；本轮 PNG 单片流程不代表多分片已验收。M3 和完整 MVP 尚未完成。

## T017：多分片续传、多版本归档与文件边界

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-24 |
| 产物 | 通用文件续传 CLI、多分片真实双 JVM 探针、PNG 校验及完成错误分类修正、t017.http；复用原上传会话和定版模型，无新增迁移 |
| 结果 | 212 项后端测试、20 项 Python 离线测试、144 项数据库约束通过；双 JVM 及双 Python 进程完成 88 次真实 HTTP/648 项 Java 检查 |
| 当前边界 | T017 完成，下一项 T018 异常工单与人工复核；M1、M2 保持通过，M3 和完整 MVP 尚未完成 |

### 1. 实现和复现

分片支持任意合法顺序，GET 从 MySQL 返回已完成分片的编号、实际长度和 SHA-256，客户端由 partCount 推导缺片。分片规格和 expiresAt 固定在原会话中；应用重启或修改默认分片大小不会改变已有会话，也不延长其期限。同编号同内容幂等返回，不同内容为 PART_CONTENT_CONFLICT/409；冻结后即使是相同分片也不再接纳写入。

`simulator/upload.py` 支持用户 JWT 和独立设备凭据，以私有 0600 配置调用正式接口。发送前持久化原 clientRequestId、文件路径/长度/完整摘要、身份及资料范围，重新运行先 GET 服务端进度，仅补缺片，并逐片核对服务端元数据与本地实际字节。创建或分片响应丢失保留原请求及会话；文件或范围改变时拒绝复用状态，JWT 刷新允许同账号继续。读取、计算摘要及请求体均使用最多 64 KiB 缓冲。

本轮同时修正三处生产边界：PNG 拒绝未知关键块，合法辅助块仍可接受；完成接口冻结条件检查恰好跨越到期时间时，返回 UPLOAD_SESSION_EXPIRED；最终数据库提交异常从文件/存储异常分类中分离，避免将数据库失败误记为 STORAGE_UNAVAILABLE，也不在提交结果未知时删除候选对象。

选择 JDK 21，复用原依赖和凭据，在项目根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 -m unittest discover -s simulator -p 'test_*.py' -v
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-multipart.py
python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/20261023T074934Z-34a10f3c
```

手动接口见 [t017.http](../examples/t017.http)，私有 CLI 配置和断点操作见 [simulator/README.md](../simulator/README.md)。例如首次使用 `python3 simulator/upload.py --config .local/uploader/config.json --max-parts 1` 只发送一个缺片并正常退出，第二次移除 --max-parts 查询原会话、补齐后请求完成。complete 返回 202 仅为异步接纳，CLI 的 VERIFYING 不代表定版、复核或上链成功。

### 2. 构建、数据库和离线恢复测试

最终日志 `.local/t017-build.log`：JDK 21 clean verify 成功，212 项后端测试，失败、错误和跳过均为 0，相较 T016 新增 13 项。覆盖乱序合并、重算每片和完整摘要、分片大小/期限配置、完成临界过期、格式边界、独立版本及数据库异常不能被错误归类为存储故障。

日志 `.local/t017-python-tests.log`：20 项 Python 标准库 unittest 全部通过，包括原 9 项 swarm 测试及新增 11 项 upload 测试。新增用例覆盖跨运行补缺片、创建/分片/完成响应丢失、原请求键保持、服务端几何优先、已有片长度或摘要冲突、本地文件/范围改变、同用户刷新令牌、用户和设备凭据保护、HTTP 错误关闭和安全提示，以及 64 KiB 有界请求流；离线测试禁止真实网络访问。

数据库报告 `.local/database-probe/20261024T083244Z-45e5fa55/verify.json`：144 项真实约束通过，全部测试写入回滚。V1～V9 迁移及校验和未变，V9 仍为 `72853277`；本轮没有新增迁移或业务表。十八表快照沿用 `T016-full-eighteen-table-snapshot-v1`，SHA-256=`a758759390fb5858e29d53f7e6b8cfd464a6ffdcd63c9fe36d7b37b66648ebf2`，其中原全库核验记录为 15 条。

### 3. 真实双进程续传、多格式及并发完成

总报告 `.local/multipart-probe/20261024T083321Z-d70b567b/verify.json` 为 PASS。prepare.json 为 23 次 Java HTTP/151 项检查，report.json 为 56 次 Java HTTP/497 项检查，合计 79 次 Java HTTP/648 项 Java 检查；两个 CLI 子进程另发起 9 次 HTTP，总计 88 次真实 HTTP。检查数不计为独立单元测试数或性能指标。

两个后端是真正独立的 JVM，PID 从 111521 变为 112563。首个进程关闭后保留 SQL 及私有分片，第二进程从原数据恢复；没有通过重建会话或手工写成功状态绕过重启。

| 场景 | 实际结果 |
| --- | --- |
| 乱序及重启准备 | 2500 字节 PNG 固定为 1024 字节/片、共三片，先传第 3 片再传第 1 片；GET 按序返回已收 1/3，累计 1476 字节，推导缺片仅为 2 |
| 重启后规格保持 | 第二 JVM 默认分片大小改为 512，原会话仍为 1024、expiresAt 不变，同 clientRequestId 返回原会话；新建会话使用 512；仅补原第 2 片即可完成 |
| 分片冲突及权限 | 同片同内容 200，同片不同内容 409；缺片完成 409、超长分片 413、越界片号 400；非成员不能读取会话 |
| 并发及冻结 | 两个相同分片并发请求均成功，只保留一条该编号记录和一个被引用对象；两个完成请求均为 202，只生成一条定版事件；真实 S3 读取暂停时 GET 为 VERIFYING，冻结分片写入为 409 |
| 完成幂等 | 恢复 S3 读取后形成一个正式版本；已完成会话拒绝分片，重复 complete 返回同一 versionId，不新增版本或存证任务 |
| 两次真实 CLI 进程 | 新 3000 字节 PNG 按 512 字节分为六片；首次 --max-parts 1 用 2 次 HTTP 保留首片，第二进程 GET 后仅补五片并 complete，用 7 次 HTTP，响应为 VERIFYING，随后由正式 Worker 定版 |
| 多版本保留 | 同一 PNG 资料项的旧 v1、新 v2 具有独立 versionId、正式对象和不同完整摘要，实际同时回读一致；新版本保持独立 PENDING 复核状态 |
| 多格式 | 2700 字节 PDF 与 1784 字节 MP4 结构样例均以倒序多片上传并由正式 Worker 定版，逐个核对完整实际字节；MP4 按普通文件处理，不承诺媒体解码、转码或播放能力 |
| 配置和自然过期 | 文件上限 8192 字节时，8193 字节请求为 413；禁用 jpg 或扩展名/MIME 不一致为 400；2 秒期限自然到期后 GET 为 EXPIRED，PUT 和 complete 为 409 |
| 分片真实完整性 | 仅修改本次隔离会话在 Garage 的第 2 片一个字节，正式 Worker 回读后得到 FAILED/PART_HASH_MISMATCH，无正式版本；未修改数据库分片摘要来制造成功 |

四个正向版本的完整摘要如下，均通过实际私有对象 GET 复核：

| 文件 | 长度 | SHA-256 |
| --- | --- | --- |
| 原 PNG / v1 | 2500 | `1c316e4984076c8680c8c86db88e0f5f526b90c3828949ece0d7cf60f3e27e45` |
| 新 PNG / v2 | 3000 | `96f8eeb87080a29ca352f56a550725aed58c09bce4205bcf04233afd72e2321c` |
| PDF | 2700 | `7778e23077e733b3dc175158eb7941566e05362f7f727822cc73fa1c8c716fae` |
| MP4 结构样例 | 1784 | `b6da6c7f33aa5d331d650d987540143aaf4534f37456a6812ac8560c86995bae` |

本轮启用生产自动发布器和定版消费者，没有启用 ledger。四条存证消息核对本次已 SENT 的持久化 Outbox 及事件内容后才由探针 ACK；四个存证任务保持 PENDING，chain_attempt 为 0，没有新 Fabric 交易，也没有将消息接纳伪写为 CONFIRMED。

保留 50 条安全审计，清理本次 1 个任务、4 个账号、1 台设备、6 个上传会话、28 个私有对象及关联 SQL 夹具，独占消息拓扑删除。清理后原十八张业务表每行全字段和 BLOB 实际字节快照一致，原 .env、演示凭据及 M2 成果保持；临时测试身份及 CLI 配置已删除。

### 4. 最终 JAR 兼容性和私有产物

最终 JAR 复查报告 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261024T083504Z-fe9956b8/report.json`：15 次 HTTP、113 项检查通过。inspection.json 的 migrationsValidated=9、originalBusinessRowsUnchanged=true；仅追加三个正式核验结果及审计，目标版本核验历史由 12 条变为 15 条，全库 verification_record 由 15 条变为 18 条。

原正式对象、版本、transactionBytes 和 commitBytes 保持，双机构查询仍对应首次 txId=`6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799`、区块号 13 的同一登记，没有新增链交易。复查关闭自动发布及恢复扫描，自有应用已停止，ownApplicationStopped=true、emptyOwnTopologyDeleted=true。

私有产物检查 `.local/t017-artifact-audit.json` 通过：本轮 32 份 JSON/日志均为 0600，未含已知服务及演示秘密、JWT 或 PEM 私钥正文；原 .env、演示凭据及 Gateway 身份清单仍为 0600。本次临时 credentials.json 和 uploader-config.json 已删除；未清库、删除卷、回改历史迁移或重建凭据。

T017 已完成多分片续传与多版本归档验收。下一项 T018 实现异常工单和人工复核；任务最终验收、共享及完整故障恢复矩阵仍按后续任务推进，M3 和完整 MVP 尚未完成。

## T018：固定版本异常工单与人工复核

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-24 |
| 产物 | V10 异常/复核表、版本异常登记及分页、委托方单版复核和结果查询、并发结论 CAS、t018.http 与真实复核探针 |
| 结果 | 233 项后端测试、167 项数据库约束、108 次真实 HTTP/780 项检查通过；原二十表全字段/BLOB、凭据和正式对象保持 |
| 当前边界 | T018 完成，下一项 T019 提交验收和任务完成；M1、M2 保持通过，M3 和完整 MVP 尚未完成 |

### 1. 实现和复现

OrgB 的 OPERATOR_USER 且为任务成员时，可通过 POST /api/v1/issues 提交 versionId 和 1～4000 字非空白 description。异常绑定具体固定版本和任务，同一版本可登记多条，不修改文件或自动改变复核结果。GET /api/v1/evidence-versions/{id}/issues 按 createdAt、id 升序分页，返回 items、page、pageSize、total；默认 1/20，pageSize 最大 100。

OrgA 的 OWNER_REVIEWER 且为任务成员时，可通过 POST /api/v1/evidence-versions/{id}/review 提交 APPROVED/RETURNED、expectedReviewStatus=PENDING 和 reason。退回必须填写 1～1000 字非空白原因，通过原因可为 null，提供时也必须非空白。业务按父任务、当前账号、版本的顺序取得行锁并重验权限，以 PENDING 和版本锁号条件更新状态；正式结论、任务重开及成功审计同事务提交。一版只有一条正式复核结论，重复或并发落败返回 409，不覆盖首次结果。

GET 同一版本的 /review 返回 reviewStatus 和该版正式结论；尚未复核时 review=null。读接口也重验当前身份、业务角色和成员关系，再读取历史。复核与链状态独立：可以复核尚未确认存证的固定版本，APPROVED 不将 PENDING 存证变为 CONFIRMED，也不代表任务已可验收；新版本独立从 PENDING 复核开始，旧版本结论及异常不迁移。

选择 JDK 21，保留原基础设施、凭据和 M2 成果，在项目根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-reviews.py
python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/20261023T074934Z-34a10f3c
```

手动顺序见 [t018.http](../examples/t018.http)，接口及角色说明见 [后端说明](../backend/README.md)。复核接口仅需 database profile；真实探针同时启用 storage、messaging 以通过正式上传及定版生成版本，不启用 ledger，也不提交新的 Fabric 登记。

### 2. 构建、迁移与保留范围

最终日志 `.local/t018-build.log`：JDK 21 clean verify 成功，233 项后端测试，失败、错误和跳过均为 0，相较 T017 新增 21 项复核服务测试。测试覆盖角色/成员重验、父任务锁序、文本与分页边界、结论 CAS、单版唯一约束冲突、事务内审计失败回滚、独立版本，以及当前最新版本退回才重开和任务完成后拒写的规则。

V10__issues_and_reviews.sql 实测校验和为 `-573715695`，V1～V9 及原校验和保持不变。新增 issue、review_record，并为 evidence_version 增加 `(id, task_id)` 唯一键以支持异常的任务/版本复合外键。异常描述上限 4000，原因上限 1000；退回不能无原因，通过可为 null；每版正式结论唯一，版本和用户历史引用均为 RESTRICT。未增加新的版本状态列或改写既有业务行。

数据库报告 `.local/database-probe/20261024T123753Z-62eaec65/verify.json`：167 项真实约束通过，其中新增 23 项；全部探针写入回滚。新增检查覆盖任务/版本复合作用域、用户引用、删除限制、单版重复结论、非法决定、null/空串/ASCII 及 Unicode 空白、超长描述和原因；正向检查同版多异常、4000 字中文描述、通过的 null/说明、1000 字退回原因、不同版本独立结论及数据库 UTC 默认时间。这些是 schema 约束和字段往返检查，不作为真实复核业务状态流的执行证据。

二十表摘要域为 `T018-full-twenty-table-snapshot-v1`，SHA-256=`17f9cad2cd64ea9c705090eb3870b18d044195b2937a71b36abe615645885fd7`；原十八表域及更早快照比较继续兼容。迁移保留报告 `.local/t018-migration-preservation.json` 的 matchesT017FinalRecheck=true、migrationsValidated=10，原十六表摘要 `9aee037b6a20a601e75ee9df189c1a97d5c36a753d3ead94e1cac81c892aa7f0` 与 T017 最终复查逐字节一致，包含所有原行、字段和 BLOB 检查点。原 device_event、execution_adjustment 及新 issue、review_record 均为空，证明原十八表保留。

### 3. 真实 HTTP 复核、权限撤销与并发结论

报告 `.local/review-probe/20261024T123905Z-f324778b/report.json` 为 PASS，真实 Servlet、MySQL、Garage 和 RabbitMQ 完成 108 次 HTTP、780 项检查。使用 8 个隔离账号、1 台设备、2 个任务和 4 个上传会话生成 4 个 PNG 固定版本，其中主任务有 v1/v2/v3，另一个任务有一个跨任务权限夹具；最终形成 4 条异常和 3 条正式结论。检查数不计为独立测试数或性能指标。

| 场景 | 实际结果 |
| --- | --- |
| 异常登记和分页 | 正式创建异常，结果绑定原任务、固定版本、当前创建人及数据库时间；分页按 createdAt/id 稳定排序；创建异常不自动复核版本 |
| 通过与存证独立 | v1 正式 APPROVED，存证仍 PENDING，任务汇总 acceptanceReady=false；随后 v2 独立为 PENDING，原 v1 仍保持 APPROVED |
| 两名复核人并发 | 对 v2 同时提交 APPROVED/RETURNED，恰好一个 200、一个 409；本轮实际胜出为 RETURNED，只有一条正式记录和成功审计，后续查询返回首次结论 |
| 新旧版本结论 | v3 正式 RETURNED，v1 的 APPROVED 未变；旧版异常仍绑定旧版，当前有权操作员仍可为旧固定版本追加异常，不迁移到新版本 |
| 输入与作用域 | 空/超长描述、非法编号、退回原因缺失/空白/超长、非法决定和非 PENDING 预期被拒；他任务版本、未知资源、越权角色及非法分页不能读写历史 |
| 用户和设备隔离 | 任务管理员、仅机构管理员不隐式具备异常/复核角色；设备凭据调用用户异常、复核及读取结论接口均为 401 |
| 真实权限变更 | 经正式 HTTP 移除成员后，原登录身份不能再读结论、读异常或新增写入；正式将已有任务成员降为 ORG_ADMIN-only 后，旧令牌失效，新令牌也不能读取或创建业务异常 |
| 文件与链状态保持 | 四个正式私有对象逐个 GET 后的完整字节和 SHA-256 与原样本一致，复核不覆盖文件、对象键或 PENDING 存证状态 |

本轮没有正式 HTTP 验证 WAITING_ACCEPTANCE 当前最新版本退回重开、历史版本不重开或 COMPLETED 拒写：T019 的提交验收及任务完成入口尚未实现。这些规则目前由上述 21 项复核服务测试覆盖，实际任务状态流留待 T019 验证，不将 schema 回滚检查描述为业务正向闭环。

四条存证消息均核对本次已 SENT 的持久化 Outbox 及事件内容后才由探针 ACK；四个存证任务保持 PENDING，没有新增 chain_attempt 或 Fabric 交易。保留 49 条安全审计，清理本次 2 个任务、8 个账号、1 台设备、4 个上传会话、8 个私有对象，以及 4 条异常、3 条复核和关联 SQL 夹具；独占消息拓扑已删除。原二十张业务表全字段/BLOB 摘要、.env 及演示凭据一致。

### 4. 最终 JAR 兼容性与私有产物

最终 JAR 复查报告 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261024T124029Z-ff8cd2ee/report.json`：15 次 HTTP、113 项检查通过。inspection.json 的 migrationsValidated=10、originalBusinessRowsUnchanged=true；仅追加三个正式核验结果及审计，目标版本核验历史由 15 条变为 18 条，全库 verification_record 由 18 条变为 21 条。

原对象、版本、transactionBytes 和 commitBytes 保持，双机构查询仍为首次 txId=`6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799`、区块号 13 的同一登记，没有新链交易。自有应用已停止，独占空消息拓扑已删除；本次复查不包含 T019 尚未实现的提交验收或任务完成 HTTP 状态流。

私有产物检查 `.local/t018-artifact-audit.json` 通过：本轮 26 份 JSON/日志均为 0600，未含已知服务及演示秘密、JWT 或 PEM 私钥正文；原 .env、演示凭据和 Gateway 身份清单仍为 0600。未清库、删除卷、回改旧迁移或重建凭据。

T018 已完成固定版本异常工单与人工复核。下一项 T019 实现提交验收和任务完成，继续验证当前批次最新资料、复核与存证就绪条件及完成后的终态限制；M3 和完整 MVP 尚未完成。

## T019：提交验收、真实存证就绪与任务完成

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-24 |
| 产物 | 提交验收/完成接口、当前批次最新资料就绪检查、可选资料校验阻塞、数据库完成时间映射、终态写入限制、t019.http 及真实 Fabric 验收探针 |
| 结果 | 262 项后端测试、172 项数据库约束、132 次真实 HTTP/968 项检查通过；7 个版本真实 CONFIRMED、7 个原交易检查点均为 VALID/code=0 |
| 当前边界 | T019 完成，下一项 T020 共享申请、审批、撤销与下载；M1、M2 保持通过，M3 和完整 MVP 尚未完成 |

### 1. 实现和复现

OrgB 的 OPERATOR_TASK_ADMIN 且为任务成员时，可通过 POST /api/v1/tasks/{id}/submit-acceptance 携带 expectedLockVersion，将 RUNNING 提交为 WAITING_ACCEPTANCE。任务必须有子任务，每个子任务至少有一个必交资料项且有当前 RUNNING 执行；每项必交资料都必须在该当前执行下具有固定版本。仅创建上传会话、接收分片或旧执行已有版本不能满足此条件；此时尚未通过复核或确认存证也可先提交委托方验收。

OrgA 的 OWNER_TASK_ADMIN 且为任务成员时，可通过 POST /api/v1/tasks/{id}/complete 携带 expectedLockVersion 完成 WAITING_ACCEPTANCE 任务。服务在父任务锁及当前账号重验后重新检查全部条件：必交资料取当前执行内最新固定版本，均须 APPROVED 且真实存证为 CONFIRMED；当前执行下任何 VERIFYING 上传，包括可选资料，都会阻止完成。历史执行的 VERIFYING 和仍为 UPLOADING 的草稿不计入该阻塞条件。

提交、完成、复核、定版和换机按父任务锁串行处理。当前最新版本退回、新当前版本定版或换机会使等待验收任务回到 RUNNING；历史或非最新版本退回不重开任务。完成事务条件更新状态及锁号，使用 UTC_TIMESTAMP(3) 写 completedAt，并与成功审计同事务提交。重复或并发落败请求返回 409，不刷新首次完成时间。

选择 JDK 21，复用原依赖、Fabric 通道/链码和凭据，在项目根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-acceptance.py
python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/20261023T074934Z-34a10f3c
```

手动接口见 [t019.http](../examples/t019.http)。真实验收启用生产默认 Outbox、定版及 ledger 消费者，在隔离消息拓扑中实际登记链上摘要。正向任务状态均通过正式 HTTP 及生产 Worker 形成，没有用 SQL 制造 WAITING_ACCEPTANCE、APPROVED、CONFIRMED 或 COMPLETED。

### 2. 构建、既有完成字段与数据库验证

最终日志 `.local/t019-build-final.log`：JDK 21 clean verify 成功，262 项后端测试，失败、错误和跳过均为 0，相较 T018 新增 29 项验收服务测试。前一份 `.local/t019-build.log` 同样为 262 项通过；之后补充真实探针中“仅缺复核、链已确认”的独立负例，生产代码未再修改。

inspection_task.completed_at 和状态/时间一致性 CHECK 自 V1 已存在：COMPLETED 必须有时间，其余状态必须为 null。本轮直接复用，不新增 V11、不改写 V1～V10，也不排除原快照中的任何列。TaskSummary 的构造映射及任务列表/详情已返回原数据库完成时间。

报告 `.local/database-probe/20261024T135131Z-b35ee8bc/verify.json`：172 项真实约束通过，本轮新增 5 项，覆盖 COMPLETED 缺时间及四种非终态错误携带时间；全部测试写入回滚。另使用实际 TaskMapper 的提交/完成条件更新，核对预期状态、lockVersion、重复/过期请求拒绝、数据库 UTC 毫秒及 Instant 映射、终态完成时间保持，结果见 taskCompletionTransitions。这些随机回滚夹具验证 Mapper 和字段，不代替下述正式 HTTP 资格验收。

二十表摘要域仍为 `T018-full-twenty-table-snapshot-v1`，SHA-256=`43a4fa2858c6070163c87df4764578589f77dba638b0c7e1ca9c8683d5dc4e41`，原迁移及校验和全部保持。保留报告 `.local/t019-preservation.json` 的 matchesT018FinalRecheck=true、migrationsValidated=10，原十六表全字段/BLOB 摘要为 `3d1f6e89a6ee76d642e8e3336523f254704547f82448da0a98ff1b39581864b4`，与 T018 最终复查一致，原 completed_at 本就参与其中。其余四张业务表仍为空，证明原二十表保持；未修改历史列投影或摘要算法。

### 3. 真实任务状态流、并发与七条链记录

报告 `.local/acceptance-probe/20261024T135552Z-32ad456a/report.json` 为 PASS：真实 Servlet、MySQL、Garage、RabbitMQ、双机构 Gateway 与原 Fabric 网络完成 132 次 HTTP、968 项检查。使用 1 个隔离任务、6 个账号、2 台设备和 9 个上传会话；主任务包含一项必交、一项可选资料，产生 7 个固定版本，全部经正式自动处理形成真实 CONFIRMED。检查数不计为独立测试数或性能指标。

| 场景 | 实际结果 |
| --- | --- |
| 提交资格及并发 | 缺必交固定文件、只有草稿或已收分片时拒绝提交；复核/存证仍 PENDING 的固定版本可提交；两个相同 CAS 请求恰好一个成功 |
| 复核与存证分别检查 | 实际链已确认但尚未复核时，独立拒绝完成；另一个版本已经 APPROVED，但真实 Gateway 受控暂停导致存证 PENDING 时，也独立拒绝完成 |
| 等待状态重开 | 当前最新版本正式 RETURNED、新当前版本定版及正式换机均将 WAITING_ACCEPTANCE 重开为 RUNNING；非最新旧版本 RETURNED 不重开 |
| 当前批次隔离 | 原 CLOSED 批次补传生成历史 v6，大于新当前批次 v5；汇总及验收仍取当前 v5，原批次已确认文件不能满足换机后缺失资料 |
| 可选资料 VERIFYING | 在真实 Garage 读取处暂停当前可选上传，HTTP 与数据库均为 VERIFYING，此时即使必交版已经就绪也不能完成；恢复后正常定版 |
| 历史处理及草稿 | 旧执行 VERIFYING Worker 在真实 S3 输入处暂停，同时保留当前 UPLOADING 草稿；两者不阻止符合条件的当前任务完成 |
| 完成 CAS 和数据库时间 | 两个完成请求恰好一个 200、一个 409；completedAt 为 `2026-10-24T13:56:32.355Z`，仅一次写入；重复完成和后续允许的成员撤销均不改变该时间 |
| 迟到 Worker | 完成后恢复旧 VERIFYING Worker，结果为 FAILED/TASK_NOT_ACTIVE，没有生成新的正式版本，任务保持 COMPLETED |
| 终态写入 | 新上传、草稿 complete、写片、异常、复核、改资料、建子任务、重新启动及换机被拒；已 COMPLETED 上传的 complete 可幂等 202，返回原版本，不写新 Outbox 或版本 |
| 权限与审计 | 上传员、复核人不能替代任务管理员提交，运营商及委托方非成员不能完成；任务终态仍可正式撤销成员，撤权后不能读取；共五次成功提交、一条成功完成审计 |

七个版本均实际读取正式私有对象并核对完整字节及摘要；保存的原 commitBytes 再次向 Peer 查询均为 VALID、validationCode=0，两机构查询同一业务键返回相同首次交易。以下登记已追加保留在原账本：

| evidenceId | 版本 | 首次交易编号 | 区块号 |
| --- | --- | --- | --- |
| `948f30d5-74ef-45cd-a991-2fe9c61446bf` | 1 | `c6641397413e78b28a19413edfe05995620439b85c698e6b441288f0b3565d98` | 14 |
| `948f30d5-74ef-45cd-a991-2fe9c61446bf` | 2 | `fed0f4957638af5de2cc52f1e9232402b54c369754f250f772a6fb21f2d366b6` | 15 |
| `1fa15fb1-77d8-493b-ae75-306faeefb2f8` | 1 | `e704c5187f6a0cac373539c2372731dc9c19562e8cd3ba98588367f8ebed9598` | 16 |
| `948f30d5-74ef-45cd-a991-2fe9c61446bf` | 3 | `c632bff6e44154e7f63a2d8a0a95412b9b57ab0210862ca1fe41804be15b104e` | 17 |
| `948f30d5-74ef-45cd-a991-2fe9c61446bf` | 4 | `b21aab8aa5be30b91e84907758e675ca53ad10e82a73ae58ae8a8474d67d8d4f` | 18 |
| `948f30d5-74ef-45cd-a991-2fe9c61446bf` | 5 | `ed1a202d300888810041a565fe93924445e087d9a2bf10da95df567b690a3c52` | 19 |
| `948f30d5-74ef-45cd-a991-2fe9c61446bf` | 6 | `fb8d95349aac9dd09a733c5c34fc54366dcaca6c3e986850d81c9b0ab6d24df2` | 20 |

报告仅保留安全业务编号、完整摘要和检查点 SHA-256，不保存原交易/确认字节或私钥。七条本次链记录永久追加保留，不删除或重建账本；对应链外夹具已经清理，不能继续用报告中的任务/版本 UUID 调用业务 HTTP。

保留 69 条安全审计，清理本次 1 个任务、6 个账号、2 台设备、9 个上传会话、17 个私有对象及关联 SQL 夹具；独占消息拓扑已删除。清理后原二十张业务表每行全字段（包含 completed_at）和 BLOB 检查点摘要相同，原 .env、演示凭据及 M2 成果保留。

### 4. 最终 JAR 兼容性与私有产物

最终 JAR 复查报告 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261024T135652Z-2db13ffa/report.json`：15 次 HTTP、111 项检查通过。inspection.json 的 migrationsValidated=10、originalBusinessRowsUnchanged=true；原行全字段及 BLOB 不变，原任务 completed_at 仍为 null。仅追加三个正式核验结果及审计，目标版本核验历史由 18 条变为 21 条，全库 verification_record 由 21 条变为 24 条。

原对象、版本、transactionBytes 和 commitBytes 保持，双机构仍查询首次 txId=`6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799`、区块号 13 的同一登记。该 M2 复查没有新增链交易；本节前述区块 14～20 的七条新登记来自隔离验收探针，继续追加保留。复查自有应用已停止，独占空消息拓扑已删除。

私有产物检查 `.local/t019-artifact-audit.json` 通过：本轮 27 份 JSON/日志均为 0600，未含已知服务及演示密码、原设备令牌、JWT 或 PEM 私钥正文；原 .env、演示凭据和 Gateway 身份清单仍为 0600。未清库、删除卷、回改历史迁移或重建凭据。

T019 已完成提交验收、任务完成与终态限制的真实验收，补齐 T018 尚未正式 HTTP 验证的任务状态流。下一项 T020 实现共享申请、审批、撤销和下载，M3 和完整 MVP 尚未完成。

## T020：版本共享、授权撤销与私有下载签发

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-25 |
| 产物 | V11 版本授权表、申请/审批/拒绝/撤销及分页、统一任务或版本授权、版本可见列表、共享核验、本地 S3 下载签发、t020.http 与真实共享探针 |
| 结果 | 311 项后端测试、206 项数据库约束、123 次真实 HTTP/866 项检查通过；两个固定版本真实 CONFIRMED、原 commit 检查点均为 VALID/code=0 |
| 当前边界 | T020 完成，下一项 T021 审计查询及业务接口整合；M1、M2 保持通过，M3 和完整 MVP 尚未完成 |

### 1. 实现、权限和复现

POST /api/v1/access-grants 以 clientRequestId、versionId、reason、requestedValidUntil 申请指定版本共享。已注册有效账号可提交已知版本编号，不因此获得文件正文或父任务信息；同申请人、同请求键及内容返回原记录，不同内容为 409。申请记录保存 PENDING、APPROVED、REJECTED、REVOKED；APPROVED 超过数据库 validUntil 时对外派生 EXPIRED，不由定时器改写原状态，重复申请不延长原期限。

审批、拒绝、撤销和任务申请队列仅允许 OrgA 的 OWNER_DATA_APPROVER 且为任务成员。批准前再次检查固定版本复核 APPROVED、存证 CONFIRMED，以及未来有效期不超过申请期限；拒绝和撤销必须有原因，状态及 expectedLockVersion 条件更新与成功审计同事务提交。审批权限不由 OWNER_TASK_ADMIN 或单独 ORG_ADMIN 隐式获得。申请人可以分页查看自己的申请元数据，不能据此读取整个任务队列。

ResourcePermissionService 对版本统一执行“当前任务权限，或面向该用户/该版本的有效授权”。版本列表在 SQL 分页前过滤，详情、anchor、下载、核验及核验历史再次执行相同规则。共享仅覆盖指定版本，不开放整个任务、异常、复核写入或其他版本；新版本不继承旧版授权。核验仍在文件/链 I/O 后重新锁任务、重验账号及授权，期间失去权限则不保存或披露结果。

POST /api/v1/evidence-versions/{id}/download-url 在任务、当前账号及共享授权行锁下本地签名，返回 url、expiresAt 和 Cache-Control:no-store，不在事务内请求 S3。任务路径最长 300 秒；共享路径使用 floor(min(300 秒, 数据库授权剩余时间))，不足一秒拒绝。SigV4 时间固定为数据库 UTC 秒，核对真实 X-Amz-Date/X-Amz-Expires 后返回实际到期时间，不能超过授权；签名期间已过期也不返回地址。URL_ISSUED 仅记录签发，不记录为下载成功，不保存签名地址、对象键或凭据。

选择 JDK 21，复用原基础设施、私有桶、Fabric 网络及凭据，在项目根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-sharing.py
python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/20261023T074934Z-34a10f3c
```

接口顺序见 [t020.http](../examples/t020.http)，下载复用原 S3_ENDPOINT、region、path-style 和静态凭据，无新密钥或公网配置。本地初始化及验收依旧保留原 .env、演示身份和账本。

### 2. 构建、V11 和原数据保留

最终日志 `.local/t020-build-final.log`：JDK 21 clean verify 成功，311 项后端测试，失败、错误和跳过均为 0，相较 T019 新增 49 项：共享服务 29 项、统一权限及版本可见性 8 项、下载签发 12 项。下载测试包含真实 AWS SDK 离线签名，验证数据库固定时间、1/17/300 秒期限、浏览器可执行签名、编码和安全错误处理；其余测试覆盖 CAS、审计回滚、权限、I/O 后重验和授权边界。

首轮 `.local/t020-build.log` 的 HTTP 测试因沙箱禁止本机监听端口，出现 SocketException/Operation not permitted；允许本次 localhost 测试端口后，以相同源码完整重跑得到上述 311 项通过，没有为绕过测试修改生产代码。

V11__version_access_grants.sql 实测校验和为 `1029133993`，V1～V10 保持原校验和。access_grant 同时承载申请和指定版本授权，唯一限制 (applicant_id, client_request_id)，保留审批/拒绝/撤销人员、原因及数据库 UTC 时间；版本及申请/审批/撤销用户引用采用 RESTRICT，状态字段配对、请求/批准/撤销时间范围及摘要格式由数据库约束。原二十表未新增或改写列。

数据库报告 `.local/database-probe/20261025T010944Z-e3c94e6a/verify.json`：206 项真实约束通过，本轮新增 34 项，全部探针写入回滚。新增检查覆盖幂等键、四种持久状态和派生过期、摘要/文本、审批及撤销字段配对、期限、四个外键及删除限制；正向验证状态往返、不同申请人的独立请求键、1000 字中文原因和数据库 UTC 默认值。授权业务角色、文件就绪条件及签发流程由下述真实 HTTP 验证。

二十一表域为 `T020-full-twenty-one-table-snapshot-v1`，SHA-256=`d5011fc698011a79f140ecc662b0e5da272b84559ad1c65170125f5fcb2f80f3`；原二十表仍以 `T018-full-twenty-table-snapshot-v1` 单独重算为 `8675062b9d98fb14a039f35dd6e0247052451b35b92f3634f80f5f35de5187bc`。保留报告 `.local/t020-preservation-after-migration.json` 的 matchesT020BeforeMigration=true，与开发前 `.local/t020-preservation-before.json` 比较，原十六表摘要均为 `91f0ed890ecba311675f69f43cd6fa0896902983fd63c8d730a5b13644e43652`，包含所有旧列、completed_at 和 BLOB 实际字节；原全库 24 条核验记录保留。Inspector 验证十一版迁移，新授权表不混入原十六表快照域。

### 3. 真实共享、私有 GET、权限变化和链登记

报告 `.local/sharing-probe/20261025T011035Z-c4276bac/report.json` 为 PASS。真实 Servlet、MySQL、Garage、RabbitMQ、双机构 Gateway 与原 Fabric 完成 123 次 HTTP、866 项检查，创建 1 个任务、9 个隔离账号、1 台设备、2 个上传会话、2 个固定版本和 5 条共享申请。正向版本、复核、存证及授权状态均由正式 HTTP 和生产自动 Worker 形成；检查数不计为独立测试数或性能指标。

| 场景 | 实际结果 |
| --- | --- |
| 批准前访问和申请 | 未批准时版本详情、anchor、下载、STORED/COPY 核验及核验历史均拒绝，列表为空；申请响应仅有安全申请元数据；相同键返回原记录，不同内容 409 |
| 正式批准就绪条件 | 未复核且未上链时不能批准；正式 APPROVED 但真实链处理暂停导致 PENDING 时独立拒绝；另一个版本已真实 CONFIRMED 但尚未复核时也独立拒绝 |
| 角色、CAS 和拒绝 | 任务管理员、运营商及非成员审批员不能审批；并发批准/拒绝只有一个成功、另一个 409；拒绝原因必填，批准不能超过申请期限；被拒申请人仍无文件权限 |
| 精确版本可见性 | 获批后可读指定版本、anchor 和 SQL 分页列表，total 与分页结果先过滤权限；旧版授权不能读取新版本，新版需独立申请批准；包括仅 ORG_ADMIN 的已注册用户也只获得该版本权限，不能读父任务 |
| 真实私有下载 | 任务路径和长授权路径实际 TTL 均为 300 秒；签名 GET 返回原 PNG 完整字节及摘要，同对象去掉签名查询后为 403；响应只有 url/expiresAt 且不可缓存 |
| 共享核验 | 原对象和相同 COPY 均 MATCH，修改副本为 MISMATCH；三条正式记录及历史只在授权版本范围内可见 |
| I/O 中撤销 | 暂停真实 Garage 读取后，通过正式 HTTP 并发撤销授权，只有一个成功；恢复核验后为 403，不保存结果；之后不能签发新 URL、读原版本或核验历史 |
| 已发地址撤销窗口 | 撤销前已经签发的地址仍可在原 expiresAt 内实际 GET 原字节；撤销不延长地址期限，不将这一行为表述为新授权 |
| 自然过期 | 约 8 秒短授权实际签发 TTL 为 7 秒；自然经过数据库期限后，查询派生 EXPIRED，版本及新下载为 403，过期授权不能再撤销；原记录及期限未被重写 |
| I/O 中禁用身份 | 已有真实有效授权的隔离账号在 Garage 读取暂停期间被禁用，恢复后核验为 401、不保存结果，之后不能读取版本；该项仅直接修改本次负例账号的 enabled，不涉及原账号或正向状态 |

三次成功的任务/共享 URL 签发分别有 URL_ISSUED 审计；真实 GET 结果由探针独立验证，审计不将地址签发表述为下载完成。授权撤销、账号失效及自然到期停止新凭证签发，已经获得的文件副本不能由平台收回。共享发生时任务仍可为 RUNNING，不要求先完成总任务。

两个正式对象完整字节和摘要保持，两个存证均为真实 CONFIRMED、每版只有一个原交易尝试；保存的 commitBytes 再次向 Peer 查询为 VALID/code=0，OrgA/OrgB 实际查询五字段及首次交易一致。本次新增登记的 evidenceId 均为 `d362c759-ef45-4b7b-ab0c-1b0520350a7c`：

| 版本 | 首次交易编号 | 区块号 | 文件 SHA-256 |
| --- | --- | --- | --- |
| 1 | `abbcfb833511e2b46f2c5f6e75662e053232dcfd20b612f045d758a69a63aef9` | 21 | `5a249ce891928ad689a1dd29eb03177155c40ef4f72096d92af4c90f56ccbdaa` |
| 2 | `92ee5aceb52dbbb894895a8af0764c11100400330c83334a037825af5254e987` | 22 | `29155b5fccee83b434e8487aef0d223394c061f646ad2fda280952dc83746526` |

这两条链登记继续追加保留，不删除或重建账本；报告只保留安全编号、摘要和检查点哈希，不保存签名 URL、JWT、私钥或二进制检查点正文。清理本次 1 个任务、9 个账号、1 台设备、2 个上传会话、4 个私有对象及关联申请/版本/核验数据，保留 46 条安全审计，独占消息拓扑已删除。原二十一张业务表全字段和 BLOB、.env、演示凭据均保持；链外测试夹具已删，报告中的业务 UUID 不能用于继续调用 HTTP。

### 4. 最终 JAR 兼容性与私有产物

最终 JAR 复查报告 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261025T011131Z-e180975f/report.json`：15 次 HTTP、117 项检查通过。inspection.json 的 migrationsValidated=11、originalBusinessRowsUnchanged=true、accessGrantTableRows=0；原全字段/BLOB、对象、版本和检查点保持，仅追加三个正式核验结果及审计，目标版本核验历史由 21 条变为 24 条，全库 verification_record 由 24 条变为 27 条。

原 M2 两机构查询仍对应首次 txId=`6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799`、区块号 13，原尝试保持 VALID/code=0，transactionBytes/commitBytes 长度为 4630/905，字节摘要未变。M2 复查没有新增链交易；区块 21、22 的两条新登记来自前述隔离共享探针，继续追加保留。自有应用已停止，ownApplicationStopped=true、emptyOwnTopologyDeleted=true，无项目后端或业务探针进程残留。

私有产物检查 `.local/t020-artifact-audit.json` 通过：本轮 33 份 JSON/日志均为 0600，未含已知服务及演示密码、原设备令牌、JWT、PEM 私钥正文或原始签名 URL；原 .env、演示凭据和 Gateway 身份清单仍为 0600。检查时将旧 `.local/database-probe/latest.json` 汇总元数据文件从 0664 收紧为 0600 后重新扫描通过，existingDatabaseLatestSummaryModeTightened=true；该项未修改业务数据、服务秘密、Java 或迁移。未清库、删除卷、回改历史迁移或重建凭据。

T020 已完成指定版本共享申请、审批、撤销和受控下载。下一项 T021 完成审计查询及业务接口整合；M3 和完整 MVP 尚未完成。

## T021：任务审计查询与固定版本业务关系整合

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-25 |
| 产物 | 任务范围审计分页、动作/资源类型/trace 精确过滤、固定版本业务关系分页、统一任务历史权限、t021.http 及真实审计/关系探针 |
| 结果 | 339 项后端测试、206 项数据库约束、最终 161 次真实 HTTP/1836 项检查通过；两次探针共追加保留四条真实链登记 |
| 里程碑 | T021 完成，T015～T021 业务补齐里程碑 M3 通过；下一项 T022/M4，完整 MVP 仍待后续任务至 T034 |

### 1. 实现、权限和复现

GET /api/v1/tasks/{id}/audit-logs 支持 page、pageSize、action、resourceType、traceId；默认 1/20，pageSize 最大 100，按 createdAt DESC、id DESC 稳定排序。查询先通过 resourceType 和真实资源引用取得任务归属，再过滤和分页，不以调用者、机构、设备编号或 traceId 推断任务归属。覆盖任务、执行、设备事件、上传、版本、存证任务、异常及共享申请的既有审计；全局账号登录、角色修改或设备管理审计不因相同身份/trace 被收入任务历史。

GET /api/v1/tasks/{id}/evidence-relations 支持相同分页及可选 requirementId、executionId。先按任务和过滤条件 COUNT/LIMIT 取得最多 100 个固定版本，再以本页版本编号分别批量汇总，避免多个一对多关联重复计数。返回安全文件元数据、任务资料/子任务/执行/上传编号、currentExecution、同资料同执行内 latestInExecution、异常数量、唯一复核的人员/决定/时间、存证状态及首次确认交易信息、共享状态计数和核验数量。没有全量异常/授权数组、申请理由、复核原因、对象键、二进制检查点或签名 URL。

共享 active/expired 计数采用同一次数据库 asOf；blockNumber/validationCode 来自与 confirmedTxId 匹配的持久化尝试，没有对应记录则为空，不发起 Fabric RPC。审计与关系查询均先锁父任务，再锁定并重新认证当前账号，使用统一 requireTaskHistory：OrgA 的 OWNER_TASK_ADMIN、OWNER_REVIEWER、OWNER_DATA_APPROVER，或 OrgB 的 OPERATOR_TASK_ADMIN，且必须为任务成员。OPERATOR_USER、仅 ORG_ADMIN、仅有版本共享授权均不能读取任务历史；共享不会扩大为整个任务的审计权限。

两个查询均为业务只读，不写审计或其他业务记录，不读取 S3、不调用 Gateway。URL_ISSUED 在审计界面明确标为“下载地址签发”，不表示文件已成功下载。任务完成后仍按当前权限可读，成员撤销、账号角色变化和禁用会影响之后的查询。

选择 JDK 21，复用原依赖、凭据和 Fabric 网络，在项目根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-audit.py
python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/20261023T074934Z-34a10f3c
```

手动请求见 [t021.http](../examples/t021.http)。只读接口仅需 database profile；真实探针同时启用存储、消息及 ledger，通过正式业务生成可核对的关系与审计。

### 2. 构建、数据库及原有基线

最终日志 `.local/t021-build-final.log`：JDK 21 clean verify 成功，339 项后端测试，失败、错误和跳过均为 0。相较 T020 新增 28 项，包括任务历史权限 4 项、审计查询 15 项和版本关系 9 项，覆盖锁序、权限撤销、无共享回退、分页/过滤、long offset、独立汇总、缺失关系、安全序列化及动态 SQL 分支解析。

本轮无新增迁移，V1～V11 校验和保持不变。报告 `.local/database-probe/20261025T070235Z-052c73f0/verify.json` 的 206 项真实约束全部通过，测试写入回滚；二十一表仍使用 `T020-full-twenty-one-table-snapshot-v1`，SHA-256=`7ba218b4dd73bfc467564d0afb7ec5a68a43fcc046ccd22d05e6482a8c15decd`。原二十表域摘要为 `3f39feffd4d9d2e010c784a437a54f514c422821eaca08e9037be87d3c0617bd`，更早的全字段/BLOB 域保持兼容，没有调整旧列投影。

开发前报告 `.local/t021-preservation-before.json` 的 matchesPreviousFinalRecheck=true、migrationsValidated=11，原十六表摘要为 `1527f37b889dee1f63f0279432de4dc5a4469bec5c52861b75694b325fc2db33`，包含原 completed_at、所有旧字段和 BLOB；原全库核验记录为 27 条。两次真实探针均逐表核对清理后原二十一表未变，原账号、凭据和既有 M2 成果保留。

### 3. 真实分页、任务隔离和关系核对

最终报告 `.local/audit-probe/20261025T070803Z-13612c9f/report.json` 为 PASS：161 次真实 HTTP、1836 项检查通过。使用两个任务、七个隔离账号和同一台设备，刻意复用调用者、设备及 traceId；主任务生成两个同当前执行的固定版本，经过正式复核、真实存证、共享申请、实际核验、提交验收和任务完成，另一个任务保留独立执行及上传草稿。检查数不计为独立测试数或性能指标。

| 场景 | 实际结果 |
| --- | --- |
| typed 任务归属 | 主任务准确返回 42 条、另一任务 9 条任务审计；同 trace 的 12 条全局身份/设备事件明确排除，没有按共同身份、设备或 trace 串入另一任务 |
| 审计分页及过滤 | pageSize=1 遍历各页，total、唯一编号及 createdAt/id 倒序均与独立 SQL oracle 一致；动作、资源类型、trace 精确过滤有效，页尾为空；URL_ISSUED 仅显示一次地址签发 |
| 管理角色与共享隔离 | 有效委托方三类业务角色及运营商任务管理员可读取；操作员或仅共享用户不能读任务审计/关系，主任务复核成员不能借共同身份访问另一任务历史 |
| 当前身份复核 | 正式移除成员、正式降为 ORG_ADMIN-only 后两个接口均拒绝；旧角色令牌失效，重新登录也不恢复业务权限；禁用隔离账号后为 401 |
| 安全关系与数量 | 本页固定版本元数据、异常数、唯一复核、首次确认尝试、核验数及六种共享计数逐项与 SQL 对照；active/expired 使用响应中的同一 asOf，未因多关联放大行数或计数 |
| 当前及最新标志 | 本次两个同当前执行的版本正确区分 latestInExecution，保留各自复核和存证；本轮实际覆盖同当前批次两版，历史执行标志见单测及 T016/T019 验证 |
| 关系过滤和分页 | pageSize=1 无重复、按创建时间及编号倒序；合法但属于其他任务的 requirementId/executionId 过滤返回空，草稿任务没有固定版本关系，不泄露他任务是否存在资料 |
| 业务只读 | 审计和关系查询前后业务数据及审计总量不变，S3 和 Gateway 调用计数不增加，readEndpointsAddNoAuditOrNetworkCalls=true |

本次最终运行保留 63 条安全审计；清理两个任务、七个账号、一台设备、四个上传会话、六个私有对象、一个自有设备在线 Redis 键和关联 SQL 夹具，独占消息拓扑已删除。两个固定版本均为真实 CONFIRMED，原 commitBytes 再次查询为 VALID/code=0，双机构实际查询首次交易及五字段一致；原二十一表全字段/BLOB、.env 和演示凭据保持。

首轮报告 `.local/audit-probe/20261025T070335Z-c199b8d7/report.json` 保留失败状态：完成 98 次 HTTP/693 项检查后，独立 oracle 假设 JDBC 的 DATETIME 只会返回 Timestamp，遇到 Connector/J 返回 LocalDateTime 时失败，failedCheck=expected_jdbc_utc_timestamp。随后仅修正探针 UTC 类型处理，并为尚未执行到的文件元数据 oracle 补齐 upload_session 的 filename/contentType 关联；生产实现未变。`.local/t021-build-initial.log` 和修正后的最终 clean verify 都为 339 项通过，最终真实探针完整重跑成功。首轮夹具已清理、originalBusinessDataUnchanged=true，已产生审计及真实链登记仍然保留。

因此本轮共追加四条真实链登记，不只统计最终成功运行的两条：

| 运行 | evidenceId | 版本 | 首次交易编号 | 区块号 |
| --- | --- | --- | --- | --- |
| 首轮 oracle 失败 | `7e943fc0-5415-4b12-9ab7-39e8ff243601` | 1 | `42dab459fe194fb7ac4deb6f45f96aa1189d77fa9745a3292bc73ca49e2d6302` | 23 |
| 首轮 oracle 失败 | `7e943fc0-5415-4b12-9ab7-39e8ff243601` | 2 | `983aa2a13136b76284d3c5d8613e3da4c24f4ae1f17d475533403ae5fb1822ed` | 24 |
| 最终完整通过 | `e2c0082b-65df-4372-a534-dd2a2859d64e` | 1 | `1c170a95169a0e76066b346a832571a5f0e83046d4293b207fc2564f45bf620d` | 25 |
| 最终完整通过 | `e2c0082b-65df-4372-a534-dd2a2859d64e` | 2 | `53b2125f15ef4fed8d7e42cce581e47aaa0812b5ea85b534d96c861245fc1066` | 26 |

四条登记均继续保留在原账本，不删除或重建账本。报告只保存安全编号、文件摘要和检查点哈希，不保存原交易字节、私钥或签名下载地址；两个运行的链外夹具都已删除，不能用报告中的任务/版本 UUID 继续调用 HTTP。

### 4. 最终 JAR 兼容性与里程碑收尾

探针后只读报告 `.local/t021-preservation-after-probes.json` 的 matchesT021BeforeWork=true，原十六表全字段/BLOB 摘要仍为 `1527f37b889dee1f63f0279432de4dc5a4469bec5c52861b75694b325fc2db33`，原 27 条核验及原凭据保持，覆盖首轮失败和最终成功两个真实探针的清理收尾。

最终 JAR 复查报告 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261025T070934Z-b0b54eaa/report.json`：15 次 HTTP、113 项检查通过。inspection.json 的 migrationsValidated=11、originalBusinessRowsUnchanged=true、accessGrantTableRows=0；仅追加三条正式核验及审计，目标版本核验历史由 24 条变为 27 条，全库 verification_record 由 27 条变为 30 条。追加后的原十六表快照摘要为 `4eb3ba6997a8932daa791b6ee744a7fca48f6b8e4452a2433b11e60cccc0da79`。

原 M2 双机构查询仍对应首次 txId=`6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799`、区块号 13，原尝试为 VALID/code=0，transactionBytes/commitBytes 长度 4630/905 及字节摘要保持。M2 复查没有新增链交易；区块 23～26 的四条登记来自本节前述两次隔离探针，继续追加保留。自有应用已停止，独占空消息拓扑已删除，无项目后端或业务探针残留。

私有产物检查 `.local/t021-artifact-audit.json` 通过：包含首轮失败运行在内的 35 份 JSON/日志均为 0600，未含已知服务及演示密码、原设备令牌、JWT、PEM 私钥正文或原始签名下载 URL；原 .env、演示凭据和 Gateway 身份清单仍为 0600。未清库、删除卷、回改历史迁移或重建凭据。

T021 已完成，T015～T021 的业务补齐里程碑 M3 通过，M1、M2 保持通过。下一项 T022 开始 M4 可靠性完善，继续验证崩溃、重试和清理故障；完整 MVP 尚未完成，后续继续至 T034。

## T022：Outbox 发布恢复、消费确认与坏消息拒收

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-25 |
| 产物 | 严格消息信封、Outbox 永久失败状态、消费 ACK 持久化门槛、安全拒收记录及两 JVM 故障恢复探针 |
| 已验证结果 | 384 项后端测试、220 项数据库约束通过；双 JVM 真实故障探针 19 次业务 HTTP/1248 项检查、旧流水线回归及原 M2 复查通过 |
| 里程碑 | T022 完成，M1、M2、M3 保持通过；下一项 T023，M4 尚未通过 |

### 1. 消息身份、ACK 与安全拒收

消息体严格限制为不超过 4096 字节的有效 UTF-8 JSON，恰好包含 eventId、eventType、bizId、traceId 四个字符串。未知字段、重复字段、尾随 JSON、非法类型或身份均拒收。发布前同时核对消息体与 Outbox 独立列中的 eventId、eventType、bizId；无效的持久化信封进入 FAILED，last_error 固定为 INVALID_ENVELOPE，清空租约且没有 sent_at，不再被发布扫描领取。

发布保留原 eventId，消息为持久化且 mandatory。发布确认和 mandatory return 分别检查，只有确认成功且没有返回才尝试写 SENT；未路由、NACK 或确认不明保留退避计划。claim、SENT、retry、FAILED 均按数据库时间检查状态、租约 token 和有效期，已失去租约的结果不能覆盖新领取者。生产发布器始终设置 messageId=eventId；消费端提供 messageId 时必须与信封身份完全一致，兼容既有无 messageId 的投递。

两个消费者复用同一手动确认流程。处理器返回后再次在父任务锁下检查 consumed_event.DONE，以及业务终态或已提交的恢复计划：临时等待必须没有处理租约且具有 next_action_at。仅 claim 返回空、他人仍持有租约或旧 Worker 失去租约，都不足以 ACK。独立业务恢复扫描继续绕过 DONE，沿用既有业务处理器，不把消费完成误作文件定版或 Fabric 确认。

无效信封、未知持久化事件及 messageId 不匹配，先经独立 REQUIRES_NEW 事务提交 message_rejection，再 basicReject(requeue=false) 进入死信。数据库只保存消费方、完整消息体 SHA-256、字节数、固定原因码、规范 UUID messageId 或 NULL、数据库 UTC 时间，不保存消息正文、任意 header、令牌或错误原文。相同消费方和消息体摘要只保留首次记录；两个消费方分别去重。

拒收记录失败或处理责任尚不能安全移交时，维持未 ACK、未 reject，在事务结束后显式等待 5 秒再关闭通道，使原投递可以重投。审查 Spring Rabbit 4.1.1 的运行时通道重启行为后，确认容器 recoveryInterval 并不保证该路径的等待，因此实际退避放在共用消费流程中；recoveryInterval 仍用于启动失败恢复。等待期间 prefetch=1，线程中断时恢复中断标志并关闭通道，不立即 requeue。

选择 JDK 21，复用原服务及凭据，在项目根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-messaging.py
python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/20261023T074934Z-34a10f3c
```

消息探针使用独占 RabbitMQ 拓扑和隔离业务夹具；第一 JVM 在真实发布确认后、SENT 落库前主动 halt，第二 JVM 等待原 120 秒发送租约自然到期后恢复，不通过 SQL 提前改租约。中断在有意 halt 后需要单独继续时，可使用 verify-messaging.py --recover 加原私有运行目录。手动观察现有上传与存证接口见 [t022.http](../examples/t022.http)；故障注入只存在于独立测试装配，没有新增故障控制业务接口。

### 2. 构建、迁移及原有数据保留

`.local/t022-build-final.log` 的 JDK 21 clean verify 通过：384 项后端测试，失败、错误和跳过均为 0。相较 T021 新增 45 项，其中 Outbox/信封 17 项、消费流程和拒收记录 12 项、ACK 持久化门槛 16 项。覆盖身份绑定、严格解码、确认及返回、条件更新、等待计划、忙租约、拒收记录失败、事务提交异常、pause→abort 顺序和中断恢复。后续只调整独立探针：将实际退避断言收紧至至少 5 秒、适配 RabbitMQ 管理统计的采样延迟，并补齐旧 Pipeline 探针对自有拒收行的清理。`.local/t022-probe-compile.log` 和 `.local/t022-probe-compile-final.log` 的 test-compile 通过；生产和上述 384 项单测未变，没有将编译检查计作重复单测执行。

报告 `.local/database-probe/20261025T154122Z-d14e8d17/verify.json` 的 220 项真实约束全部通过，新增 14 项，所有测试写入回滚。V1～V11 校验和不变，V12__durable_message_rejections.sql 实际 Flyway 校验和为 1723908793；该迁移只扩展 Outbox 终态约束并新增 message_rejection，没有改写旧业务行。

新二十二表域为 `T022-full-twenty-two-table-snapshot-v1`，SHA-256=`e67e61149c0ea7ad54f9955d097aadd76dd1688b6f8a14bfe25e9390cc283cc6`；兼容的原二十一表摘要为 `496be51ec518aa6c099bfc4192207cad5852fda668acde328208ebdb8c715576`。回滚正向检查还实际验证了 FAILED 的租约所有权与终态、重试退避期间不能领取、拒收首次元数据不被重复投递改写、消费方隔离、规范或空 messageId，以及数据库 UTC 时间。

迁移后只读报告 `.local/t022-preservation-after-migration.json` 的 matchesT022BeforeMigration=true、migrationsValidated=12；原十六表全字段/BLOB 摘要仍为 `4eb3ba6997a8932daa791b6ee744a7fca48f6b8e4452a2433b11e60cccc0da79`，与开发前和 T021 最终 M2 复查一致，原 30 条核验保持。拒收表和 FAILED Outbox 行均为 0，原 .env、演示账号、设备凭据及 Fabric 检查点保持；旧快照域及列投影未变。

### 3. 双 JVM 发布崩溃、责任移交与真实死信

最终汇总 `.local/messaging-probe/20261025T155232Z-95251a9d/verify.json` 为 PASS。第一 JVM 的 crash.json 为 14 次业务 HTTP/96 项检查，进程以预定的 73 退出；第二 JVM 的 report.json 为 5 次业务 HTTP/1152 项检查，合计 19 次业务 HTTP/1248 项检查。两个 JVM 的 PID 不同，检查项包含真实服务状态、RabbitMQ 操作及管理统计断言，不作为 1248 个独立测试或性能指标。

| 场景 | 实际结果 |
| --- | --- |
| mandatory return 与崩溃窗口 | 先以真实无路由投递验证原 eventId 保持 PENDING 并退避；恢复路由后收到真实 confirm，第一 JVM 在写 SENT 前主动 halt，此时消息已入队、原行仍为 SENDING |
| 自然租约到期及竞争 | 发送租约沿用生产默认 120 秒；第二 JVM 启动后等待剩余 111843 毫秒，未改 SQL 租约。两个并发真实事务只有一个取得新 token，旧 token 的 SENT/retry 更新均失败，复用原 eventId 再次发布后才写 SENT |
| claim 暂时失败 | 注入 SQL 执行前的数据访问异常，两个 broker 副本仍保留且不伪造 DONE/恢复计划；实际两次失败处理间隔为 5025 毫秒，没有立即重投热循环 |
| 可恢复业务失败后 ACK | 对真实 Garage 读取注入一次失败，处理器先提交 VERIFYING、next_action_at、无处理租约和 DONE，再确认两个重复副本；此时尚无固定版本，之后由恢复扫描完成定版 |
| 正在处理的重复投递 | 恢复扫描在真实对象读取中持有处理租约，另一个同事件投递未能 claim；ACK 门槛拒绝提前确认。释放读取后仅生成一个固定版本与一个存证任务 |
| 实际重复投递 | 两种事件各额外投递 8 次，共 16 次，最终只有一个固定版本、一个存证任务、一个链尝试和各消费方一条 DONE，首次确认交易未被重放替换 |
| 无效 Outbox 源数据 | 两条专属负例夹具分别验证坏信封和独立身份列不匹配，均在首次尝试进入 FAILED，不发送到 broker、不被后续扫描重新领取 |
| 拒收写库失败 | 在拒收 SQL 前注入异常时，拒收记录为 0、死信为 0，broker 仍观察到一份原投递；实际重试间隔为 5014 毫秒。解除注入后，先提交拒收元数据再进入真实死信 |
| 坏消息及去重 | 两个消费者实际接收无效 UTF-8、未知或重复字段、错误事件类型、未知持久化事件、messageId 不匹配、尾随 JSON 和错误字段类型；重复坏正文最终保留 17 条拒收元数据，对应 19 次真实死信投递 |

死信逐条核对 x-death 来源队列、消息体摘要及投递次数，只消费本次独占死信队列中的预期消息。message_rejection 仅保存安全摘要等元数据；RabbitMQ 私有死信队列仍承载原消息正文，不能将数据库脱敏表述为 broker 不保存正文。

数据库类故障由测试装配在指定 SQL 前抛出数据访问异常，未关闭共享 MySQL；对象读取及 broker、数据库事务、Fabric 查询均使用原真实服务。NACK、确认等待异常和事务提交失败等边界另有单测覆盖，不将它们表述为本次实际停止服务的故障演练。租约续期及更完整的 Worker 恢复、清理故障矩阵仍属于 T023 及后续 M4 任务。

首轮 `.local/messaging-probe/20261025T154417Z-4c6b2b05/report.json` 保留失败状态：crash 阶段 14 次 HTTP/96 项检查通过，recover 阶段执行到 5 次 HTTP/773 项检查，failedCheck=no_dlq_or_ack_before_rejection_metadata_transaction_commits。原因是探针将 RabbitMQ 管理接口采样中的暂时 unacked=0 当作即时队列事实；拒收记录和死信当时均为 0。修正为有界等待 broker 观察到保留副本，再分别断言拒收记录和死信仍为 0，生产代码未变，之后以新夹具完整重跑通过。首轮夹具与独占消息拓扑已清理，originalBusinessDataUnchanged=true；其已提交审计及真实链登记继续保留。

两次运行因此共追加两条真实登记，均为 VALID/code=0，不只统计最终通过运行的一条：

| 运行 | evidenceId | 版本 | 首次交易编号 | 区块号 |
| --- | --- | --- | --- | --- |
| 首轮统计断言失败 | `89400601-8df4-4f8d-b7c1-7bc8a26d6214` | 1 | `23c8b6654bc304b208722c94ea5757b5eb5dc7b19c17d096f4a4e53476554d1e` | 27 |
| 最终完整通过 | `eb2462b7-eb6c-440f-985a-3586672753f4` | 1 | `10ec34283533f93f93f366fa7c59a3b2c983ca49b6c1e931b6bce5c991c5d1da` | 28 |

两条登记的文件摘要均为 `6d15974dccf7bd8fb20cd3fec0101fcd89da7c32a9a7dfa623e5a50ffcf97cef`，已保存的 commitBytes 再查询与双机构查询均对应各自原交易。最终运行保留 19 条安全审计，清理本次一个任务、三个账号、一台设备、一个上传会话、两个私有对象、17 条拒收记录及相关业务夹具；临时夹具凭据文件和独占消息拓扑已删除。原二十二表全字段/BLOB、.env 和演示凭据保持。账本和审计继续追加保留；报告中的夹具 UUID 不能用于继续调用 HTTP。

### 4. 旧流水线回归、原 M2 复查与收尾

旧自动流水线报告 `.local/pipeline-probe/20261025T155047Z-7721e692/report.json` 为 PASS：42 次 HTTP/404 项检查，覆盖自动发布、重复消费、存储失败恢复及实际 Garage 字节核对。本次以非 ledger 模式运行，存证任务保持 PENDING，不增加链登记。旧探针的坏消息标记现在会产生一条拒收记录；清理通过执行前保存的编号集合，仅删除本次新建行，保留预先存在的同摘要记录。报告确认 fixtureMessageRejectionsDeleted=1、原十四表快照保持、独占拓扑清理完成，并保留 36 条安全审计。

原 M2 最终 JAR 复查报告 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261025T155700Z-1b3b59fa/report.json` 为 PASS：15 次 HTTP/111 项检查。其 preflight.json 在追加核验前的原十六表全字段/BLOB 摘要仍为 `4eb3ba6997a8932daa791b6ee744a7fca48f6b8e4452a2433b11e60cccc0da79`，覆盖前述两个消息探针及旧流水线清理后的基线；据此生成的 `.local/t022-preservation-after-probes.json` 的 matchesT022BeforeWork=true，原十六表行数保持、原 30 条核验未变。inspection.json 的 migrationsValidated=12、originalBusinessRowsUnchanged=true，messageRejectionTableRows=0、failedOutboxTableRows=0。

复查仅追加三个正式核验结果及审计，目标版本核验历史由 27 条变为 30 条，全库 verification_record 由 30 条变为 33 条；原对象、版本和二进制检查点保持。原 M2 双机构查询仍返回首次 txId=`6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799`、区块号 13，原尝试为 VALID/code=0，transactionBytes/commitBytes 长度为 4630/905，字节摘要未变。M2 复查没有新增链交易；本轮新增仅为前述区块 27、28。报告的 ownApplicationStopped=true、emptyOwnTopologyDeleted=true。

私有产物检查 `.local/t022-artifact-audit.json` 通过：包含首轮失败与最终成功消息探针在内的 48 份 JSON/日志均为 0600，未含已知服务及演示密码、原设备令牌、JWT、PEM 私钥正文或原始签名 URL；扫描覆盖演示凭据中的全部 droneTokens 及兼容 deviceToken。原 .env、演示凭据和 Gateway 身份清单仍为 0600，两次消息探针的临时凭据文件均已删除，检查时无项目后端或业务探针进程残留。未清库、删除卷、回改历史迁移或重建凭据。

T022 已完成 Outbox 发布恢复、消费幂等与安全死信的本步验证。M1、M2、M3 保持通过；下一项 T023 完成 Worker 租约续期及恢复扫描完善，M4 和完整 MVP 尚未完成。

## T023：处理租约续期与业务扫描恢复

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-26 |
| 产物 | 上传与存证共享租约心跳、独立恢复执行器、统一扫描异常隔离及两 JVM 处理恢复探针 |
| 已验证结果 | 425 项后端测试、220 项数据库约束、双 JVM 26 次业务 HTTP/272 项检查、旧流水线回归及最终 M2 复查通过 |
| 里程碑 | T023 完成，M1、M2、M3 保持通过；下一项 T024，M4 尚未通过 |

### 1. 续租、失去所有权与恢复执行

上传定版和存证处理仍由短事务领取默认 120 秒处理租约，随后在数据库事务之外执行对象 I/O、格式检查及 Fabric RPC。Spring 注入的 ProcessingLeaseKeeper 在领取提交后立即调用一次续租，以数据库条件更新确认当前所有权；之后每 30 秒续租。心跳使用独立两个 daemon 线程，不依赖执行外部 I/O 的消息线程、恢复线程或 Spring 定时调度线程；活动注册数最多 64，每项仅保留一个定时任务，关闭时取消，应用停止时关闭执行器。

两类 renew 都沿用各自原有父任务到业务行的锁序，要求业务仍可处理、token 相同且 lease_until 大于数据库 UTC 当前时间，才延长到数据库当前时间之后 120 秒。到期或被替换的 token 不能续租、保存检查点或提交最终结果。心跳返回 false、抛出异常或执行线程被中断后停止后续处理；进程内还以续租调用开始前的单调时钟时间设置保守的 120 秒界限，不从延迟返回的提交响应重新计算一个完整租约。

处理器在外部调用前后及业务检查点、结果提交前检查心跳状态。失去租约后的正常 I/O 返回或存储异常都不能被转换为普通重试检查点，数据库写入仍需再次通过 token 和有效期条件。无法强制中断的外部调用可以继续占用原线程，返回后停止业务写入；上传候选对象保留给后续引用感知清理，不因提交结果不明而删除。终态事务提交后关闭心跳，不以之后观察到的租约清空反向否定已提交的结果。T022 的最终 ACK 持久化门槛继续有效。

定时入口默认每 10 秒提交一次业务扫描，BusinessRecoveryDispatcher 使用两个固定线程、SynchronousQueue 和每种业务一批在途的限制，不将长 I/O 阻塞在定时调度线程内，也不堆积待执行批次。两类消息消费和业务扫描继续复用相同处理器；扫描直接读取 VERIFYING 上传及可恢复存证，绕过 consumed_event.DONE。统一 DurableRecovery 为每个事件设置并还原 traceId，单项失败不阻止下一项；执行线程中断时停止继续领取本批后续事件。

续租及扫描没有新增业务 HTTP 接口，没有新增依赖或数据库迁移。Fabric 人工重新检查、完整未知态策略及孤立对象清理仍由 T024、T025 实施，完整跨系统故障矩阵继续由 T027 验收。

### 2. 构建、数据库及原有数据基线

`.local/t023-build-final.log` 的 JDK 21 clean verify 通过：425 项后端测试，失败、错误和跳过均为 0。相较 T022 新增 41 项，其中共享心跳 11 项、文件处理 7 项、存证处理 15 项、恢复执行与扫描 8 项，覆盖失去所有权、保守时间界限、续租异常、外部调用返回后的停止写入、终态提交时的心跳关闭、执行容量、单项失败隔离和中断停止扫描。

构建后仅完善独立探针并通过 `.local/t023-probe-compile.log` 的 test-compile，生产实现及上述单测未变。实际运行前，依据本机 AWS SDK ResponseInputStream 的 60 秒首次读取保护，将探针的暂停点移到首次真实读取之后，再原样返回字节，避免未开始读取就被 SDK 自动关闭；生产存储客户端时限及租约默认值保持。本轮真实恢复探针首次完整运行通过。

报告 `.local/database-probe/20261026T011302Z-031564e1/verify.json` 的 220 项真实约束全部通过，所有测试写入回滚。V1～V12 校验和保持，本轮没有 V13；业务结构及旧字段投影不变。二十二表仍沿用 `T022-full-twenty-two-table-snapshot-v1` 域，SHA-256=`68afcce1ba54ddd54c0d9d5d0866b392a779beff0dd6a40ba5b328355fd1d13b`；兼容原二十一表摘要为 `4f91e40b1a0a46596d925cb4e8319b30e32f4cc8cf156a52cea722d8c2e51aca`。

开发前只读报告 `.local/t023-preservation-before.json` 的 matchesPreviousFinalRecheck=true、migrationsValidated=12，原十六表全字段/BLOB 摘要为 `fecd7fa55c129ba28fae60968ed8fac40434710f80aaafdfd5bc8380f4be02fe`，与 T022 最终 M2 复查一致；全库原 33 条核验保持，messageRejectionTableRows=0、failedOutboxTableRows=0，原账号、凭据、对象及交易检查点继续保留。

旧自动流水线报告 `.local/pipeline-probe/20261026T011719Z-cb55c74a/report.json` 为 PASS：42 次 HTTP/404 项检查，仍通过真实自动消息链路完成定版、重复消费、mandatory return、存储故障恢复及 Garage 字节核对。本次为非 ledger 模式，不增加链登记；清理一条自有拒收行及本次业务夹具，独占消息拓扑已删除，原业务快照保持，保留 36 条安全审计。

### 3. 真实长 I/O 与双 JVM 重启恢复

选择 JDK 21，原 MySQL、Garage、RabbitMQ、Fabric 网络与普通 User1 身份均已准备好时，在项目根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-recovery.py
```

先停止其他项目后端、业务探针及 RabbitMQ 消费者。探针启动前检查全库没有他人的待处理工作，使用独立新任务和消息拓扑；两次 JVM 均由 Spring 注入完整租约心跳，没有调用兼容旧单测的非监控构造器。第一 JVM 通过正式 HTTP 创建业务，使用真实处理器保存可恢复状态后以预定 73 退出；第二 JVM 读取原数据库检查点，等待处理租约自然到期，再启动默认定时发布和业务恢复。两类消息消费者保持暂停，以证明结果来自业务扫描，不由消息重投代替扫描验收。

最终汇总 `.local/recovery-probe/20261026T011916Z-128f9416/verify.json` 为 PASS：crash.json 的 20 次业务 HTTP/151 项检查、report.json 的 6 次业务 HTTP/121 项检查，合计 26 次业务 HTTP/272 项检查，两个 JVM 的 PID 不同。检查项数包含真实服务状态及轮询断言，不作为独立测试数或性能指标。

| 场景 | 实际结果 |
| --- | --- |
| 长文件处理续租 | 已开始读取的真实 Garage 流暂停 122980 毫秒，数据库时间超过领取时的原 120 秒到期时间；同 token 完成 5 次成功续租，包括立即确认和 4 次周期心跳，未通过 SQL 改租约。竞争领取返回空，释放读取后只生成一个版本 |
| 文件续租故障 | 另一个真实读取暂停期间，在一次续租 SQL 前注入数据访问异常；释放 I/O 后原 Worker 不生成版本、不写重试计划和 DONE，原会话仍 VERIFYING、消费回执仍 PROCESSING，由原租约到期后恢复 |
| 存证续租及确认响应丢失 | 暂停真实 Fabric 查询返回，在原事务之外观察到 2 次成功续租，包括立即确认和 1 次周期心跳；竞争领取失败。实际有效提交后仅丢弃一次确认响应，提交 UNKNOWN、next_action_at 和 DONE，保留原交易及 commit 检查点 |
| 持久文件重试 | 第三个上传在真实存储读取入口发生一次临时异常，提交 VERIFYING、无处理租约、next_action_at、retry_count=1 和 DONE，再退出第一 JVM；存储故障不是文件内容错误，没有转为永久 FAILED |
| 自然到期的非终态旧 Worker | 第二 JVM 在启动应用前等待剩余 82327 毫秒，原 token 和 VERIFYING、PROCESSING 均仍保留。自动扫描第一次领取之前，用到期原 token 尝试续租、检查点及定版均失败，原会话每个字段保持，单独证明到期条件有效 |
| 默认扫描绕过 DONE | 应用启动后没有手动调用处理器，也没有启动消息消费者。默认上传扫描恢复到期工作及已有 DONE 的重试上传，存证扫描恢复 UNKNOWN/DONE；22810 毫秒内观察到三个版本全部完成定版与 CONFIRMED，该等待包含默认扫描初始延迟，不作为性能结论 |
| 原交易与唯一结果 | UNKNOWN 恢复保留完全相同 txId、transaction_bytes 和 commit_bytes，未创建第二次尝试；三个上传最终恰好三个固定版本、三个存证和三个原交易尝试，六条消费回执均为 DONE，全部自有 Outbox 为 SENT |

两类错误 token 的续租、检查点和最终写入都实际被拒绝；恢复完成后再次使用过期旧 token 也不能改变终态结果。数据访问故障仅由隔离测试装配在指定 SQL 前抛出异常，未停止共享 MySQL；对象读取、数据库事务、消息发布及两机构 Fabric 查询使用真实服务。长流暂停和单次响应丢失均为指定调用边界的测试注入，不将本次验收表述为真实设备断网或完整基础设施故障矩阵。

三条登记的 evidenceId 均为 `27bf8a56-db70-4ea2-9a90-87152eff6996`，双机构查询与已保存的 commit 请求复查均为原交易、VALID/code=0：

| 版本 | 首次交易编号 | 区块号 | 文件 SHA-256 |
| --- | --- | --- | --- |
| 1 | `d56e460c543b16449737f25bc74371eef7b48981eb67990f510e86a9e31e953c` | 29 | `6d15974dccf7bd8fb20cd3fec0101fcd89da7c32a9a7dfa623e5a50ffcf97cef` |
| 2 | `2bdef08b0fd93aacacc41054d478bd88fc7086f49fc82e35e6cd418930921a99` | 30 | `28aaf362d464779a1bcef79fb60aff0f1ccc326417d27ab7dfcfe38dc3857511` |
| 3 | `6a8297c0186842eedc227200a03f08e44b2f85cdea6850b6a9878b0d98cf348b` | 31 | `8c9dbf69519216f3ceb11291d3c077cf28abc4eee306125e54573c9616590ff2` |

本次保留 30 条安全审计及三条追加链登记，清理一个任务、三个账号、一台设备、三个上传会话、六个私有对象及关联业务夹具；独占消息拓扑与临时夹具密码文件已删除，原二十二表全字段/BLOB、.env 和演示凭据保持。未重建或清空账本；报告只保存安全编号、摘要及检查点哈希，不能用已清理的夹具 UUID 继续调用 HTTP。

### 4. 最终 JAR 兼容性与私有产物

探针后只读报告 `.local/t023-preservation-after-probes.json` 取自原 M2 新一轮复查的 preflight.json，matchesT023BeforeWork=true，原十六表全字段/BLOB 摘要仍为 `fecd7fa55c129ba28fae60968ed8fac40434710f80aaafdfd5bc8380f4be02fe`，原 33 条核验及逐表行数保持，messageRejectionTableRows=0、failedOutboxTableRows=0。该基线覆盖本轮旧流水线和双 JVM 恢复探针的清理收尾。

最终 JAR 的原 M2 同版本复查报告 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261026T012514Z-dc0fc94e/report.json` 为 PASS：15 次 HTTP/116 项检查。inspection.json 的 migrationsValidated=12、originalBusinessRowsUnchanged=true，拒收表及 FAILED Outbox 行均为 0；只追加三个正式核验结果与审计，目标版本历史由 30 条变为 33 条，全库 verification_record 由 33 条变为 36 条，追加后的原十六表摘要为 `00340f8e18237f263737fea9985d9324b9b44b5d51e10aecede3dae08b5e659f`。

原对象、固定版本和二进制交易检查点保持，原 M2 双机构查询仍返回首次 txId=`6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799`、区块号 13，原尝试 VALID/code=0，transactionBytes/commitBytes 长度仍为 4630/905、字节摘要未变。复查没有新增链交易；本轮仅前述恢复探针追加区块 29～31 的三条登记。ownApplicationStopped=true、emptyOwnTopologyDeleted=true，最终 JAR 摘要与复查实际运行的产物一致。

私有产物检查 `.local/t023-artifact-audit.json` 通过：43 份 JSON/日志均为 0600，未含已知服务与演示密码、全部原设备令牌、JWT、PEM 私钥正文或原始签名 URL，扫描包含 droneTokens 及兼容 deviceToken。原 .env、演示凭据及 Gateway 身份清单仍为 0600，探针临时凭据已删除，无项目后端或业务探针进程残留。未清库、删除卷、回改历史迁移或重建凭据。

T023 已完成本步处理租约续期及业务扫描恢复验证。M1、M2、M3 保持通过；下一项 T024 完善 Fabric 未知态、失败重试与人工重新检查。M4 和完整 MVP 尚未完成。

## T024：Fabric 未知态、失败重试与人工重新检查

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-26 |
| 产物 | 明确冲突失败预算、持续 UNKNOWN 对账、人工重新检查接口、幂等请求历史与新派发事件 |
| 已验证结果 | 458 项后端测试、235 项数据库约束、V13 迁移、真实探针 77 次 HTTP/599 项检查及最终 M2 复查通过 |
| 里程碑 | T024 完成，M1、M2、M3 保持通过；下一项 T025，M4 尚未通过 |

### 1. 失败预算、原交易与人工请求

保留原 chain_submission.retry_count 作为处理检查点及退避计数，达到 INT 上限后饱和，不让长期 UNKNOWN 对账因计数溢出停止。明确失败预算单独从持久交易尝试推导：只统计本轮人工请求 baseline_attempt_no 之后、phase=INVALID 且验证码为 MVCC_READ_CONFLICT 或 PHANTOM_READ_CONFLICT 的尝试。每轮最多十次明确冲突失败，含首次；UNKNOWN、未确认提交和暂时查询失败不消耗该预算，不因 retry_count 达到十次而转为确定失败。

第十次明确冲突后提交 FAILED、retryable=true、next_action_at=null 和 DONE，自动扫描等待有权限的人工请求。人工请求保存当时的最新 attempt_no 作为新基线，保留旧失败尝试、原退避计数和历史事件。领取可重试 FAILED 时原子改为 PENDING，使当前 Worker 仍能在第十次结果确定后完成最后检查点；新领取、续租和结果写入继续执行 T023 的状态、token 及数据库有效期校验。

已有 commitBytes 优先恢复原确认请求；只有原 transactionBytes 时恢复相同 txId 的交易，不重新背书。查询未发现业务记录不能证明原交易失败。只有已有明确 INVALID、验证码属于允许重试的两类冲突、业务键仍不存在且本轮预算有余，才准备下一次交易；事务保存新尝试前也再次核对这些条件。即使业务状态因后续查询失败变为 UNKNOWN，已知 INVALID 也不能借此绕过预算。DUPLICATE_TXID、已确认但暂未查到业务记录或检查点身份校验失败，继续保留原尝试与 UNKNOWN，不盲目创建交易。

明确权限拒绝发生在提交前的查询或背书时，FAILED 且 retryable=false；已可能提交的原交易遇到状态查询权限错误时，不能据此断言该交易无效，仍保留 UNKNOWN 与原检查点。查询得到同业务键、不同摘要，或已知不可重试无效结果，不自动重新准备，也不能由人工入口覆盖。只有真实有效回执并核对账本记录，或已查询到一致的有效业务登记时，才确认业务状态并保存首次有效交易号。

POST /api/v1/evidence-versions/{id}/anchor-retries 的正文为 clientRequestId 与不超过 1000 字的非空 reason，返回 202 及安全受理回执。按父任务、当前账号、固定版本、存证记录的顺序加锁，重新验证 OrgB 的 OPERATOR_TASK_ADMIN 角色及任务成员身份；单独 ORG_ADMIN、其他任务管理员或仅有版本共享授权均不获得该操作权限。接口不调用 Gateway，不直接创建成功状态。

同存证、同当前请求人、同 clientRequestId 的相同规范化内容复用原回执，不追加事件或审计；相同请求键改理由返回 409。权限在幂等查询前重新验证，即使原请求已经成功，撤销角色或成员后也不能利用旧请求键继续读取回执。新请求只接受可恢复、无处理租约且最新派发已提交 DONE 的业务；活动或失效但未清理的租约、上一请求尚未移交、CONFIRMED、不可重试 FAILED 和已完成任务均拒绝。原键在当前权限仍有效时可复用受理结果，不代表再次执行。

受理事务同时保存 next_action_at、递增 dispatch_no 的新 Outbox、anchor_retry_request 历史和 EVIDENCE_ANCHOR_RECHECK_REQUESTED 审计。旧 eventId、消费回执及交易检查点不改写。业务扫描选择最新 dispatch；领取在父任务锁内再次检查 latestEventId，避免扫描先选出旧事件、人工受理随后提交时，旧扫描使用过时派发继续处理。数据库采用既有 READ_COMMITTED 和 MyBatis STATEMENT 缓存范围，锁后读取使用当前已提交数据。

选择 JDK 21，沿用原服务、凭据及 User1 身份，在项目根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-anchor-retries.py
python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/20261023T074934Z-34a10f3c
```

人工调用见 [t024.http](../examples/t024.http)。实际探针和最终复查结果见本节下文；普通 Maven 构建不自动向 Fabric 提交交易。

### 2. 构建、迁移及原有数据

`.local/t024-build-initial.log` 的 JDK 21 clean verify 通过：458 项后端测试，失败、错误和跳过均为 0。相较 T023 新增 33 项，其中存证策略 15 项、人工请求及 Outbox 派发 18 项；覆盖未知态不消耗明确失败预算、预算停止及新基线、已知无效结果的重试门槛、过时派发、权限拒绝与提交不确定性的区别、当前任务权限、幂等重放、并发前置状态、事务提交及安全回执。生产实现和这些测试已冻结。

最终数据库报告 `.local/database-probe/20261026T070711Z-6da80706/verify.json` 为 PASS：235 项真实约束，较 T023 新增 15 项，写入全部回滚。V13__anchor_retry_requests.sql 只新增 anchor_retry_request，实际 Flyway 校验和为 -1202069766，V1～V12 保持不变，没有修改旧表列或旧业务行。新表约束请求 UUID、摘要、理由、派发号与基线，并通过外键限制存证、请求人及事件引用；事件所属业务、类型和派发号的完整一致性由受理事务保证，不将单列外键表述为完整作用域约束。

二十三表域为 `T024-full-twenty-three-table-snapshot-v1`，SHA-256=`9d4151017580c1b5c3999929097d91d80b4b239ae25d95b5130b3c6ddccfa464`；兼容原二十二表域仍为 `T022-full-twenty-two-table-snapshot-v1`，摘要为 `fbf3f40b93c021fa35f2b4e85534ac64a5c45aba91e7a45522317bd66331ae4c`。回滚检查还通过实际 Mapper 验证 UNKNOWN 的高退避计数不消耗失败预算、十次明确无效结果停止、新人工基线恢复预算、最新派发选择及计数饱和。这些 INVALID 结果是数据库协议负例，不是实际 Fabric 返回的失败交易。

首轮 `.local/database-probe/20261026T070021Z-ae703d09/verify.json` 也为 235 项通过；随后仅加强独立数据库 CLI 的协议验证，以外层回滚事务调用真实 AnchorTransactions：九条合成 INVALID 冲突后领取 FAILED 并原子进入 PENDING，添加第十条合成 INVALID 后仍能续租，再提交 FAILED、无下一次自动执行时间及 DONE，之后领取和扫描均停止；新的人工基线允许下一轮恢复。最终报告的 tenthDefiniteConflictCheckpointVerified=true，原二十三表及兼容摘要与首轮相同。`.local/t024-probe-compile.log` 的 test-compile 通过，包含新业务探针和该 CLI 检查；生产与上述 458 项单测未变，没有将这次编译计为重复单测或合成真实链失败回执。

开发前 `.local/t024-preservation-before.json` 的 migrationsValidated=12、matchesPreviousFinalRecheck=true，原十六表全字段/BLOB 摘要为 `00340f8e18237f263737fea9985d9324b9b44b5d51e10aecede3dae08b5e659f`，原核验记录 36 条。迁移后 `.local/t024-preservation-after-migration.json` 的 migrationsValidated=13、matchesT024BeforeMigration=true、anchorRetryRequestTableRows=0，原十六表摘要保持，原 .env、演示凭据及 Fabric 检查点未变。

### 3. 真实人工重新检查与失败边界

报告 `.local/anchor-retry-probe/20261026T070756Z-f089a6a4/report.json` 与 verify.json 为 PASS：77 次真实 HTTP/599 项检查。通过正式接口创建三个独立任务、七个隔离账号和四个真实文件版本；使用原 MySQL、Garage、RabbitMQ、Fabric 及 User1 身份。消息自动发布保持启用，探针暂停自己的消息消费者和两类定时恢复，显式调用生产处理器及 recoverDue，以准确验证人工请求前后的状态，不将它表述为本轮再次完成 T023 的默认定时恢复验收。

| 场景 | 实际结果 |
| --- | --- |
| 已有 commitBytes 的 UNKNOWN | 第一个版本取得真实有效提交回执后，测试装配丢弃一次查询响应；业务保存 UNKNOWN、原交易与 commit 检查点及 DONE。人工受理后恢复原 commit，首次 txId、transactionBytes、commitBytes 摘要均保持，只保留一个交易尝试 |
| 活动处理责任 | 先通过真实领取获得租约，新人工请求为 409；经原处理器事务提交 UNKNOWN 等待计划并释放租约后才允许受理 |
| 同键并发与重放 | 两个相同 clientRequestId/内容的并发请求均为 202、回执完全相同，只有一条请求和 dispatch=1；同键改理由为 409，上一新派发尚未移交时另一个请求也为 409；确认后原键仍复用回执，新键不能改写确认结果 |
| 当前任务权限 | 匿名、设备凭据、无效令牌、OrgA、上传者、其他任务管理员均被拒绝；正式移除成员、正式移除业务角色及令牌失效后重新检查均有效，禁用隔离账号后为 401 |
| 精确共享与任务完成 | 通过正式复核及共享审批让另一个管理员只能读取该版，但该授权不能用于人工重新检查；正式完成的任务拒绝新的人工请求 |
| 缺少 commitBytes 的 UNKNOWN | 第二个版本先保存真实背书所得原交易，在实际 submit 调用到达网络前注入一次不可用异常，形成 UNKNOWN 与 DONE；两个不同请求键并发只有一个 202、另一个 409。恢复时真实查询仍不存在该业务登记，但始终提交原 txId 与原交易字节，没有再次背书，最终真实确认并补齐 commitBytes |
| 明确权限拒绝 | 在测试装配的背书调用前注入一次权限拒绝，业务为 FAILED、retryable=false、无自动计划和交易尝试；扫描不再准备，人工入口也不能覆盖该结果 |
| 实际摘要冲突 | 用真实 User1 为专属负例业务键登记全零摘要并取得 VALID 回执，两机构实际查询一致；业务原文件具有不同摘要，正常处理器将该版置为永久 EVIDENCE_HASH_CONFLICT，不准备新尝试，人工请求不能覆盖已有登记 |

四个固定版本最终为两个 CONFIRMED、两个永久 FAILED。真实链新增三条登记：前两条与业务文件一致，第三条是有意产生的摘要冲突负例，不能计作第三个业务存证成功。

| 类型 | evidenceId | 版本 | 首次交易编号 | 区块号 |
| --- | --- | --- | --- | --- |
| 原 commit 恢复并确认 | `cb17fff9-8a65-490a-8361-bca665f26895` | 1 | `dd60791cbd7ae58608e91abe9d818dcd2f6fc3e685f03b28e2448c5bbde55e09` | 32 |
| 原 transaction 恢复并确认 | `5ba1f2b7-8a0d-4fb7-8f86-568bf49090b4` | 1 | `66ddcb9b118426a12672764e3a89bb48997cdd98bb1024e34837d4d8609d26cf` | 33 |
| 实际冲突登记负例 | `5ba1f2b7-8a0d-4fb7-8f86-568bf49090b4` | 3 | `5ea9901bf6892de7b36d5261fa7b47594e50d4e7b58fe437bdeac8f6b6496516` | 34 |

三条交易均为 VALID/code=0；前两条原文件 SHA-256 分别为 `79011355c923858dfefeff593844088c49696cd535a96c0e44a2edc91bff6fde` 和 `531e06eb2204bc3cf2114f72f35d02f4bd7754198951a0e1a9d7fb0f2e59569a`，已保存的 commit 请求再次查询与双机构查询均核对一致。第三条实际登记摘要为 64 个零，其业务文件摘要为 `0f6ccce328ee70ce0c700ed0fa0142f9c00c46c8d145833fce442c7fdc7606f4`；该差异是负例输入，业务未被强制确认。

提交前不可用与背书权限拒绝均为指定调用边界的测试注入，未停止共享网络或修改链 ACL；真实响应丢失用例只丢弃已经取得的有效 commit 查询结果，不把提交前注入表述为网络已经提交后丢失响应。十次明确冲突预算由前述回滚数据库协议检查验证，不伪造为十条真实 Fabric 冲突回执。本轮实际探针首轮即通过，没有失败运行需要另行统计。

两次人工受理只产生两条新请求和两条对应审计，本轮保留 55 条安全审计及三条追加链登记。清理三个任务、七个账号、一台设备、四个上传会话、八个私有对象和关联业务夹具，请求历史在其外键父项之前删除；独占 RabbitMQ 拓扑已删除，原二十三表全字段/BLOB、.env 和演示凭据保持。账本及安全审计继续追加保留，报告只保存安全编号、摘要和检查点哈希，夹具 UUID 不能用于继续调用 HTTP。

### 4. 最终 JAR 兼容性与私有产物

探针后只读报告 `.local/t024-preservation-after-probes.json` 取自原 M2 新一轮复查的 preflight.json，matchesT024BeforeWork=true，原十六表全字段/BLOB 摘要仍为 `00340f8e18237f263737fea9985d9324b9b44b5d51e10aecede3dae08b5e659f`；原 36 条核验、逐表行数及原凭据保持，anchorRetryRequestTableRows=0。该基线覆盖两次数据库回滚检查和真实人工重新检查探针的清理收尾。

最终 JAR 的原 M2 同版本复查报告 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261026T070913Z-b3471000/report.json` 为 PASS：15 次 HTTP/115 项检查。inspection.json 的 migrationsValidated=13、originalBusinessRowsUnchanged=true，人工请求表、拒收表及 FAILED Outbox 行均为 0。复查只追加三个正式核验结果与审计，目标版本历史由 33 条变为 36 条，全库 verification_record 由 36 条变为 39 条，追加后的原十六表摘要为 `45618eae110fe44e0b7c4d20415338324fa6ca30a629378eb0a34d15c469dd4c`。

原对象、固定版本和二进制交易检查点保持，原 M2 双机构查询仍返回首次 txId=`6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799`、区块号 13，原尝试 VALID/code=0，transactionBytes/commitBytes 长度仍为 4630/905、字节摘要未变。复查没有新增版本、交易或链登记；本轮仅前述人工重新检查探针追加区块 32～34 的三条登记。ownApplicationStopped=true、emptyOwnTopologyDeleted=true，最终 JAR 摘要与复查实际运行的产物一致。

私有产物检查 `.local/t024-artifact-audit.json` 通过：包含两次成功数据库检查在内的 45 份 JSON/日志均为 0600，未含已知服务及演示密码、全部原设备令牌、JWT、PEM 私钥正文或原始签名 URL，扫描包含 droneTokens 及兼容 deviceToken。原 .env、演示凭据和 Gateway 身份清单仍为 0600；本轮夹具密码与令牌仅在进程内存使用，没有生成临时夹具凭据文件，检查时无项目后端或业务探针进程残留。未清库、删除卷、回改历史迁移或重建凭据。

T024 已完成本步未知态对账、明确失败的受控重试及人工重新检查验证。M1、M2、M3 保持通过；下一项 T025 完成临时文件、分片及孤立对象的引用感知清理。M4 和完整 MVP 尚未完成，T026 并发回归及 T027 完整故障矩阵继续按任务清单验收。

## T025：临时文件、分片及孤立对象清理

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-27（Asia/Shanghai；部分私有目录按 UTC 命名为 20261026） |
| 产物 | 显式 cleanup profile、持久清理任务及租约、引用感知对象回收、跨 JVM 临时文件保护与崩溃残留回收 |
| 已验证结果 | 508 项后端测试、248 项数据库约束、V14 迁移、真实清理探针 30 次 HTTP/303 项检查及最终 M2 复查通过 |
| 最终兼容性 | 原 M2 同版本复查 15 次 HTTP/111 项检查通过，原对象与交易保持；62 份私有 JSON/日志检查通过 |
| 里程碑 | T025 完成，M1、M2、M3 保持通过；下一项 T026，M4 和完整 MVP 尚未完成 |

### 1. 清理范围及文件持有边界

清理通过 `python3 deploy/scripts/run-backend.py --cleanup` 显式加入 `cleanup` profile，要求 database 与 storage，不要求 ledger；普通 local/prod 启动不自动开启。默认宽限 3600 秒、每批 100 条、触发间隔 60000 毫秒，批量上限为 100。独立无排队后台线程执行清理，周期不重叠；对象 LIST、HEAD、DELETE 均不放进数据库事务，启动及定时触发线程不执行这些 I/O。

对象只接受服务端生成的两类规范键。发现、领取及 HEAD 后重新校验引用、上传状态、对象年龄和当前租约；正式版本、已登记分片、仍能写入的 UPLOADING 会话及有效定版 token 对应的候选对象受到保护。UPLOADING 超过数据库期限才转为 EXPIRED，过宽限的 EXPIRED/FAILED 会话且无正式版本、无有效处理租约时才退役其分片引用。COMPLETED 的源分片与正式文件继续保留，VERIFYING 的源分片继续供恢复读取。

清理任务按父任务、上传会话、清理任务的顺序取锁，120 秒租约及 token 限制结果写入；完成、重试和续租不能由过期或被替换的 Worker 提交。删除失败持久化 2、10、30、60、300 秒退避，对象已不存在可幂等完成；原 DONE/SKIPPED 任务对应的同键对象再次出现且符合清理条件时，可重新激活原任务。数据库提交故障不会被分类为 S3 删除故障。内部对象键不进入 HTTP、消息和任务审计响应，清理未新增对外强制删除接口。

上传、定版和 COPY 核验共同通过 TemporaryFileManager 创建并持有临时文件。独立 sidecar 文件锁覆盖实际 I/O 和数据库提交边界；已关闭或超时的请求不能让仍在执行的后台读取提前失去文件保护。清扫在私有目录内只识别三类生成文件名，检查符号链接、文件身份和跨 JVM 锁；保留未知文件及仍有持有者的文件。每目录保留有界批次游标，可越过初始的年轻文件或活动文件，最多缓存 16 个目录游标；目录替换、淘汰和应用关闭时释放目录句柄。

### 2. 构建、迁移及原有数据

`.local/t025-build-final.log` 的 JDK 21 clean verify 为 PASS：508 项后端测试，失败、错误和跳过均为 0。较 T024 新增 50 项：清理事务、处理器、对象清单适配器及调度边界 26 项，临时文件管理和使用方集成 24 项。其中四项回归覆盖有界批次越过初始年轻文件、目录被替换时重置游标、管理器关闭释放目录句柄但不破坏活动持有者，以及 16 个目录游标缓存上限。最低空闲空间检查使用不会发生 long 加法溢出的比较方式，文件定版和副本核验均验证极大保留空间配置被拒绝，未创建临时文件或开始对象 I/O。

最终真实数据库报告 `.local/database-probe/20261026T163104Z-90ef2718/verify.json` 为 PASS：248 项约束，较 T024 新增 13 项，allProbeWritesRolledBack=true。V14__storage_cleanup_tasks.sql 仅新增 storage_cleanup_task，实际 Flyway checksum 为 `-1562459282`；V1～V13 校验和及既有表列保持。新表约束对象摘要身份唯一、上传 UUID、对象键所属前缀、非负大小/次数、合法状态、租约与下次计划配对、安全错误码；完整规范键及清理资格由业务事务再次检查。upload_id 不设外键，以允许记录上传行已不存在的服务端孤立对象。

数据库协议检查还实际调用 CleanupTransactions 与 Mapper，验证缺失上传行的孤立任务、错误及过期 token、租约接管、退避时间、完成后同键重新出现、非规范键和新 HEAD 元数据仍在宽限内时的拒绝结果。storageCleanupLeaseRoundTrip 各项通过，全部在外层事务回滚；该检查没有删除 S3 对象，不能替代下文的真实回收验收。

二十四表域为 `T025-full-twenty-four-table-snapshot-v1`，SHA-256=`49789df21e014fb4a9ba6b958fc35d006aaef49dcfa7647c7b788f451346abc1`；兼容二十三表域仍为 `T024-full-twenty-three-table-snapshot-v1`，SHA-256=`67da968ca26500b6b4fe1af6b59b9f1f0571c384c1fba7885bd242a985b8bc17`。这些摘要对应数据库 CLI 的序列化域，不与下文 Inspector 或清理探针的不同摘要域直接比较。

开发前 `.local/t025-preservation-before.json` 的 migrationsValidated=13、matchesPreviousFinalRecheck=true，原十六表全字段/BLOB 摘要为 `45618eae110fe44e0b7c4d20415338324fa6ca30a629378eb0a34d15c469dd4c`，全库核验记录 39 条。迁移后 `.local/t025-preservation-after-migration.json` 的 migrationsValidated=14、matchesT025BeforeMigration=true、storageCleanupTaskTableRows=0，原十六表摘要及逐表行数保持，原 .env 和演示凭据保持。

选择 JDK 21，沿用既有 MySQL、Garage、RabbitMQ 及凭据，先停止其他项目后端与消息消费者，再执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-cleanup.py
python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/20261023T074934Z-34a10f3c
```

观察现有业务接口见 [t025.http](../examples/t025.http)。普通 Maven 构建不提交链交易，也不自动运行真实对象删除探针。

### 3. 真实对象生命周期与跨 JVM 临时文件

最终 `.local/cleanup-probe/20261027T003017Z-5c786fa1/report.json` 和 verify.json 为 PASS：30 次真实 HTTP、303 项检查、四个实际固定版本。检查项含轮询及服务状态断言，不作为独立测试数或性能指标。探针使用独占账号、任务、设备、上传和 RabbitMQ 拓扑；真实 S3 清单按本轮上传 UUID 过滤，生产清理事务、S3 HEAD/DELETE 及定版处理器仍实际执行。自动清理和恢复扫描、消息消费者在本轮暂停，由探针显式驱动处理器，验证受控并发边界，不将该结果表述为又一次默认定时恢复验收。

| 场景 | 实际结果 |
| --- | --- |
| 已提交分片但返回丢失 | 真实分片及引用事务已提交后，测试装配丢弃一次方法返回，HTTP 为 503；已登记对象被保留，同片重试为 200，实际定版生成完整版本 |
| 提交前孤立对象 | 真实 S3 写入后、分片引用提交前注入一次异常，HTTP 为 503 且无 upload_part 行；会话仍 UPLOADING 时不删除，数据库期限及宽限自然经过后转 EXPIRED 并回收 |
| 正式引用和处理租约 | 已完成上传的源分片、正式版本、VERIFYING 的源分片及当前有效 token 候选对象均保留；原处理器提交恢复计划并释放责任后，仅无引用旧候选被删除，源分片继续用于恢复定版 |
| 删除持久重试 | 首次 DELETE 前注入一次存储异常，任务为 PENDING、attempt_count=1、租约为空，next_action_at 恰为 updated_at 加 2 秒，对象仍实际存在；自然到期后实际 DELETE 成功，任务为 DONE 且尝试次数至少 2 |
| 同键对象再次出现 | 在实际删除完成后重新写入本轮相同孤立键，等待 LIST 和 HEAD 元数据均过宽限，沿用同一清理任务再次删除 |
| 正常分片自然退役 | 另一条带已登记分片的上传超过 20 秒会话期及 2 秒宽限，EXPIRED 后分片行被退役、实际 S3 对象消失，正式上传进度显示空分片和 uploadedSize=0 |
| 陈旧清理意图与并发定版 | 真实定版在候选对象写入后暂停；SQL 仅插入一条自有候选的陈旧清理任务负例。清理在真实领取事务前等待，先让生产定版事务提交正式引用，随后真实领取重查引用并将该意图置为 SKIPPED，实际正式对象保持 |
| 跨 JVM 活动临时文件 | 一个 JVM 持有真实 sidecar 锁并把文件时间设为超龄，另一个 JVM 清扫仍保留该文件；不依赖进程内活动集合完成判断 |
| 崩溃残留 | 子 JVM 以预定 halt(73) 退出并留下超龄文件，另一 JVM 清扫实际删除残留；符号链接目标及不识别的文件名继续保留 |
| 审计所属任务 | 正式 HTTP 按 CLEANUP_TASK 查询，返回集合与清理任务经上传关联到本任务的数据库结果一致；缺失上传行的孤立任务不混入任务历史，审计不含对象键、JWT、存储凭据或签名 URL |

本轮仅在探针进程内使用 2 秒宽限、20 秒上传期限，生产默认 3600 秒不变。上传失败和删除失败是指定调用边界的一次测试注入，未停止共享 MySQL 或 Garage；正常版本由真实处理器校验实际字节并提交，不通过 SQL 伪造正向成功状态。Fabric 未启用，realLedgerRegistrations=0，没有新增链登记。

本轮成功探针核对并保留 37 条任务范围安全审计，缺失上传行的孤立任务审计也继续追加；删除一个任务、三个账号、一台设备、六个上传会话、五条清理任务及关联链外夹具。收尾实际删除剩余十个私有对象，此数不包含场景验证中已被生产清理器删除的对象。自有空 RabbitMQ 拓扑已删除，原二十四表逐字段/BLOB 快照与逐表行数保持，探针域 SHA-256=`2e1de25152b35eebf9de11dc7cf5afb73efc24492a484ddeefcb829bbad39b37`。原两条上传均仍 COMPLETED，原 39 条核验保持；安全审计继续追加，不能用已清理夹具 UUID 继续调用 HTTP。

通过前四轮失败报告继续保留，不计为成功验收：

| 私有运行目录 | 失败位置与修正 |
| --- | --- |
| `.local/cleanup-probe/20261027T001929Z-41784a22/` | 16 次 HTTP/116 项检查；探针对 AtomicReference 中的字符串使用引用比较，故障未触发，预期 503 实际为 200。修正测试注入的 CAS 对象身份 |
| `.local/cleanup-probe/20261027T002159Z-9d27cf36/` | 25 次 HTTP/190 项检查；预期删除退避任务未出现，探针等待对象年龄不足以保证真实 LIST 元数据已过宽限 |
| `.local/cleanup-probe/20261027T002803Z-2f044ac4/` | 25 次 HTTP/195 项检查；首次删除重试已通过，但同键重新出现时的年龄等待仍不充分 |
| `.local/cleanup-probe/20261027T002905Z-b45e33da/` | 25 次 HTTP/190 项检查；仅改为数据库时钟仍不足，随后确认 HEAD 为整秒精度、LIST 保留毫秒，最终改为同时等待两者均符合数据库宽限 |

最终报告记录一次 HEAD 与 LIST 的精度差为 515 毫秒。探针同时将退避断言改为相对 updated_at 检查持久计划，避免其他真实 I/O 自然耗时影响“尚未到期”的瞬时断言；生产清理的宽限及引用检查未被放宽。四轮失败均清理本轮夹具及自有消息拓扑，originalBusinessDataUnchanged=true，原二十四表保持，未启用 Fabric；失败 JSON 和私有日志没有被覆盖为 PASS。

### 4. 最终 JAR 兼容性与私有产物

探针后只读报告 `.local/t025-preservation-after-probes.json` 取自原 M2 新一轮复查的 preflight.json，matchesT025BeforeWork=true，原十六表全字段/BLOB 摘要仍为 `45618eae110fe44e0b7c4d20415338324fa6ca30a629378eb0a34d15c469dd4c`；原 39 条核验及逐表行数保持，storageCleanupTaskTableRows=0，人工请求、拒收及 FAILED Outbox 行数也为 0。该基线覆盖数据库协议回滚检查、四轮失败及最终成功清理探针的收尾。

最终 JAR 的原 M2 同版本复查报告 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261027T003108Z-cd42690c/report.json` 为 PASS：15 次 HTTP/111 项检查。inspection.json 的 migrationsValidated=14、originalBusinessRowsUnchanged=true，无待处理工作，清理任务、人工请求、拒收及 FAILED Outbox 表行数均为 0；只追加三个正式核验结果及审计，目标版本历史由 36 条变为 39 条，全库 verification_record 由 39 条变为 42 条。追加后的原十六表摘要为 `93b91316e486862aff0897b9e9a410009b0088d1d9071ea00aa247ada791995e`。

原分片与正式对象均仍为 79 字节、SHA-256=`ec81ce39789f63a8f58cd7c6ecaef171b4b944d665ea92d79b2c92175ea2726f`，匿名 GET/HEAD 均为 403。原 M2 两机构查询仍返回首次 txId=`6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799`、版本 1、相同登记摘要；原交易仍为区块 13、VALID/code=0，transactionBytes/commitBytes 长度仍为 4630/905，两份二进制检查点摘要保持。核验结果为原文件 MATCH、相同副本 MATCH、变更副本 MISMATCH；复查没有新增任务、上传、版本、交易或链登记，T025 全轮也没有新增 Fabric 登记。

复查 ownApplicationStopped=true、emptyOwnTopologyDeleted=true，当前最终 JAR 与实际运行产物的 SHA-256 均为 `8ec2057c56e1d847a877f7b40e44c7479855aacce095990dea602010264440db`。本次复查按既有方式关闭自己的业务恢复及派发，不自动启用 cleanup；独立清理探针已在上文证明实际清理边界，原 M2 复查用于确认正式接口、原私有对象和原交易仍兼容。

私有产物检查 `.local/t025-artifact-audit.json` 为 PASS：62 份 JSON/日志均为 0600，包含四轮失败清理报告、最终成功清理及跨 JVM 子进程、数据库和最终 M2 复查产物。已知服务及演示密码、全部原设备令牌、JWT、PEM 私钥正文或原始签名 URL 均未出现；设备令牌检查覆盖 droneTokens 及兼容 deviceToken。原 .env、演示凭据及 Gateway 身份清单仍为 0600，夹具密码和令牌仅在进程内存使用，无项目后端或业务探针进程残留。未清库、删除卷、回改历史迁移或重建凭据。

T025 已完成本步临时文件、过期分片及孤立对象的引用感知清理验证。M1、M2、M3 保持通过；下一项 T026 完成权限、状态与跨模块并发回归，T027 继续验证完整跨系统故障矩阵。M4 和完整 MVP 尚未完成。

## T026：权限、状态与跨模块并发回归

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-27（Asia/Shanghai） |
| 产物 | 跨任务成员更新锁顺序修复、成员锁专项 CLI、权限及跨模块 HTTP 并发回归探针、操作示例 |
| 已验证结果 | 513 项后端测试、248 项数据库约束；成员锁专项 8 轮/16 次更新；综合回归 183 次 HTTP/1322 项检查；原 M2 复查 15 次 HTTP/116 项检查全部通过 |
| 数据与链 | V1～V14 不变；独占夹具清理后原二十四表保持，四条真实 Fabric 登记与追加审计保留 |
| 里程碑 | T026 完成，下一项 T027；M1、M2、M3 保持通过，M4 和完整 MVP 尚未完成 |

### 1. 已复现的成员更新死锁及修复

修复前的隔离 SQL 协议报告 `.local/t026-member-lock/report-before.json` 实际复现两个管理员维护不同任务时的锁环：两事务先各持有自己的父任务及操作者账号锁，再执行原 DELETE m JOIN app_user 成员删除语句，其中一个事务得到 SQLState=40001、MySQL code=1213，另一个执行完成。该复现使用与旧业务相同的锁/SQL 次序，不是正式 HTTP 调用；两事务最终均回滚，删除两个临时任务、三个账号后原二十四表完整摘要保持，不能把该负例计作成功成员更新。

TaskService.replaceMembers 现在先锁父任务，非锁定预读当前身份并初验任务权限；按服务端身份确定本机构当前成员、请求候选和操作者的账号集合。仅接受本机构候选，去重后按账号编号升序逐行加锁，再执行最终 currentActor 重验，校验当前 tokenVersion、角色、任务成员关系、任务锁号以及候选的启用状态/角色。成员删除改为精确编号的单表 DELETE，不再隐式通过多表 JOIN 锁其他账号；插入也按编号排序。创建者及对方机构成员保留，业务修改和 TASK_MEMBERS_UPDATED 审计仍在一个事务提交。未锁全机构账号，没有新增接口、表或迁移。

`.local/t026-build-initial.log` 的 JDK 21 clean verify 通过：513 项后端测试，失败、错误及跳过均为 0。较 T025 新增五项事务回归，覆盖有序相关账号锁及创建者/对方机构保留、预读后 token 失效、预读后管理角色撤销、候选在锁等待期间停用，以及外机构候选不进入账号锁集合。COMPLETED 后成员撤销仍被允许，原完成时间保持。生产代码在该构建后没有再修改。

### 2. 真实跨任务账号锁专项

`python3 deploy/scripts/verify-member-locks.py` 的成功报告为 `.local/member-lock-probe/20261027T062402Z-fa6849e5/report.json` 和 verify.json。通过真实 TaskService 创建并分配两个任务，两个同机构管理员同时维护不同任务。测试装配只在各自独立父任务锁取得后同步开跑，不持有账号锁等待另一个任务提交；实际 Mapper、事务代理和 MySQL 执行修复后的排序账号锁及成员写入。

八轮共十六次更新全部提交，deadlocks=0，两个 Worker 每轮的账号锁顺序相同；每次成功后任务锁号与服务返回一致，成员始终保留创建者及两位运营商管理员，每个任务每轮只有一条成功成员审计。核对并保留 22 条任务相关审计，清理两个任务及三个账号后，原二十四表域摘要仍为 `9ecce1aacee8b9e55a9f8eb14e245caf1e1d49cb1842afbeda8d9e4a05febd12`，与修复前负例相同。

该专项不启动 HTTP、S3、消息或 Fabric Worker，没有新链登记；十六次服务调用不计入下文 HTTP 数量。这是受控锁竞争验证，不代表任意负载下不存在所有类型的数据库冲突。

### 3. 真实权限和跨模块 HTTP 并发

`python3 deploy/scripts/verify-regression.py` 首轮通过，报告为 `.local/regression-probe/20261027T062433Z-eb2e962d/report.json` 和 verify.json：183 次真实 HTTP、1322 项检查。检查项含响应契约、状态轮询和数据库断言，不能当作独立测试数或性能指标。

探针使用独占任务、账号、设备和消息拓扑。关闭本轮 publisher、业务恢复及独立 ledger 恢复扫描，暂停自己的消费者，显式以原 Outbox 事件驱动真实定版及存证处理器；末尾实际发布确认并按本轮 eventId 排空自己的消息。I/O 门闩在真实存储读取处暂停并断言没有数据库事务，顺序由门闩控制，不靠睡眠猜测。正常版本、复核、授权及完成状态由正式接口或生产处理器形成，不通过 SQL 伪造成功状态。

| 场景 | 实际结果 |
| --- | --- |
| 跨机构及同机构非成员 ID 直查 | 任务、进度、执行历史、审计、上传、版本、存证、异常、复核及核验历史拒绝越权；非法查看/下载签发/核验请求不产生复核记录 |
| SQL 权限分页 | pageSize=1 检查多个任务和版本页面，total 和返回编号只包含当前成员或精确版本授权范围；无可见数据时列表为空，没有遗漏授权编号或混入其他版本 |
| 同任务并发成员更新 | 两个真实 HTTP 请求携带相同 expectedLockVersion，只有一个 200、一个 409；创建者及另一机构成员保留。同一旧 JWT 在移除成员后立即失去任务及版本读取，恢复成员后重新可读 |
| 核验与成员撤销交错 | 实际 STORED 读取暂停后由正式接口撤销成员；放行后最终事务返回 403，不落核验结果，原 JWT 不能绕过撤销 |
| 同版本并发复核 | 两请求竞争同一 PENDING，只有一个 200、一个 409；数据库只有一条正式复核结论和一条 EVIDENCE_REVIEWED 审计 |
| 同申请并发共享审批 | 同一授权锁号两个批准请求只有一个 200、一个 409；数据库只有一个批准状态和一条批准审计 |
| 成员与精确授权组合 | 收件人先取得旧版授权，再临时加入和移除任务成员；撤销成员后仍可沿旧版精确授权读旧版，不能读取任务、执行历史或审计 |
| 新旧版本资格及授权隔离 | 新版本独立为 PENDING，旧版通过不能满足新版复核或共享批准；新版独立批准后，撤销旧版授权不影响新版。分页只返回仍有有效授权的版本 |
| 换机与定版交错 | 当前上传在真实 S3 读取中保持 VERIFYING 并阻挡完成；正式换机原子关闭旧执行并建立唯一 RUNNING。旧 Worker 放行后定版归属原执行，历史成果不能满足新当前批次 |
| 当前成果提交与完成 | 新当前版本实际复核且链确认后，进度仅选该执行最新版本；并发提交及并发完成各为一个 200、一个 409，完成审计只有一条 |
| 完成与历史定版交错 | 历史执行上传在实际 I/O 中时完成任务；放行后 Worker 重新发现任务终态，上传为 FAILED/TASK_NOT_ACTIVE，没有晚到正式版本或新存证 |
| 终态限制及保留例外 | 新上传、分片继续写入、未完成上传定版、换机、要求修改、子任务新增、启动、复核和异常写入均拒绝；已完成上传重复完成返回原 202 且不新增版本。成员撤销、新精确版本共享批准与旧授权撤销仍可执行，completedAt 保持 |

四个实际固定版本均保留有效原交易、非空交易及确认检查点。再次查询原 commit 为 VALID/code=0，两机构查询相同的 evidenceId、version、fileHash、submitterMsp、registeredTxId，正式对象字节与记录大小及 SHA-256 一致。

| 版本 | 固定版本编号 | 首次有效交易编号 | 区块号 |
| --- | --- | --- | --- |
| 1 | `fe0674b5-7ad5-4612-a963-0c82b1aab0bc` | `9ce8eee727591eba01517ca0806171a80bca1f0f4f3eb01cae084c963016ec70` | 35 |
| 2 | `2e90ac29-d928-4db8-acac-23c65112bf21` | `03e70785adabb5e7e2c455d9e78f625cfa2d845f8e002660ea120fbce2e5629e` | 36 |
| 3 | `fb95afe2-aaa8-4be0-ac69-f8d0bd940505` | `b9fa40eeb570a0f39c5c89ad4a5f10a30b71b7960db8c30c071bef5608d7b840` | 37 |
| 4 | `a027df0c-7c66-4a5c-b056-ae0a0a512878` | `b37f618d5f38496cfe15d576ea31dac0fafea5e5d02b103ca319e0717bd6848b` | 38 |

四版均属于 evidenceId=`113d665b-e407-4c5d-a727-0d04b3007c41`。前三版属于原执行（第三版在换机后完成历史定版），第四版属于新当前执行。晚到终态上传未生成第五个版本或登记。真实登记及安全审计追加保留，报告没有保存对象键、JWT、密码或原始二进制检查点。

成功探针核对并保留 66 条本轮 HTTP trace 关联的安全审计；这不是全部内部 Worker 审计的总数。所有受控 Worker 已结束后，清理两个任务、八个账号、两台设备、六个上传会话、十一个私有对象和关联链外夹具，自有空消息拓扑已删除。原二十四表逐字段/BLOB 和行数保持，探针域 SHA-256=`a4c7409b1a95bf5768ef6592f62071dd9518da99b92d3542332ec927a4a78860`，全库核验仍为 42 条。

本轮 HTTP 综合探针及修复后成员专项均首轮通过，没有失败 HTTP 运行。修复前的真实死锁负例报告与私有日志另行保留，未覆盖为 PASS。两种探针的业务摘要序列化域不同，只在各自运行前后比较，不能把摘要差异当作业务数据变化。

### 4. 数据库、原 M2 与私有产物

`.local/database-probe/20261027T062518Z-f5068e7d/verify.json` 的 248 项真实约束、UTC 往返和回滚协议通过，allProbeWritesRolledBack=true、sameBusinessSnapshot=true、sameFullMigrationChecksums=true。V1～V14 校验和保持；二十四表域仍为 `T025-full-twenty-four-table-snapshot-v1`，摘要为 `d45827d85026d38aed20329606d55054c65c30d717bc416e4d431ce1ea8805d6`，兼容二十三表摘要为 `ebc85fe6d3b26f489faf463b7d1b24bde4959f48eb9f4c68f41a089f33b021f2`。没有新增迁移或把受控并发测试伪称为数据库约束新增。

开发前 `.local/t026-preservation-before.json` 的 migrationsValidated=14、matchesPreviousFinalRecheck=true，原十六表摘要为 `93b91316e486862aff0897b9e9a410009b0088d1d9071ea00aa247ada791995e`，核验记录 42 条。探针后 `.local/t026-preservation-after-probes.json` 的 matchesT026BeforeWork=true，原十六表完整字段/BLOB、逐表行数及凭据摘要保持；清理任务、人工重检、拒收及 FAILED Outbox 行数为 0。

最终 JAR 的原 M2 同版本复查 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261027T062612Z-18adf733/report.json` 通过：15 次 HTTP/116 项检查。inspection.json 的十四版迁移及 originalBusinessRowsUnchanged=true；只追加三次正式核验及审计，目标版本核验历史 39→42，全库 verification_record 42→45，追加后的原十六表摘要为 `f57a18391478a1e92d6d18ba58234467309539c176514d88ac09cb6db4ce280b`。

原 M2 文件仍为 79 字节、SHA-256=`ec81ce39789f63a8f58cd7c6ecaef171b4b944d665ea92d79b2c92175ea2726f`，两机构仍返回首次 txId=`6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799`，原交易仍为区块 13、VALID/code=0，transactionBytes/commitBytes 长度及摘要保持。此次兼容复查没有新任务、版本或链登记；本轮新链登记仅为上文区块 35～38 的四条。ownApplicationStopped=true、emptyOwnTopologyDeleted=true，实际运行与当前最终 JAR 摘要均为 `16804276ddcc9d3b010e220ac081bf3d955c2c896f949aade6a08070ca580011`。

私有产物检查 `.local/t026-artifact-audit.json` 通过：41 份 JSON/日志均为 0600，包含修复前死锁负例、修复后成员专项、综合 HTTP、数据库及最终 M2 复查。未发现已知服务/演示密码、全部原设备令牌、JWT、PEM 私钥正文或原始签名 URL；设备令牌扫描包含 droneTokens 及兼容 deviceToken。原 .env、演示凭据和 Gateway 身份清单保持 0600，无项目后端或业务探针进程残留。夹具凭据不写入报告或单独凭据文件，未清库、删除卷、修改旧迁移或重建凭据。

使用 JDK 21 和既有服务，可按以下顺序复验；真实探针运行前停止其他项目后端和消息消费者：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-member-locks.py
python3 deploy/scripts/verify-regression.py
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/20261023T074934Z-34a10f3c
```

观察方式见 [t026.http](../examples/t026.http)。T026 已完成，下一项 T027 执行跨系统故障矩阵。受控并发通过不代表完整故障恢复、性能验收或完整 MVP 已完成。

## T027：跨系统故障注入与恢复验收

| 项目 | 内容 |
| --- | --- |
| 验证日期 | 2026-10-27（Asia/Shanghai） |
| 产物 | 故障矩阵总入口、发送/候选文件崩溃组件、Fabric RPC 崩溃组件、跨 JVM 安全检查点与分阶段报告 |
| 已验证结果 | 513 项后端测试、248 项真实数据库约束；六次预定真实 halt 后自动恢复，六个版本真实存证确认；原 M2 15 次 HTTP/110 项复查通过 |
| 总报告 | `.local/fault-matrix/20261027T072736Z-fcefada0/report.json`，PASS |
| 里程碑 | T027 完成，T022～T027 全部通过，M4 可靠性完善通过；下一项 T028，M5 和完整 MVP 尚未完成 |

### 1. 范围、构建与总入口

本轮新增真实崩溃验收工具，没有修改生产恢复逻辑、既有迁移、120 秒租约或默认扫描频率。`.local/t027-build-initial.log` 的 JDK 21 clean verify 通过：513 项后端测试，失败、错误及跳过均为 0。新增的跨进程 CLI 通过显式真实探针运行，不计入 JUnit 数量；普通 Maven 构建不自动提交链交易或退出测试进程。

总入口 `python3 deploy/scripts/verify-faults.py` 串行执行两个组件，独占本轮业务及消息拓扑。原配置、演示凭据及实际生产 classes/运行依赖摘要贯穿前后核对；报告中的 productionRuntimeSha256 为 `839b99d2d96e09143eeade757be3d8580784fd27bbc50465c9977ecc8b4e9995`。只有两组全部必需场景通过、各自原业务保护及三条正常链登记核对完成，才生成总 PASS。总入口 --resume 可复用已经通过的组件，但生产运行摘要变化时拒绝复用。

六次 halt 均发生在独立 JVM，按预定退出码触发；没有使用普通异常代替进程崩溃，没有通过 SQL 提前过期租约或直接写入成功状态。两个组件本轮均首轮通过，无非预期失败运行，六次预定退出不计作失败验收。T022～T026 的既有结果继续保留；本节对新增故障窗口实际运行并记录，不用历史探针冒充本轮结果。

### 2. 发送和候选对象崩溃

组件报告为 `.local/publish-crash-probe/20261027T072736Z-c5d5c75c/report.json` 和 verify.json，PASS：23 次真实 HTTP、120 项协调器检查。子进程在各自报告中执行边界断言，但未单独累加 checkCount，不把缺失计数字段当作没有执行检查。协调 JVM 的 PID 为 2441361，四个子 JVM 的 PID 均不同于协调器。

| 阶段 | 独立 JVM PID | 实际退出码 | 实际持久状态与外部证据 |
| --- | --- | --- | --- |
| before-publish | 2442402 | 73 | 原 Outbox 领取事务已提交，SENDING 且未来租约，自有真实队列 ready=0；尚未调用发送 |
| after-confirm | 2443298 | 74 | RabbitMQ 实际发布确认后、SENT SQL 前退出；原行仍 SENDING，自有真实队列 ready=1 |
| file-candidate | 2443809 | 75 | 真实 S3 候选对象 PUT 返回，源上传 VERIFYING 且处理租约有效；尚无正式版本 |
| recover | 2456331 | 0 | 新 JVM 启用默认发布、两个恢复扫描及真实消费者，完成原三次上传及存证 |

协调器实际等待 119740 毫秒，使两条发送租约及一条上传处理租约均按数据库 UTC 自然到期，再启动恢复应用。三种崩溃没有缩短生产的 120 秒租约，领取和结果事务仍由真实生产事务代理执行；NOT_SUPPORTED 测试边界在真实领取代理返回后才 halt，避免把未提交事务误记为持久检查点。

恢复后原三个 eventId 保持，源上传各只有一个正式版本、一个存证业务记录及一次有效链尝试，原 Outbox 最终 SENT。发布确认后崩溃的事件实际经过三次生产 listener 投递：原消息、发送租约恢复后的同 eventId 重发，以及另一次明确标记的 post-DONE 重放。前两次自然投递完成后才发送显式重放，重放后要求真实 ACK 计数超过原基线；duplicateDoneAcknowledgements=2、postReplayNewDuplicateAcknowledgements=1。其他两个原文件事件各有一次恢复期投递。所有 ACK 均由生产 listener 发出，不通过人工 basicAck 代替消费验收。

候选对象崩溃后，原候选仍存在且没有正式引用，恢复得到的正式对象使用新的候选身份；实际文件大小和摘要一致，仅形成一个版本。旧 S3 候选随本轮自有前缀在收尾删除；它不是一次生产 GC 验收。原 finalize 临时文件及 sidecar 仅留在本轮私有 managed-temp 目录作崩溃证据，两文件均为 0600，其他共享临时目录不受影响。

协调器核对并保留 25 条本轮 trace 关联审计；内部 Worker 的其他审计仍正常追加，不把该数视为全库新增审计总数。清理一个任务、三个账号、一台设备、三个上传会话、七个私有对象及关联链外夹具，自有空消息拓扑已删除；三条真实链登记保留，原二十四表逐字段/BLOB、行数及 45 条核验记录保持。

### 3. Fabric RPC 崩溃、UNKNOWN 与原交易恢复

组件报告为 `.local/fabric-crash-probe/20261027T073024Z-0710c6aa/report.json` 和 verify.json，PASS。五个阶段合计 20 次真实 HTTP/155 项检查，包含 setup 及三个崩溃阶段；最终 recover 报告自身为 0 次 HTTP/46 项检查，不能只用该阶段计数替代组件总数。

| 阶段 | 独立 JVM PID | 实际退出码 | 崩溃时数据库证据及恢复结果 |
| --- | --- | --- | --- |
| setup | 2458707 | 0 | 正式 HTTP 及实际定版生成三个固定版本，存证尚未调用；明文夹具密码/JWT 不写跨进程检查点 |
| before-submit | 2459750 | 73 | 原 transaction_bytes、txId 及 SUBMITTING 已提交，业务仍 PENDING、commitBytes 为空；在 submit RPC 前退出，重启仅提交原交易 |
| after-submit | 2460757 | 73 | 真实 submit 返回，测试 oracle 已观察区块 42 的 VALID/code=0；commitBytes 尚未落库，原业务 PENDING、尝试 SUBMITTING，随后退出 |
| after-commit | 2461466 | 73 | 真实 commit 查询返回前已有原交易及 commitBytes 持久保存，业务/尝试 SUBMITTED；oracle 观察区块 43 的 VALID/code=0，结果落库前退出 |
| recover | 2462749 | 0 | 等原处理租约自然到期，默认扫描自动恢复，三个原业务最终 CONFIRMED |

恢复 JVM 在应用上下文启动前实际等待 116016 毫秒，并确认数据库没有本轮仍有效的处理租约。恢复应用暂停自己的消息消费者，启用默认业务扫描，证明不依赖额外消息即可恢复；没有手动调用 recoverDue 冒充默认扫描。recoveryPreparedTransactions=0，只有 before-submit 的原 txId 被再次提交，三个业务均只有一个原尝试，原交易字节摘要保持。

对 after-commit 一项，在测试装配的 commit 查询及其业务记录回退查询入口同时注入 UNAVAILABLE。至少两次默认扫描后，实际持久检查点为 UNKNOWN、retry_count=2、lease_token 为空、next_action_at=`2026-10-27T07:33:35.474`，原消费 DONE；两个 commit 调用和两个业务回退查询均遭遇指定边界注入。断言等待该完整事务已提交，不只观察 RPC 入口计数。放开注入后，原 commitBytes 保持并实际确认。未停止共享 Fabric 网络，不把此结果表述为物理网络停机。

提交后未保存 commitBytes 的版本通过两机构相同业务记录完成对账，业务为 CONFIRMED，而原尝试仍 SUBMITTING、commitBytes/本地 validationCode/blockNumber 为空。报告单独保留崩溃前真实 VALID oracle 与当前两机构五字段查询，标明 confirmationBasis；没有补造检查点、强写 VALID 或新增第二次尝试。其他两项保留实际 VALID 与 commitBytes，并重新查询原确认请求核对有效回执。真实响应在退出点已取得，但尚未返回业务处理器或落库；这证明进程退出丢失结果，不冒充测试了网络层丢包。

组件核对并保留 27 条本轮 trace 关联审计，原账本和全部安全审计追加；清理一个任务、三个账号、一台设备、三个上传会话、六个私有对象及关联链外夹具，空消息拓扑已删除。原二十四表逐字段/BLOB、行数和 45 条核验记录保持，与发送组的原数据域摘要同为 `66128a23cee7a9416f7141c1ff8cb4117029c857c5629500a164e2947e6b0390`。

### 4. 六条实际登记及数据保护

| 组件 | 版本 | evidenceId | 首次有效交易编号 | 区块号 |
| --- | --- | --- | --- | --- |
| 发送/定版 | 2 | `89ccc867-348e-419d-bf64-89d553d6da94` | `8f5b8ebaa06fc10574b60ed29dcfeb37cc203cb3d2196d106ac59c5959af0e77` | 40 |
| 发送/定版 | 1 | `89ccc867-348e-419d-bf64-89d553d6da94` | `fa8028533cdbca1c93b017e2e595f9da7b73548e1f2cfae9ac97f383f53aa0ac` | 39 |
| 发送/定版 | 3 | `89ccc867-348e-419d-bf64-89d553d6da94` | `da905e3f4960c455e1330a67a8bb998a76b9b439bad6101ffeb20fc149a11051` | 41 |
| Fabric RPC | 1 | `71692842-43b7-4ae2-b6e2-2ca92a06336e` | `3237ac02c7c607194adff7d6895ad347857af6bd3a20e9ab9e4e8e05b8548f8c` | 44 |
| Fabric RPC | 2 | `71692842-43b7-4ae2-b6e2-2ca92a06336e` | `18d2f3dd111a45e09edbbf07e7df22e71b8655068c5fce260c74afa8a8255f68` | 42 |
| Fabric RPC | 3 | `71692842-43b7-4ae2-b6e2-2ca92a06336e` | `e6db8849b7d646d3824d8fd0b496d26c3974ad4f9f24e3ce62f898a4a1a330a6` | 43 |

六条登记均为实际 VALID/code=0；对应正式对象的大小和 SHA-256 已实际回读，双机构查询五字段一致并保留首次交易编号。发送组的版本生成顺序由真实消费时序决定，原第二个上传先被恢复，不假定上传创建顺序等于版本号顺序。原三个 RPC 版本的检查点哈希保存在独立安全报告，提交后缺失 commit 检查点的合法情况见上文。

`.local/t027-preservation-before.json` 的 migrationsValidated=14、matchesPreviousFinalRecheck=true，原十六表完整摘要为 `f57a18391478a1e92d6d18ba58234467309539c176514d88ac09cb6db4ce280b`，全库核验 45 条。故障矩阵及数据库检查后，`.local/t027-preservation-after-probes.json` 的 matchesT027BeforeWork=true，原十六表全部字段/BLOB、逐表行数及演示凭据摘要保持；清理任务、人工重检、拒收及 FAILED Outbox 行数为 0。

最终数据库报告 `.local/database-probe/20261027T073435Z-9fa125b3/verify.json` 为 PASS：248 项真实约束、UTC 往返和回滚协议通过，allProbeWritesRolledBack=true、sameBusinessSnapshot=true、sameFullMigrationChecksums=true。V1～V14 校验和保持，没有新增迁移。二十四表仍使用原 `T025-full-twenty-four-table-snapshot-v1` 域，摘要为 `cf1248cd9491375432f4d9179cda5ec23526e544fdcbbb8ab1e8a1d2485db5df`；兼容二十三表摘要为 `c2f3c8d81f9d906fa348d84647fb314c9c70503a87d1dbd871e6a5566a5d080e`。不同工具的摘要域分别比较，不跨域推断数据变化。

### 5. 最终 JAR、原 M2 与私有产物

原 M2 同版本复查 `.local/mvp-demo/20261023T074934Z-34a10f3c/rechecks/20261027T073526Z-2a6a963c/report.json` 为 PASS：15 次 HTTP/110 项检查。十四版迁移及 originalBusinessRowsUnchanged=true，只追加三次正式核验和审计；目标版本历史 42→45，全库 verification_record 45→48，追加后的原十六表摘要为 `9bde39443ab668544ba0fbd21d241623dc775ee75763c6b98d2e7138500b30da`。

原对象、固定版本和首次有效交易保持：原文件仍为 79 字节、SHA-256=`ec81ce39789f63a8f58cd7c6ecaef171b4b944d665ea92d79b2c92175ea2726f`，双机构仍返回 txId=`6b0031134784a493c1f2139f8c7b7c471e6fbee7c2dedbcba95d921f9bf12799`、区块 13、VALID/code=0，transactionBytes/commitBytes 长度仍为 4630/905、摘要保持。复查没有新增任务、文件版本或链登记，本轮新增登记仅为上文区块 39～44 的六条。

ownApplicationStopped=true、emptyOwnTopologyDeleted=true，实际运行产物与当前最终 JAR 的 SHA-256 均为 `0cdb1b057844caa9c11e1105a05fc40cc856fb8123613b13439bd9d029c7af6e`。私有产物检查 `.local/t027-artifact-audit.json` 为 PASS：59 份 JSON/日志均为 0600，原 .env、演示凭据及 Gateway 身份清单仍为 0600；未发现已知服务/演示密码、原设备令牌、JWT、PEM 私钥正文或原始签名 URL。设备令牌扫描包含 droneTokens 及兼容 deviceToken；两份保留的崩溃临时文件也为 0600。全部故障子进程、恢复应用及业务 Worker 均已结束，无项目后端或业务探针进程残留。未清库、删卷、改旧迁移或重建凭据。

复验入口如下，使用 JDK 21、原有服务与 User1 身份，执行前停止其他后端和消费者：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-faults.py
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/20261023T074934Z-34a10f3c
```

观察及分组件恢复/收尾方法见 [t027.http](../examples/t027.http) 和 [后端说明](../backend/README.md#跨系统故障注入与恢复验收t027)。T027 及 M4 已完成，下一项 T028 提供最小可用管理界面并联调。M5、12 项最终验收、压测和完整交付尚未完成。
