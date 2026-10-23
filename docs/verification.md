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
