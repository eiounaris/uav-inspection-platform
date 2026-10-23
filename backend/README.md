# 后端工程

Spring Boot 4.1.1、Java 21、Maven 单工程。已实现 HTTP 基础能力、Gateway 适配、MySQL/MyBatis/Flyway、用户认证、T009 任务与初始执行，以及 T010 私有对象存储、持久上传会话与分片幂等；T011 已接入完整文件校验、异步定版和 RabbitMQ Outbox。T012 已接入真实 Fabric 存证消费、二进制交易检查点与 UNKNOWN 恢复；T013 已接入存储文件与上传副本的实际字节核验及核验历史；T014 可保留、可重查的最小真实闭环演示已验收通过，M2 已通过，下一项为 T015 设备管理与上报。实际证据见验证记录。

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

`local` 开启项目包 DEBUG 日志，`prod` 使用 INFO；两个环境共用 UTC 时间和错误响应规则。database 模式将 Spring Web/Security 日志保持为 INFO，避免输出认证正文或请求头。`.env.example` 用于说明参数，Spring Boot 不会自动读取 `.env`，运行前需按需设置环境变量。`PROJECT_JAVA_HOME` 也不会自动改变 `JAVA_HOME`。

上述默认启动用于独立 HTTP 验证，不连接数据库。启用真实业务数据库使用以下入口。

## MySQL、迁移与演示数据（T007）

前提是 T003 的 MySQL、Garage、RabbitMQ 服务已启动，项目根目录已有 .env，终端已选择 JDK 21：

```bash
python3 deploy/scripts/init-demo-credentials.py
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/run-backend.py --demo
```

最后一个命令启动实际 Web 后端，Ctrl+C 停止。启动器默认启用 local,database,storage,messaging；--demo 再加入 demo，--profile prod 则使用 prod,database,storage,messaging，不能与 --demo 合用。启动器显式读取已有 .env 中的 DB_*、S3_*、UPLOAD_*、RABBITMQ_*、MESSAGING_*、JWT_SECRET 及 DEMO_CREDENTIALS_PATH，通过进程环境传给 Java，不执行 .env 中的 shell 表达式。首次环境准备见 [部署说明](../deploy/README.md)；继续开发时沿用原 .env 和演示凭据，不重新生成或覆盖它们，缺少的新配置按 .env.example 在本地补齐。

storage 需要显式提供 S3_ENDPOINT、S3_ACCESS_KEY、S3_SECRET_KEY，使用 T003 已配置的 Garage 私有桶。移除 --demo 只关闭演示初始化，仍启用存储和消息处理。若只验证数据库、认证和任务，可自行提供 DB_*、JWT_SECRET 等进程环境，使用 `SPRING_PROFILES_ACTIVE=local,database java -jar backend/target/inspection-backend-0.1.0.jar`；此组合不创建 S3 客户端，也不提供上传接口。直接运行 JAR 不会自动读取 .env。

database profile 启用 Hikari、MyBatis 与 Flyway。连接会话使用 UTC 和 READ COMMITTED，Connector/J 默认 sslMode=REQUIRED；生产部署可通过 DB_SSL_MODE 配置更严格的证书校验。普通 local/prod 启动不启用 database 时排除这些自动配置，原 HTTP/Gateway 探针可独立运行。MyBatis SQL 参数日志关闭，local-cache-scope=STATEMENT，锁后权限与成员关系重查不会复用同事务中较早的 SESSION 缓存。

首版迁移 V1__identity_and_task_scope.sql 建立 organization、app_user、inspection_task、task_member、drone 五张表，并创建两个固定机构。roles JSON、机构角色白名单、唯一用户名、创建人机构、设备摘要及外键由数据库约束；username 区分大小写。T008 新增 V2__identity_audit.sql 建立 audit_log；T009 新增 V3__task_execution_scope.sql 建立 inspection_subtask、material_requirement、task_execution。V3 的复合外键限制资料项与子任务属于同一任务、currentExecutionId 属于当前子任务，并以唯一约束限制一个子任务只有一个 READY/RUNNING 批次。T010 新增 V4__upload_sessions_and_versions.sql，建立 upload_session、upload_part、evidence_version；复合外键固定资料项、证据编号、任务、子任务和执行作用域，唯一约束保证主体请求键、分片号和未来版本号不重复。T010 当时不插入 evidence_version；T011 的 V5__finalization_outbox_and_submission.sql 新增 outbox_event、consumed_event、chain_submission，提供事件派发、消费回执和待存证状态。T012 追加 V6__chain_attempts.sql，建立 chain_attempt 保存原 Fabric 交易与确认请求检查点；T013 追加 V7__verification_records.sql，建立 verification_record 保存实际摘要、链上摘要、核验来源、结果及原因；V1～V6 保持不变。Flyway 校验开启，自动 baseline 和 clean 关闭，不使用 schema.sql/data.sql；后续结构变化继续追加迁移。

demo 必须同时包含 local、database，任何包含 prod 的组合都会被应用自身拒绝。初始化在一个事务中创建以下账号与一台 UAV-DEMO-001，不预造任务、成员、文件或成功状态：

| 用户名 | 机构 | 初始角色 |
| --- | --- | --- |
| owner-admin | OrgA | ORG_ADMIN、OWNER_TASK_ADMIN |
| owner-reviewer | OrgA | OWNER_REVIEWER |
| owner-approver | OrgA | OWNER_DATA_APPROVER |
| operator-admin | OrgB | ORG_ADMIN、OPERATOR_TASK_ADMIN |
| operator-user | OrgB | OPERATOR_USER |
| operator-outsider | OrgB | OPERATOR_USER，预留无任务范围用例 |

随机密码和设备令牌只保存在忽略提交的 .local/demo-credentials.json（0600）；脚本不打印它们，也不覆盖已有文件。数据库保存 cost=12 的 BCrypt 摘要和设备令牌 SHA-256。重复初始化核对固定身份和原凭据，保留合法的已修改角色、版本、启用状态及其他业务数据；不会把角色恢复为上表初始值。固定身份或凭据不匹配时拒绝覆盖。应保留原凭据文件，不能靠删除它来重置账号。登录接口已实现，设备认证接口在 T015 接入。

T007 当时的真实验证使用数据库探针三个独立进程：migrate 不启用 demo；seed 初始化并核对 Mapper 结果；verify 再次启动，比较五张表全部字段的摘要及凭据文件，执行 24 项约束拒绝检查和 UTC 时间往返。所有临时任务、成员及字段修改均回滚，验证后为 2 个机构、6 个用户、0 个任务/成员和 1 台设备。JVM 使用 Asia/Shanghai，固定毫秒时间仍按 UTC 保存和读取。

数据库探针现已支持 V1～V7，比较七版迁移校验和及十六张业务表完整快照，执行 110 项真实约束检查及 UTC 往返；探针写入均回滚。最新成功报告由 .local/database-probe/latest.json 指向，T007 的 24 项约束及五表历史证据继续保留在 [验证记录](../docs/verification.md)。需要再次检查时执行 `python3 deploy/scripts/verify-database.py`。探针要求当前演示基线，后续业务数据变化时需更新相应验收用例。后端测试和显式真实探针分别执行，普通 Maven 构建不自动连接 MySQL、S3 或提交链交易。

## 用户认证与角色管理（T008）

以下四个接口由 database profile 启用，统一前缀为 `/api/v1`，响应仍使用 `code/message/data/traceId`：

| 方法与路径 | 请求 | 权限与结果 |
| --- | --- | --- |
| POST /auth/login | JSON 仅含 username、password | 无需登录；返回 data.accessToken 和 UTC data.expiresAt |
| POST /auth/logout | 无请求正文；Authorization: Bearer 令牌 | 注销该账号当前请求版本的全部令牌 |
| GET /users/me | Authorization: Bearer 令牌 | 返回 id、orgId、username、roles、tokenVersion、lockVersion |
| PUT /users/{id}/roles | JSON 仅含 roles、expectedLockVersion；Bearer 令牌 | 本机构 ORG_ADMIN 修改同机构目标账号，返回目标账号的新角色及版本 |

username 区分大小写，长度最多 64；password 不得为空，UTF-8 编码最多 72 字节。登录请求不能另传 orgId、userId、roles 或 tokenVersion，角色修改也不能另传机构身份，未知字段返回 400。错误账号、错误密码及停用账号统一返回 401，不返回账号存在性或密码细节。登录对不存在的账号也执行固定成本 BCrypt 校验。

JWT 固定使用 HS256，有效期 30 分钟，无刷新令牌。JWT_SECRET 必须是恰好 64 个小写十六进制字符，解码为 32 字节签名密钥；database 模式下缺失或格式错误会拒绝启动。令牌校验签名、类型、issuer、audience、规范 UUID subject、整数 tokenVersion 及时间范围；时间校验 clock skew 为零，不能依赖过期后的宽限。expiresAt 与令牌内的秒级 exp 一致。不要将密钥或访问令牌写入代码、请求示例、日志或提交记录。

每次受保护请求都按令牌用户编号读取数据库：enabled=false、用户不存在或 tokenVersion 不一致时返回 401；实际机构和角色以数据库为准。角色不从客户端请求或 JWT 权限声明取值。数据库状态不可读取时返回 503，不把依赖故障当作密码错误或账号已注销。

角色修改要求调用者当前仍有 ORG_ADMIN，且目标属于同一机构。OrgA 可分配 ORG_ADMIN 及三个 OWNER_* 角色，OrgB 可分配 ORG_ADMIN、OPERATOR_TASK_ADMIN、OPERATOR_USER；空列表、重复角色或不属于目标机构的角色返回 400。无管理权限、跨机构及不可管理目标返回 403。expectedLockVersion 必须取目标账号的最新版本，可由目标账号的 `/users/me` 响应取得；角色更新返回的新版本可用于下一次操作。

服务在事务中按用户编号顺序锁定调用者和目标，再次核对调用者账号、令牌版本和权限；目标以 expectedLockVersion 做 CAS，版本过期返回 409。成功修改同时递增目标 tokenVersion 和 lockVersion，目标旧 JWT 随后返回 401；目标需要重新登录获取新令牌。修改自身角色也执行相同规则。退出使用请求原 tokenVersion 做条件更新，只有匹配才递增两个版本；已进入服务的延迟退出不会误注销在后续版本签发的新令牌。

成功登录、退出和角色修改分别追加 AUTH_LOGIN、AUTH_LOGOUT、USER_ROLES_CHANGED 审计，记录安全的操作者、机构、资源、traceId 和 SUCCESS。角色/退出写操作与成功审计处于同一数据库事务，审计失败时回滚；并发退出的无变更分支不追加成功审计。审计不保存密码、JWT、密码摘要、设备令牌或请求正文，仅提供追加 Mapper，没有更新/删除接口。

手动调用使用 [examples/t008.http](../examples/t008.http)。从本机凭据文件读取对应账号密码，将真实密码和令牌保存在忽略提交的 REST Client 环境中；示例文件只保留占位符。自动验证在 MySQL 已启动、.env 已有 DB_* 和 JWT_SECRET、本地演示凭据文件存在的情况下执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-auth.py
```

认证探针通过真实 Web 应用和 MySQL 执行调用，创建带随机编号的隔离测试账号，结束时清理自己的账号并保留成功审计证据；不运行 demo 初始化，不修改原演示账号、设备、业务数据或凭据文件。日志与安全报告位于 `.local/auth-probe/<runId>/`，`.local/auth-probe/latest.json` 指向最近成功报告。后端测试在 Maven 中运行，真实探针不计入其测试数量；具体执行结果见 [验证记录](../docs/verification.md)。

T008 提供用户认证及机构内角色管理，T009 补充下文任务成员范围。ORG_ADMIN 不会自动获得全部巡检资料权限；任务创建、上传、存证和核验的完整演示见下文 T014。

## 任务、资料要求与初始执行（T009）

以下九项操作在 database 模式下使用 Bearer 令牌，路径前缀均为 `/api/v1`。创建任务不需要既有版本；其余写操作必须提供对应对象最新的 expectedLockVersion。

| 方法与路径 | 请求要点 | 权限、状态及返回 |
| --- | --- | --- |
| GET /tasks | page=1、pageSize=20，pageSize 范围 1～100 | 按任务成员、机构及适用业务角色过滤；返回 items/page/pageSize/total |
| GET /tasks/{id} | 无正文 | 任务成员及适用业务角色；返回任务、成员、资料要求和子任务当前批次 |
| POST /tasks | name、scope、requirements[{name,required}] | 委托方 OWNER_TASK_ADMIN 创建 DRAFT；自动加入创建人，资料要求可为空草稿 |
| PUT /tasks/{id}/requirements | requirements[{id?,name,required}]、expectedLockVersion | 委托方任务管理员成员在 DRAFT/ASSIGNED 维护要求；返回 TaskDetail |
| POST /tasks/{id}/assign | operatorOrgId、operatorAdminId、expectedLockVersion | 委托方任务管理员成员将 DRAFT 分配为 ASSIGNED，并加入指定运营商管理员 |
| PUT /tasks/{id}/members | userIds、expectedLockVersion | 本任务的本机构任务管理员维护自己机构的可管理成员；返回 TaskDetail |
| POST /tasks/{id}/subtasks | name、requirementIds、expectedLockVersion | 运营商任务管理员成员在 ASSIGNED 建立子任务并绑定未分配资料项；返回 TaskDetail |
| POST /subtasks/{id}/executions | droneId、expectedLockVersion | 运营商任务管理员成员初次分配设备；返回 ExecutionView，此处版本属于子任务 |
| POST /tasks/{id}/start | expectedLockVersion | 运营商任务管理员成员启动 ASSIGNED 任务；返回 TaskDetail |

TaskDetail 保持平铺任务字段，并包含 members、requirements、subtasks；每个子任务包含 currentExecutionId、lockVersion 及 execution（未分配时为空）。ExecutionView 包含 id、subtaskId、droneId、status、progress、startedAt、closedAt、lockVersion。执行分配完成会同时增加任务版本，启动前应重新 GET 任务详情取得最新任务 lockVersion。

任务创建固定 owner 为 OrgA、operator 为 OrgB，不接受客户端伪造创建者或机构。分配时仅接受 OrgB，以及启用且具 OPERATOR_TASK_ADMIN 的同机构账号。委托方读取角色为 OWNER_TASK_ADMIN、OWNER_REVIEWER、OWNER_DATA_APPROVER，运营商读取角色为 OPERATOR_TASK_ADMIN、OPERATOR_USER；同时必须是任务成员。仅有 ORG_ADMIN 的账号不具任务读取或维护权限。没有任务范围及未知任务/子任务统一返回 403，不通过详情或分页泄露资源。

请求编号使用规范的小写 UUID，路径和正文中格式不合法的编号返回 400；合法但不可访问的任务资源返回统一 403。机构、创建者、状态、证据编号及批次编号由服务端控制，创建请求中的额外字段会被拒绝。

列表查询先在 SQL 中按成员、机构和当前业务角色过滤，再执行 COUNT 与 LIMIT，按 createdAt DESC、id DESC 稳定排序。角色来自数据库；任务写事务在锁定任务后重新锁定并验证操作者账号、tokenVersion 和当前角色，再校验成员关系，避免只信任进入请求时的身份快照。

成员维护只替换调用者本机构的成员，候选必须启用、属于本机构且具相应业务角色；不会删除其他机构成员或创建人。userIds 可以为空，创建人仍保留。委托方和运营商分别使用 OWNER_TASK_ADMIN、OPERATOR_TASK_ADMIN；不能借成员接口为另一机构增删成员。

资料要求维护是整个列表替换：已有项传原 id，保留 evidenceId、子任务绑定及版本分配信息；新增项省略 id，由后端分别生成资料编号与稳定证据编号。仅能删除未绑定项，遗漏已绑定项会返回 409；不接受其他任务的资料编号。运营商创建子任务只绑定资料项，不能改变要求内容。启动后要求冻结，名称、必交标志或绑定不能通过这些接口修改。

初次分配仅限 ASSIGNED：创建一个 READY 批次并设置子任务 currentExecutionId，同时增加子任务和任务版本。已有当前批次或版本过期返回 409，不产生重复执行；未知、停用或归属不符的初始设备返回 400。运行中换机在 T016 接入。

启动前必须至少有一个子任务，每个子任务至少绑定一个 required=true 的资料项，所有资料均已绑定本任务子任务，每个子任务都有当前 READY 批次且设备仍启用、归属正确。不满足条件返回 409；包括只有可选资料、仍有未绑定资料或 READY 设备被停用。启动复用原批次 id，将原批次变为 RUNNING，写 UTC startedAt，并增加任务、子任务及执行版本，不重新插入批次。重复启动或陈旧 expectedLockVersion 返回 409。

所有写操作与成功审计使用同一数据库事务。已有任务先取得父任务行锁，随后处理子任务和资料；初次分配锁定所选设备，启动按去重后的设备编号排序获取行锁，避免不同任务以相反次序锁同一组设备。审计动作共七类：TASK_CREATED、TASK_REQUIREMENTS_UPDATED、TASK_ASSIGNED、TASK_MEMBERS_UPDATED、SUBTASK_CREATED、EXECUTION_CREATED、TASK_STARTED；除 EXECUTION_CREATED 关联 TASK_EXECUTION 外，其余关联 INSPECTION_TASK。审计只保存安全元数据，CAS 或审计失败回滚本次变更。

手动执行 [examples/t009.http](../examples/t009.http) 可从登录开始创建并保留一条真实任务，供后续上传示例使用。真实验收使用隔离测试账号、设备及任务，MySQL 和 JWT 配置沿用上述启动方式：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-tasks.py
```

探针以真实 HTTP 和 MySQL 验证任务流程、权限、成员范围、绑定、初次执行及启动条件，保留审计证据并清理自己的关联测试数据；不会清空原业务表或更改演示凭据。报告与私有日志位于 `.local/task-probe/<runId>/`，`.local/task-probe/latest.json` 指向最近成功报告。后端测试与真实验收结果以 [验证记录](../docs/verification.md) 为准；T010 已接入下文上传能力，固定版本已在 T011 接入，T012 已提供真实链存证与基础恢复；T014 将这些正式接口串成可保留的演示任务。

## 私有对象存储与上传会话（T010）

上传接口要求同时启用 database、storage，统一使用 Bearer 令牌和 `/api/v1` 前缀。当前支持用户上传：调用者必须有 OrgB 的 OPERATOR_USER 角色、属于任务成员，读取或写入已有会话还必须是该会话创建者。只有 OPERATOR_TASK_ADMIN 或 ORG_ADMIN 不具上传权限；另一位任务成员也不能读取别人的会话。设备凭据上传在 T015 接入。

| 方法与路径 | 请求 | 返回 |
| --- | --- | --- |
| POST /upload-sessions | JSON：clientRequestId、requirementId、executionId、filename、contentType、size | 会话进度，包含 uploadId、固定分片规格、原 expiresAt、已提交分片和 uploadedSize |
| PUT /upload-sessions/{id}/parts/{partNo} | Content-Type: application/octet-stream，正文直接传分片原始字节 | uploadId、partNo、实际 size、服务端计算的 sha256 |
| GET /upload-sessions/{id} | 无正文 | 从 MySQL 读取的会话进度，uploadedParts 按分片号排列 |

编号必须是规范小写 UUID；clientRequestId 表示一次逻辑上传，重试时保留原值。filename 只能是文件名，长度不超过 255，不能包含路径或控制字符。contentType 新增为必填字段，必须与允许的文件扩展名匹配；支持 JPEG、PNG、PDF、MP4，首片再检查文件签名与声明是否一致。size 是声明的完整文件字节数，必须为正且不超过配置上限。服务固定资料项与执行批次的作用域，并要求当前任务、子任务和批次允许上传，不能借其他任务或旧批次编号写入。

默认文件上限 200 MiB、分片大小 8 MiB、会话有效期 24 小时。partNo 从 1 开始，partCount=ceil(size/partSize)；除最后一片外必须恰好为 partSize，最后一片必须恰好为剩余长度。支持先传后续分片，再按 GET 返回的已提交分片补传缺失部分；正文使用原始字节，不使用 multipart/form-data。短片返回 400、超长片返回 413，格式或文件签名不符返回 400。

幂等键为 actorType、actorId、clientRequestId。同键且全部创建参数相同，返回原会话及原 expiresAt，不延长有效期；参数不同返回 409 IDEMPOTENCY_CONFLICT。同一分片号、相同长度和摘要返回原分片，不重复插入或记录成功审计；内容不同返回 409 PART_CONTENT_CONFLICT。并发提交仍只保留一条会话或分片记录。新会话和新分片分别记录 UPLOAD_SESSION_CREATED、UPLOAD_PART_STORED 安全审计。

过期会话不能继续写片，返回 409 UPLOAD_SESSION_EXPIRED；VERIFYING 等冻结状态返回 409 UPLOAD_NOT_WRITABLE，已完成任务返回 409 TASK_COMPLETED。GET 仍可查看进度，尚为 UPLOADING 但已到期的会话对外显示 EXPIRED。每次写入在数据库锁下重新核对账号、成员、会话所有者及当前批次，成员移除或批次变化后，旧会话不能绕过权限继续写入。

S3 客户端固定使用 AWS SDK 2.56.0、UrlConnectionHttpClient，默认采用 path style。请求和响应 checksum 策略均设为 WHEN_REQUIRED，以兼容 Garage；应用自己计算并持久化每片 SHA-256。客户端通过 RequestBody.fromFile 重开稳定文件，重试不依赖已消费的流。S3_ENDPOINT、S3_ACCESS_KEY、S3_SECRET_KEY 必须显式提供；S3_REGION 默认 garage，S3_BUCKET 默认 uav-evidence，S3_FORCE_PATH_STYLE 默认 true。上传限制通过 UPLOAD_MAX_FILE_SIZE、UPLOAD_PART_SIZE、UPLOAD_SESSION_TTL_SECONDS、UPLOAD_ALLOWED_EXTENSIONS 配置；临时目录默认 `.local/uploads/tmp`，可用 UPLOAD_TEMP_DIRECTORY 和 UPLOAD_MIN_FREE_SPACE 调整目录及最低剩余空间。

HTTP 接收分片时使用 0600 临时文件，按长度上限流式读取、计数和计算摘要；S3 网络写入在数据库事务外进行，每个候选对象使用独立服务端键。短事务重新验证作用域并提交分片引用，已确认未引用的候选对象删除。数据库提交结果不明时保留对象，避免删掉可能已提交的引用；孤儿扫描与故障恢复在 T025 完善。私有桶不开放匿名读取，上传响应和审计不返回 objectKey、私钥、S3 凭据或 JWT；对象存储故障统一返回 503。

完成所有分片后仍为 UPLOADING，versionId=null；调用下文 T011 完成接口后进入异步校验和定版。仅启用 database,storage 可继续使用 T010 会话与分片接口；完成接口及 Worker 还需要 messaging。历史批次隔离在 T016、多机业务验收及清理恢复在 T017、T025 继续完成；单片上传到真实核验的闭环见下文 T014。

手动执行 [examples/t010.http](../examples/t010.http)，其中使用 [79 字节示例 PNG](../examples/files/inspection-sample.png)。先运行 [examples/t009.http](../examples/t009.http) 创建并启动真实任务，再按上传示例为 operator-user 加入任务成员；保留 operator-admin 及委托方成员。真实密码和令牌只保存在本地忽略提交的 REST Client 环境中。MySQL、Garage 私有桶和原 .env 已就绪、JDK 21 已选择后，可执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-upload.py
```

上传探针启用 local,database,storage，以真实 T009 API 建立隔离任务、成员和运行批次，再执行会话及分片请求。验证包含并发创建、相同与不同内容并发写片、倒序两片续传、权限与状态拒绝、实际 Garage 字节与数据库摘要一致、匿名 HEAD/GET 被拒，以及存储不可达时 503 且无成功分片。结束时只删除本次上传前缀内的对象及关联测试数据，核对原十一张业务表与演示凭据未变，保留成功审计。日志、报告权限为 0600，位于 `.local/upload-probe/<runId>/`；`.local/upload-probe/latest.json` 指向最近成功报告。后端测试与真实验证结果见 [验证记录](../docs/verification.md)。

## 完成上传、异步定版与 Outbox（T011）

启用 database,storage,messaging 后提供 `POST /api/v1/upload-sessions/{id}/complete`，请求无正文，沿用会话创建人的 Bearer 令牌。接口在短事务内重新核对当前账号、任务成员、会话所有者、有效期及执行批次，并检查分片数量、连续编号和长度。缺片返回 409 UPLOAD_PARTS_INCOMPLETE；成功将 UPLOADING 改为 VERIFYING、记录成功审计并插入一条 FILE_FINALIZE_REQUESTED Outbox，统一返回 HTTP 202 及 UploadProgress。重复调用返回原进度，不重复冻结、发事件或分配版本；已完成时仍返回 202，versionId 为原版本。

完成接口只持久化处理意图。Outbox 发布器用数据库时间和租约领取事件，在事务外发送持久 RabbitMQ 消息；只有 Publisher Confirm 成功且 mandatory 没有 Return 才标为 SENT，失败保留原 eventId 并按退避重发。默认持久 direct exchange 为 inspection.events，file.finalize 路由到 inspection.file.finalize，evidence.anchor 路由到 inspection.evidence.anchor。事件仅含 eventId、eventType、bizId、traceId，不包含文件字节、对象键或凭据。存证事件的 bizId 为 chain_submission.id。

文件消费者采用 manual ACK 和 `(consumer_code,event_id)` 唯一消费回执。Worker 在短事务领取 120 秒处理租约，事务外按分片号读取私有 Garage 对象，重新校验每片长度与 SHA-256，流式合并并计算完整摘要，验证完整 PNG/JPEG/PDF/MP4 容器后写入独立最终对象。PNG 校验块长度、CRC、IEND 并解码图像，PNG/JPEG 图像上限为 2500 万像素；其余格式的结构检查范围及限制见设计文档，不把文件签名检查当作完整媒体解析。

最终短事务重新核对任务状态和租约，锁定资料项并分配 versionNo，同时写入固定 evidence_version、PENDING chain_submission、EVIDENCE_ANCHOR_REQUESTED Outbox、成功审计，将会话改为 COMPLETED 并写 DONE 回执；ACK 随后执行。该事务任一步失败都会回滚数据库结果。固定版本保留用户来源、长度、完整摘要和私有对象引用；重复完成或消息重投不会新增版本。当前批次产生新版本时，WAITING_ACCEPTANCE 任务重新进入 RUNNING。

内容校验失败进入 FAILED，不创建版本或存证记录，修正文件后应创建新上传会话。对象存储或本地 I/O 暂时失败保持 VERIFYING，事务保存 retryCount、nextActionAt、安全错误码和 DONE 回执，再 ACK；独立扫描器读取这些持久检查点重试，延迟为 2、10、30、60、300 秒。数据库未能提交结果或检查点时不 ACK，消费者关闭通道，容器恢复后重投。格式错误的消息拒绝并进入持久死信队列 inspection.dead，避免立即重排形成空转。

GET 上传进度在 COMPLETED 时返回 versionId，其余状态为 null。COMPLETED 表示固定文件版本生成；仅运行 T011 的 database,storage,messaging 组合时，anchor 消息保留在队列中，chain_submission 仍为 PENDING；增加 ledger profile 后启用下文 T012 真实存证消费者。租约续期、完整恢复矩阵和引用感知孤儿清理分别在 T023、T027、T025 完善；当前提交结果不明时保留最终候选对象。

RabbitMQ 使用已有 .env 中的 RABBITMQ_HOST、RABBITMQ_PORT、RABBITMQ_USERNAME、RABBITMQ_PASSWORD。messaging profile 的拓扑、派发和恢复间隔可由 .env.example 中的 MESSAGING_* 参数配置；默认派发间隔 1 秒、恢复扫描 10 秒。database 或 database,storage 单独运行不连接 RabbitMQ，也不启动本节 Worker。手动流程见 [examples/t011.http](../examples/t011.http)：沿用 T010 的 uploadId，完成后轮询 processingStatus 与 versionId。

真实验收需 MySQL、Garage、RabbitMQ 已启动，沿用原 .env 和演示凭据，先构建后执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-pipeline.py
```

探针创建隔离账号、任务、执行和随机 RabbitMQ 拓扑，通过真实 HTTP、MySQL、Garage、RabbitMQ 完成两片倒序上传、并发完成、真实消费者定版、完整字节与摘要核验、重复消息、mandatory 路由失败与恢复、已登记对象损坏和完整 PNG 格式拒绝。临时故障用例在实际 Garage 读取流中注入一次 IOException，验证持久 VERIFYING 检查点和恢复扫描成功。探针显式驱动发布与恢复，启动前要求无既有待派发 Outbox 或 VERIFYING 会话，避免推动其他人的待处理工作；不会停止服务或清空共享队列。

探针结束时停止自己的消费者，仅删除本次对象前缀、关联测试数据及随机拓扑，比较原十四张业务表完整快照，保留追加审计；演示凭据文件必须不变。报告与私有日志位于 `.local/pipeline-probe/<runId>/`（0600），最近成功报告由 `.local/pipeline-probe/latest.json` 指向，实际结果见 [验证记录](../docs/verification.md)。普通 Maven 测试不自动运行此真实探针。2026-10-22 验证通过：后端 99 项测试；真实链路探针 42 次 HTTP 调用、404 项断言，生成 3 个固定版本并核验 2 个文件失败分支，原十四张业务表及演示凭据保持不变。

## Fabric 存证、查询与原交易恢复（T012）

沿用既有 Fabric 开发网络、mychannel 和 evidence 链码，显式启用真实存证：

```bash
python3 deploy/scripts/run-backend.py --demo --ledger
```

--ledger 在既有 local,database,storage,messaging 上加入 ledger；生产可使用 --profile prod --ledger 并显式配置身份路径。默认启动器不启用 ledger，T011 文件处理可独立运行。ledger 模式创建 OrgA、OrgB 各一个可复用 Gateway，应用按版本的 submitterOrgId 选择机构签名身份，HTTP 请求不能指定 MSP、签名人或交易字节。两机构使用已有普通 User1 身份，Peer TLS CA 和主机名校验保持启用。

本地 --ledger 缺少身份路径时，启动器仅从既有 `.local/fabric/gateway-paths.json` 补齐 User1 的证书、私钥与 TLS 证书路径，不创建身份或打印密钥，也不覆盖 .env。路径完整时使用显式配置；生产不会读取本地开发身份清单。配置项为 FABRIC_ORGA_*、FABRIC_ORGB_*，通道、链码、合约分别由 FABRIC_CHANNEL、FABRIC_CHAINCODE、FABRIC_CONTRACT 设置。当前开发映射固定为 OrgA→Org1MSP、OrgB→Org2MSP；完整参数见 .env.example。

EVIDENCE_ANCHOR_REQUESTED 的 bizId 定位 chain_submission，消费者领取数据库租约后，先按 evidenceId/versionNo 查询链上记录。已有记录的证据编号、版本、摘要、提交机构与首笔交易号必须匹配才确认；摘要冲突进入不可自动重试的 FAILED。没有记录时才准备登记交易，先将原 transactionBytes、txId 与 PREPARED 尝试写入 MySQL，提交前持久化 SUBMITTING，提交返回后再保存 commitBytes 与 SUBMITTED。网络调用均在数据库事务外，检查点写入各自使用短事务。

确认先使用原 commitBytes 查询提交结果，再读取链上固定记录核对，得到可证明结果后将 chain_submission 改为 CONFIRMED。confirmedTxId 保留链上首次登记的 registeredTxId，confirmedBy 是记录中的提交 MSP。仅收到消息、发布确认或调用 submitPrepared 成功都不会直接表示存证成功。已存在相同业务记录时可复用首次结果，重复事件不会重新创建一次登记尝试。

提交或确认响应丢失时进入 UNKNOWN，保留 txId、transactionBytes、已有 commitBytes 和 nextActionAt。消费者只在结果或持久重试检查点提交后 ACK；独立恢复扫描器读取 UNKNOWN/SUBMITTED 等记录，有 commitBytes 优先恢复原确认请求，没有时恢复原交易并查询链上结果，不因超时创建新交易号。确定的 MVCC/幻读冲突可按策略重试，权限拒绝、摘要冲突、检查点不匹配等进入不可自动重试分支。完整故障矩阵、续租及业务补偿仍在 T023、T024、T027 继续验收。

新增两个只读接口，在 database 模式即可查询已经持久化的结果，统一使用 Bearer 令牌与 `/api/v1` 前缀：

| 方法与路径 | 返回 | 权限 |
| --- | --- | --- |
| GET /evidence-versions/{id} | 固定版本 metadata、完整 SHA-256、reviewStatus 和 anchor | 相应机构业务角色且是任务成员 |
| GET /evidence-versions/{id}/anchor | status、confirmedTxId、confirmedBy、retryCount、nextActionAt、安全 lastError，以及原 transactionId/attemptPhase/validationCode/blockNumber | 与版本详情一致 |

版本详情包含 id、evidenceId、versionNo、requirementId、taskId、subtaskId、executionId、filename、contentType、size、sha256、reviewStatus、createdAt、anchor。响应不包含 S3 对象键、下载链接、私钥、证书、transactionBytes 或 commitBytes。ORG_ADMIN 不自动具备证据读取权限；业务角色仍需任务成员身份。未知和不可访问版本统一返回 403，格式错误编号返回 400，匿名或失效身份返回 401。接口查询数据库状态，原文件与上传副本的实际字节核验已由下文 T013 接入。

手动调用见 [examples/t012.http](../examples/t012.http)。先按 T009～T011 创建真实任务并完成上传，从会话进度取得 versionId，再轮询 `/anchor`。COMPLETED 只表示文件定版，最终应以 anchor.status=CONFIRMED 与 confirmedTxId 判断链上登记是否已被核对。

真实验收需现有 MySQL、Garage、RabbitMQ、Fabric 网络和链码均正常；选择 JDK 21 后执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-anchor.py
```

探针启动隔离后端与随机 RabbitMQ 拓扑，通过真实 HTTP 创建任务和上传，再由两个实际消费者完成文件定版及 Fabric 存证。验证 MySQL 二进制检查点可通过 T006 的身份、通道、链码作用域校验，重新向真实 Peer 查询同一 commitBytes，并分别用 OrgA、OrgB 读取完全一致的五字段记录。故障分支在真实 queryCommit 已返回 VALID 后人为丢弃一次响应，检查 UNKNOWN 持久状态，再恢复原 commitBytes，确认尝试编号、交易号与两份二进制检查点保持不变，未执行新的背书或提交。重复消息、非法消息死信及版本接口权限一并验证。

探针要求无既有待派发 Outbox、VERIFYING 上传或待恢复存证，避免推动其他待处理工作。结束时仅删除本次账号、任务、SQL 关联记录、私有对象和随机消息拓扑，核对原十五张业务表及演示凭据未变，保留追加审计。Fabric 上已提交的证据记录永久保留，报告只记录 evidenceId、版本、摘要、交易号、块号及检查点长度和 SHA-256，不输出检查点原文。私有日志与报告位于 `.local/anchor-probe/<runId>/`（0600），`.local/anchor-probe/latest.json` 指向最近成功报告，执行结果见 [验证记录](../docs/verification.md)。2026-10-22 验证通过：后端 129 项测试；真实存证探针 45 次 HTTP 调用、407 项断言，完成 2 个真实链上版本与 2 条交易尝试，其中一次 UNKNOWN 使用原交易恢复成功；原十五张业务表及演示凭据保持不变。

## 存储文件、上传副本与核验历史（T013）

核验接口同时启用 database、storage、ledger，正常业务启动仍使用 `python3 deploy/scripts/run-backend.py --demo --ledger`。调用者必须是该版本所属任务的当前成员，并具本机构适用业务角色；ORG_ADMIN 单独不具核验权限。存储读取、摘要计算与链查询不持有数据库事务，结果写入前再读取并校验当前账号、tokenVersion、角色和成员关系。I/O 期间失去权限时返回 401/403，不保存或披露本次核验结果。

| 方法与路径 | 输入 | 返回 |
| --- | --- | --- |
| POST /evidence-versions/{id}/verifications?source=STORED | 无请求正文或 Content-Type | 核验私有原对象的真实字节，返回一条核验记录 |
| POST /evidence-versions/{id}/verifications?source=COPY | multipart/form-data，仅一个名为 file 的文件 part | 核验本次副本，不覆盖对象、不生成版本或存证事件 |
| GET /evidence-versions/{id}/verifications | page=1、pageSize=20，pageSize 最大 100 | 当前有版本权限的用户可读该版本核验历史，按 verifiedAt DESC、id DESC 排序 |

source 必须由 query string 明确指定且仅为 STORED 或 COPY，不从 JSON 正文读取。副本要求非空文件名、允许的扩展名与匹配 MIME，检查 JPEG/PNG/PDF/MP4 文件头；文件过大返回 413，类型或参数无效返回 400，不是 multipart 的 COPY 返回 415。COPY 保留文件签名检查，同时允许内容已变化的副本进入摘要比较，因此不会用完整媒体解码阻止 MISMATCH 结果。上传前先鉴权，再按配置限制并发接收和解析 multipart。

后端流式计算本次完整字节的 actualSha256/actualSize，再使用调用用户所属机构的真实 Gateway 查询固定 evidenceId/versionNo。OrgA 用户使用 OrgA 身份，OrgB 用户使用 OrgB 身份；核验不以 MySQL 保存的原摘要或本地 CONFIRMED 状态代替本次文件读取与链查询。即使本地仍为 PENDING，只要实际账本已有有效记录，也可得到 MATCH/MISMATCH；核验不会修复或改写本地存证状态。

| result | 含义 |
| --- | --- |
| MATCH | 实际字节摘要与取得的链上摘要相同 |
| MISMATCH | 实际字节摘要与取得的链上摘要不同；HTTP 200 表示核验已成功执行 |
| ANCHOR_UNCONFIRMED | 实际链查询成功但 found=false，尚不能取得登记结果 |
| FILE_READ_FAILED | 对象缺失、读取中断或文件处理超时；不保存任何部分摘要、长度或链字段 |
| CHAIN_QUERY_FAILED | 已完整读取文件，但链查询失败、超时或响应无效；保留实际摘要与长度，链字段为空 |

每次执行成功形成一条 verification_record，同时追加安全审计；这五种业务结果都返回 HTTP 200。记录字段为 id、versionId、userId、source、actualSha256、ledgerSha256、actualSize、ledgerTxId、result、reason、verifiedAt，不返回对象键、下载链接、SDK 错误原文、链身份证书或交易二进制检查点。输入错误、身份失效和权限拒绝不生成核验记录；历史接口每次重新执行相同的版本权限校验。

默认核验并发为 2、单次文件处理及链查询总时限为 60 秒、文件上限 200 MiB。工作线程使用无等待队列的有界执行器，忙时返回 503；超时取消并请求关闭流，实际仍占用的工作线程在退出前不会让出处理容量。COPY 的请求文件与处理临时文件放入受限本地目录，处理文件权限为 0600，在 Worker 退出时清理；超时后仍阻塞的 Worker 继续占用处理容量，临时文件等待其退出或后续清理任务回收。HTTP multipart 接收由独立容量限制保护；传输与业务处理分别受连接超时和业务时限约束。

参数为 VERIFICATION_MAX_FILE_SIZE、VERIFICATION_CONCURRENCY、VERIFICATION_TIMEOUT_SECONDS、VERIFICATION_TEMP_DIRECTORY、VERIFICATION_MIN_FREE_SPACE、VERIFICATION_ALLOWED_EXTENSIONS，完整配置见 .env.example。直接运行 JAR 时需要自行提供环境变量，启动器显式读取已有 .env 中的 VERIFICATION_*；不修改原 .env 或演示凭据。

手动流程见 [examples/t013.http](../examples/t013.http)。沿用 T010 上传的 79 字节示例 PNG，先核验原对象与同内容副本，再按示例命令在 `.local/` 生成只改变一个字节的副本，验证 MISMATCH。副本的本地变更不会修改正式文件。

真实验收需要已有 MySQL、Garage、RabbitMQ、Fabric 网络及链码正常，选择 JDK 21 后执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-verifications.py
```

探针通过正式 HTTP 和实际文件、消息消费者生成一个真实已存证版本，验证 STORED/COPY 匹配、单字节副本不匹配及正式对象和业务记录不变。额外检查实际对象内容变化后重新计算摘要、缺失对象后恢复、真实对象流读出前缀后中断、真实链查询响应被故意丢弃、本地 PENDING 与实际账本匹配，以及另一版本确实尚未登记的 ANCHOR_UNCONFIRMED。测试只对自己的对象和状态做临时修改；不会返回伪造的链上成功响应。

探针还验证两机构实际 Gateway 调用计数、分页与当前权限、输入拒绝，以及在真实对象读取期间撤销成员关系后不记录结果。测试开始要求无既有待处理工作；结束仅清理本次账号、任务、核验记录、对象和随机消息拓扑，比较原十六张业务表及演示凭据不变，保留追加审计与已提交的真实账本记录。报告不包含凭据、对象键或检查点原文，位于 `.local/verification-probe/<runId>/`（0600），最近成功报告由 `.local/verification-probe/latest.json` 指向，结果见 [验证记录](../docs/verification.md)。2026-10-23 验证通过：后端 148 项测试；真实核验探针 62 次 HTTP 调用、588 项断言，保存 10 条核验记录并覆盖五种结果，原十六张业务表及演示凭据保持不变。

## 保留并重查最小真实闭环（T014）

T014 使用已打包的后端 JAR 和既有演示账号，通过正式 HTTP 完成登录、创建任务、资料要求、分配、成员、子任务、初始执行与启动，再上传 [79 字节 PNG](../examples/files/inspection-sample.png)、提交完成、等待文件定版和真实 Fabric 确认，最后执行原对象 MATCH、同内容副本 MATCH、单字节变更副本 MISMATCH 并读取历史。STORED 使用 OrgA 的 owner-admin，两个 COPY 使用 OrgB 的 operator-user，分别执行各自机构的实际链查询。

先确保 MySQL、Garage、RabbitMQ、Fabric 网络和 evidence 链码已就绪，原 .env、演示凭据和 User1 身份路径已存在，并停止其他正在运行的项目后端或真实业务探针。选择 JDK 21，在项目根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-mvp.py
```

脚本启动自己的 JAR 进程并使用随机本机端口和独占随机 RabbitMQ 拓扑，沿用正常默认启用的 Outbox 发布、文件消费、存证消费和恢复扫描。业务 POST 只提交一次；异步阶段只轮询正式查询接口，不直接调用 Service/Mapper、不手工修改成功状态。HTTP 202 仅表示接受完成请求，之后分别等待上传 COMPLETED 和存证 CONFIRMED。

启动前通过本地文件锁、实际 Java 进程检查、RabbitMQ 消费者检查及只读数据库 Inspector 拒绝并发后台实例和既有待处理工作；不接入共享队列。启动后确认自己两个消费者各一个、manual ACK、prefetch=1。Inspector 不启动 Spring 或 Worker，只读 MySQL、实际对象和两机构账本，校验完整任务关联、唯一版本与交易检查点、两个事件及消费回执、真实字节摘要与账本、三条核验记录，并核对启动前十六张业务表的全部旧行未变。

本脚本与此前隔离清理探针的用途不同：任务、成员、上传、私有分片、正式对象、固定版本、核验、审计及真实账本记录全部保留，可直接用于面试演示。结束时停止自己启动的应用，只删除已确认无消息且无消费者的自有随机消息拓扑；共享服务与既有队列保留。任务保持 RUNNING、reviewStatus=PENDING，不提前实现人工复核或任务验收完成。

运行目录为 `.local/mvp-demo/<runId>/`：`report.json` 为阶段、HTTP 与断言结果；`resources.json` 保存安全资源编号和摘要；`preflight.json` 保存原行快照摘要；`inspector-input.json`、`inspection.json` 为只读交叉核对输入与结果；`copy-original.png`、`copy-modified.png` 为实际核验副本。应用与 Inspector 日志分别为 `application.log`、`inspector.log`。目录权限为 0700，报告、日志和副本为 0600，不保存密码、JWT、对象键或交易检查点原文。成功后 `.local/mvp-demo/latest.json` 指向最近目录。

对同一条成功演示重新读取和核验：

```bash
python3 deploy/scripts/verify-mvp.py --recheck .local/mvp-demo/REPLACE_WITH_SUCCESSFUL_RUN_ID
```

recheck 保留原目录，在其 `rechecks/<recheckId>/` 下写新报告。它启动完整 profile 组合和全新独占空拓扑，以进程专属配置关闭 Outbox 发布及两类恢复扫描，只执行现有任务、上传、版本、存证和历史查询，再追加三条实际核验，不创建任务、上传、文件版本、交易或链记录。初始 preflight 保持不变，同时采集本次重查前快照，Inspector 仅允许同版本新增核验记录，校验所有既有行、原交易及正式对象未变。

脚本不覆盖 .env 或演示凭据，不在结束时删除保留的业务成果。执行失败时写阶段报告并保留已取得的资源编号，不盲目重试未知结果的业务 POST；先根据私有日志及资源进度处理未完成阶段，再决定后续动作。完整演示步骤与验收证据见 [M2 演示说明](../docs/m2-demo.md) 和 [验证记录](../docs/verification.md)。

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
| `config` | UTC Clock、database Mapper 扫描、local demo 初始化及 prod 防护 |
| `system` | 已实现只读状态接口 |
| `identity` | 已实现机构/用户 Mapper、JWT 登录校验、退出、角色 CAS 及任务成员/业务角色权限校验 |
| `inspection` | 已实现任务列表/详情、草稿与要求、分配、成员维护、子任务、READY 初始执行及启动 |
| `device` | 设备 Mapper/演示设备，任务分配及启动校验；设备管理和上报接口在后续实现 |
| `evidence.storage/upload/finalization/verification` | 已实现私有 S3、幂等分片与定版、实际原文件/副本核验、有界处理及核验历史 |
| `review`、`sharing` | 人工复核及授权；预留包边界 |
| `blockchain` | 已提供两机构 Gateway、存证消费、MySQL 二进制尝试检查点、原交易恢复及版本/存证状态读取 |
| `audit` | V2 审计表及成功操作追加 Mapper；查询接口随业务任务接入 |
| `messaging` | 已实现 Outbox 租约派发、Confirm + mandatory Return、manual ACK、消费幂等、基础重试扫描及持久死信 |

预留包不含模拟业务实现。MyBatis Starter 4.0.0、Flyway 12.4.0、Connector/J 9.7.0 和 MySQL 8.4.11 已通过 T007；对象存储使用固定 AWS SDK 2.56.0，RabbitMQ 适配使用 Spring Rabbit 4.1.1；缓存客户端随对应任务接入。Spring Boot 父 POM 固定为 4.1.1；Gateway 固定为 1.13.0，gRPC BOM 1.84.0 与 Protobuf BOM 4.36.2 显式管理。默认启动没有 Fabric Bean，也不会读取链身份或连接网络。

## Java Gateway 真实探针（T006）

前提是 T004 网络、mychannel 和 T005 evidence 链码已经启动。在项目根目录、选择 JDK 21 后执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P fabric-probe clean verify
python3 deploy/fabric/verify-gateway.py
```

Maven 的 fabric-probe profile 只生成探针 classpath；构建运行后端测试，不自动向链提交交易。Python 启动器才执行真实链调用，每次创建一个独立运行目录，并依次启动四个 JVM：

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

可复用接口为 EvidenceLedgerClient，生产适配为 GatewayEvidenceLedgerClient。探针位于 test 源码，不进入可执行应用 JAR，不提供 HTTP 写入口。T006 已实测通过，具体证据见 [验证记录](../docs/verification.md)。M1、M2 已通过，T014 保留式演示及同版本复查均已实际执行；下一项为 T015。
