# 后端工程

Spring Boot 4.1.1、Java 21、Maven 模块化单体。提供身份与资源权限、多机任务、分片续传与固定版本、人工复核、授权共享、真实 Fabric 存证/核验，以及 Outbox、租约和扫描恢复。0.1.0 MVP 的 M0～M5、T001～T034 已完成，完成日期为 2026-10-29；当前版本、产物绑定和限制见[交付说明](../docs/delivery.md)。

T034 最终交付核对已完成。本文各任务小节保留该阶段的验证数字、运行证据和当时范围，旧段落中的“后续任务”“尚未完成”应按该阶段日期理解；当前交付状态、版本和已知限制统一见[交付说明](../docs/delivery.md)。

## 构建与运行

在项目根目录先选择 JDK 21，确认 `mvn -version` 的 Java 版本，再执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml clean verify
java -jar backend/target/inspection-backend-0.1.0.jar
```

构建启用 `deprecation`、`unchecked` 编译检查，编译警告会使构建失败；生产代码和测试代码均需通过。VS Code Java 扩展另检查未使用代码及资源生命周期，工作区配置已排除 `.local` 中的缓存和部署复现副本。更新配置后可执行 `Java: Clean Java Language Server Workspace` 重新导入。临时目录游标的资源所有权交接仅在 `TemporaryFileManager.openCursor` 局部抑制已核实的分析器误报，由目录文件描述符及关闭测试继续验证。

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

最后一个命令启动实际 Web 后端，Ctrl+C 停止。启动器默认启用 local,database,storage,messaging；--demo 再加入 demo，--profile prod 则使用 prod,database,storage,messaging，不能与 --demo 合用。`--cleanup` 显式追加清理 profile，普通 local/prod 启动均不自动启用清理。启动器显式读取已有 .env 中的 DB_*、S3_*、UPLOAD_*、VERIFICATION_*、CLEANUP_*、RABBITMQ_*、MESSAGING_*、JWT_SECRET 及 DEMO_CREDENTIALS_PATH，通过进程环境传给 Java，不执行 .env 中的 shell 表达式。首次环境准备见 [部署说明](../deploy/README.md)；继续开发时沿用原 .env 和演示凭据，不重新生成或覆盖它们，缺少的新配置按 .env.example 在本地补齐。

storage 需要显式提供 S3_ENDPOINT、S3_ACCESS_KEY、S3_SECRET_KEY，使用 T003 已配置的 Garage 私有桶。移除 --demo 只关闭演示初始化，仍启用存储和消息处理。若只验证数据库、认证和任务，可自行提供 DB_*、JWT_SECRET 等进程环境，使用 `SPRING_PROFILES_ACTIVE=local,database java -jar backend/target/inspection-backend-0.1.0.jar`；此组合不创建 S3 客户端，也不提供上传接口。直接运行 JAR 不会自动读取 .env。

database profile 启用 Hikari、MyBatis 与 Flyway。连接会话使用 UTC 和 READ COMMITTED，Connector/J 默认 sslMode=REQUIRED；生产部署可通过 DB_SSL_MODE 配置更严格的证书校验。普通 local/prod 启动不启用 database 时排除这些自动配置，原 HTTP/Gateway 探针可独立运行。MyBatis SQL 参数日志关闭，local-cache-scope=STATEMENT，锁后权限与成员关系重查不会复用同事务中较早的 SESSION 缓存。

首版迁移 V1__identity_and_task_scope.sql 建立 organization、app_user、inspection_task、task_member、drone 五张表，并创建两个固定机构。roles JSON、机构角色白名单、唯一用户名、创建人机构、设备摘要及外键由数据库约束；username 区分大小写。T008 新增 V2__identity_audit.sql 建立 audit_log；T009 新增 V3__task_execution_scope.sql 建立 inspection_subtask、material_requirement、task_execution。V3 的复合外键限制资料项与子任务属于同一任务、currentExecutionId 属于当前子任务，并以唯一约束限制一个子任务只有一个 READY/RUNNING 批次。T010 新增 V4__upload_sessions_and_versions.sql，建立 upload_session、upload_part、evidence_version；复合外键固定资料项、证据编号、任务、子任务和执行作用域，唯一约束保证主体请求键、分片号和未来版本号不重复。T010 当时不插入 evidence_version；T011 的 V5__finalization_outbox_and_submission.sql 新增 outbox_event、consumed_event、chain_submission，提供事件派发、消费回执和待存证状态。T012 追加 V6__chain_attempts.sql，建立 chain_attempt 保存原 Fabric 交易与确认请求检查点；T013 追加 V7__verification_records.sql，建立 verification_record 保存实际摘要、链上摘要、核验来源、结果及原因；T015 追加 V8__device_reports.sql，建立设备事件去重与执行归属约束；T016 追加 V9__execution_adjustments.sql，保存换机前后批次、操作者、原因及数据库时间，T018 追加 V10__issues_and_reviews.sql，建立固定版本异常 issue 与唯一结论 review_record；T020 追加 V11__version_access_grants.sql，新增精确版本共享 access_grant；T022 追加 V12__durable_message_rejections.sql，扩展 Outbox 的 FAILED 约束并新增安全拒收记录 message_rejection；T024 追加 V13__anchor_retry_requests.sql，仅新增人工重新检查申请表 anchor_retry_request，V1～V12 历史迁移及旧表列保持不变；T025 追加 V14__storage_cleanup_tasks.sql，建立持久对象清理任务 storage_cleanup_task。当前共 25 张应用表，其中 24 张纳入业务快照，audit_log 另行追加保留。Flyway 校验开启，自动 baseline 和 clean 关闭，不使用 schema.sql/data.sql；后续结构变化继续追加迁移。

demo 必须同时包含 local、database，任何包含 prod 的组合都会被应用自身拒绝。初始化在一个事务中创建以下账号与一台 UAV-DEMO-001，不预造任务、成员、文件或成功状态：

| 用户名 | 机构 | 初始角色 |
| --- | --- | --- |
| owner-admin | OrgA | ORG_ADMIN、OWNER_TASK_ADMIN |
| owner-reviewer | OrgA | OWNER_REVIEWER |
| owner-approver | OrgA | OWNER_DATA_APPROVER |
| operator-admin | OrgB | ORG_ADMIN、OPERATOR_TASK_ADMIN |
| operator-user | OrgB | OPERATOR_USER |
| operator-outsider | OrgB | OPERATOR_USER，预留无任务范围用例 |

随机密码和设备令牌只保存在忽略提交的 .local/demo-credentials.json（0600）；脚本不打印它们，也不覆盖已有文件。数据库保存 cost=12 的 BCrypt 摘要和设备令牌 SHA-256。重复初始化核对固定身份和原凭据，保留合法的已修改角色、版本、启用状态及其他业务数据；不会把角色恢复为上表初始值。固定身份或凭据不匹配时拒绝覆盖。应保留原凭据文件，不能靠删除它来重置账号。登录接口及 T015 设备认证均已接入；设备身份与用户 JWT 独立。

T007 当时的真实验证使用数据库探针三个独立进程：migrate 不启用 demo；seed 初始化并核对 Mapper 结果；verify 再次启动，比较五张表全部字段的摘要及凭据文件，执行 24 项约束拒绝检查和 UTC 时间往返。所有临时任务、成员及字段修改均回滚，验证后为 2 个机构、6 个用户、0 个任务/成员和 1 台设备。JVM 使用 Asia/Shanghai，固定毫秒时间仍按 UTC 保存和读取。

数据库探针现已支持 V1～V14，比较十四版迁移校验和及二十四张业务表完整快照，包含 248 项真实约束检查及完成状态 CAS、拒收去重、明确冲突预算、人工申请、清理任务租约/重试和 UTC 往返；探针写入均回滚，实际 S3 删除由独立清理探针验证。最新成功报告由 .local/database-probe/latest.json 指向，T007 的 24 项约束及五表历史证据继续保留在 [验证记录](../docs/verification.md)。需要再次检查时执行 `python3 deploy/scripts/verify-database.py`。探针要求当前演示基线，后续业务数据变化时需更新相应验收用例。后端测试和显式真实探针分别执行，普通 Maven 构建不自动连接 MySQL、S3 或提交链交易。

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

初次分配仅限 ASSIGNED：创建一个 READY 批次并设置子任务 currentExecutionId，同时增加子任务和任务版本。已有当前批次或版本过期返回 409，不产生重复执行；未知、停用或归属不符的初始设备返回 400。T016 已实现运行中换机：同一路由在 RUNNING / WAITING_ACCEPTANCE 下使用子任务 CAS，必须填写原因并选择不同的启用设备，旧批次 CLOSED、新批次 RUNNING/progress=0；真实集成验收见下文。

启动前必须至少有一个子任务，每个子任务至少绑定一个 required=true 的资料项，所有资料均已绑定本任务子任务，每个子任务都有当前 READY 批次且设备仍启用、归属正确。不满足条件返回 409；包括只有可选资料、仍有未绑定资料或 READY 设备被停用。启动复用原批次 id，将原批次变为 RUNNING，写 UTC startedAt，并增加任务、子任务及执行版本，不重新插入批次。重复启动或陈旧 expectedLockVersion 返回 409。

所有写操作与成功审计使用同一数据库事务。已有任务先取得父任务行锁，随后处理子任务和资料；初次分配锁定所选设备，启动按去重后的设备编号排序获取行锁，避免不同任务以相反次序锁同一组设备。T009 的审计动作共七类：TASK_CREATED、TASK_REQUIREMENTS_UPDATED、TASK_ASSIGNED、TASK_MEMBERS_UPDATED、SUBTASK_CREATED、EXECUTION_CREATED、TASK_STARTED；除 EXECUTION_CREATED 关联 TASK_EXECUTION 外，其余关联 INSPECTION_TASK。T016 换机追加 EXECUTION_ADJUSTED，关联新 TASK_EXECUTION。审计只保存安全元数据，CAS 或审计失败回滚本次变更。

手动执行 [examples/t009.http](../examples/t009.http) 可从登录开始创建并保留一条真实任务，供后续上传示例使用。真实验收使用隔离测试账号、设备及任务，MySQL 和 JWT 配置沿用上述启动方式：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-tasks.py
```

探针以真实 HTTP 和 MySQL 验证任务流程、权限、成员范围、绑定、初次执行及启动条件，保留审计证据并清理自己的关联测试数据；不会清空原业务表或更改演示凭据。报告与私有日志位于 `.local/task-probe/<runId>/`，`.local/task-probe/latest.json` 指向最近成功报告。后端测试与真实验收结果以 [验证记录](../docs/verification.md) 为准；T010 已接入下文上传能力，固定版本已在 T011 接入，T012 已提供真实链存证与基础恢复；T014 将这些正式接口串成可保留的演示任务。

## 私有对象存储与上传会话（T010）

上传接口要求同时启用 database、storage，统一使用 Bearer 令牌和 `/api/v1` 前缀。当前支持用户上传：调用者必须有 OrgB 的 OPERATOR_USER 角色、属于任务成员，读取或写入已有会话还必须是该会话创建者。只有 OPERATOR_TASK_ADMIN 或 ORG_ADMIN 不具上传权限；另一位任务成员也不能读取别人的会话。T015 的设备凭据上传使用独立的 /device/upload-sessions 路径，见下文。

| 方法与路径 | 请求 | 返回 |
| --- | --- | --- |
| POST /upload-sessions | JSON：clientRequestId、requirementId、executionId、filename、contentType、size | 会话进度，包含 uploadId、固定分片规格、原 expiresAt、已提交分片和 uploadedSize |
| PUT /upload-sessions/{id}/parts/{partNo} | Content-Type: application/octet-stream，正文直接传分片原始字节 | uploadId、partNo、实际 size、服务端计算的 sha256 |
| GET /upload-sessions/{id} | 无正文 | 从 MySQL 读取的会话进度，uploadedParts 按分片号排列 |

编号必须是规范小写 UUID；clientRequestId 表示一次逻辑上传，重试时保留原值。filename 只能是文件名，长度不超过 255，不能包含路径或控制字符。contentType 新增为必填字段，必须与允许的文件扩展名匹配；支持 JPEG、PNG、PDF、MP4，首片再检查文件签名与声明是否一致。size 是声明的完整文件字节数，必须为正且不超过配置上限。服务固定资料项与执行批次的作用域，并要求当前任务、子任务和批次允许上传，不能借其他任务编号或他机批次写入。当前 RUNNING 批次可以正常上传；原分配设备或有权限的用户可对同任务原 CLOSED 批次新建、续传和完成会话，固定版本保留原执行编号，只作为历史资料。

默认文件上限 200 MiB、分片大小 8 MiB、会话有效期 24 小时。partNo 从 1 开始，partCount=ceil(size/partSize)；除最后一片外必须恰好为 partSize，最后一片必须恰好为剩余长度。支持先传后续分片，再按 GET 返回的已提交分片补传缺失部分；正文使用原始字节，不使用 multipart/form-data。短片返回 400、超长片返回 413，格式或文件签名不符返回 400。

幂等键为 actorType、actorId、clientRequestId。同键且全部创建参数相同，返回原会话及原 expiresAt，不延长有效期；参数不同返回 409 IDEMPOTENCY_CONFLICT。同一分片号、相同长度和摘要返回原分片，不重复插入或记录成功审计；内容不同返回 409 PART_CONTENT_CONFLICT。并发提交仍只保留一条会话或分片记录。新会话和新分片分别记录 UPLOAD_SESSION_CREATED、UPLOAD_PART_STORED 安全审计。

过期会话不能继续写片，返回 409 UPLOAD_SESSION_EXPIRED；VERIFYING 等冻结状态返回 409 UPLOAD_NOT_WRITABLE，已完成任务返回 409 TASK_COMPLETED。GET 仍可查看进度，尚为 UPLOADING 但已到期的会话对外显示 EXPIRED。每次写入在数据库锁下重新核对账号、成员、会话所有者及执行归属。成员移除、设备禁用或其他权限变化会阻断写入；人工换机后的原 CLOSED 会话可受控续传，不能修改所属执行，也不能借补传覆盖当前批次资料。

S3 客户端固定使用 AWS SDK 2.56.0、UrlConnectionHttpClient，默认采用 path style。请求和响应 checksum 策略均设为 WHEN_REQUIRED，以兼容 Garage；应用自己计算并持久化每片 SHA-256。客户端通过 RequestBody.fromFile 重开稳定文件，重试不依赖已消费的流。S3_ENDPOINT、S3_ACCESS_KEY、S3_SECRET_KEY 必须显式提供；S3_REGION 默认 garage，S3_BUCKET 默认 uav-evidence，S3_FORCE_PATH_STYLE 默认 true。上传限制通过 UPLOAD_MAX_FILE_SIZE、UPLOAD_PART_SIZE、UPLOAD_SESSION_TTL_SECONDS、UPLOAD_ALLOWED_EXTENSIONS 配置；临时目录默认 `.local/uploads/tmp`，可用 UPLOAD_TEMP_DIRECTORY 和 UPLOAD_MIN_FREE_SPACE 调整目录及最低剩余空间。

HTTP 接收分片时使用 0600 临时文件，按长度上限流式读取、计数和计算摘要；S3 网络写入在数据库事务外进行，每个候选对象使用独立服务端键。短事务重新验证作用域并提交分片引用，已确认未引用的候选对象删除。数据库提交结果不明时保留对象，避免删掉可能已提交的引用；孤儿扫描与引用保护由下述 T025 实现。私有桶不开放匿名读取，上传响应和审计不返回 objectKey、私钥、S3 凭据或 JWT；对象存储故障统一返回 503。

完成所有分片后仍为 UPLOADING，versionId=null；调用下文 T011 完成接口后进入异步校验和定版。仅启用 database,storage 可继续使用 T010 会话与分片接口；完成接口及 Worker 还需要 messaging。T016 已实现历史批次隔离，T017 多分片与版本归档已验收通过，孤立对象清理由下述 T025 实现，完整故障矩阵由 T027 验收；单片上传到真实核验的闭环见下文 T014。

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

内容校验失败进入 FAILED，不创建版本或存证记录，修正文件后应创建新上传会话。对象存储或本地 I/O 暂时失败保持 VERIFYING，事务保存 retryCount、nextActionAt、安全错误码和 DONE 回执，再 ACK；独立扫描器读取这些持久检查点重试，延迟为 2、10、30、60、300 秒。数据库未能提交结果或检查点时不 ACK；T022 进一步要求明确持久 handoff，事务外固定退避 5 秒后关闭通道重投。格式错误消息在安全拒收元数据提交后进入持久死信队列 inspection.dead，详情见 T022。

GET 上传进度在 COMPLETED 时返回 versionId，其余状态为 null。COMPLETED 表示固定文件版本生成；仅运行 T011 的 database,storage,messaging 组合时，anchor 消息保留在队列中，chain_submission 仍为 PENDING；增加 ledger profile 后启用下文 T012 真实存证消费者。T023 的处理租约续期及有界恢复扫描已通过验收；完整恢复矩阵由 T027 验收，引用感知孤儿清理见下述 T025。当前提交结果不明时保留最终候选对象。

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

提交或确认响应丢失时进入 UNKNOWN，保留 txId、transactionBytes、已有 commitBytes 和 nextActionAt。消费者只在结果或持久重试检查点提交后 ACK；独立恢复扫描器读取 UNKNOWN/SUBMITTED 等记录，有 commitBytes 优先恢复原确认请求，没有时恢复原交易并查询链上结果，不因超时创建新交易号。确定的 MVCC/幻读冲突可按策略重试，权限拒绝、摘要冲突、检查点不匹配等进入不可自动重试分支。T023 的续租及恢复扫描、T024 的明确失败预算及人工重新检查均已通过验收，完整跨系统故障矩阵继续由 T027 验证。

新增两个只读接口，在 database 模式即可查询已经持久化的结果，统一使用 Bearer 令牌与 `/api/v1` 前缀：

| 方法与路径 | 返回 | 权限 |
| --- | --- | --- |
| GET /evidence-versions/{id} | 固定版本 metadata、完整 SHA-256、reviewStatus 和 anchor | 任务业务成员，或 T020 的有效精确版本共享授权 |
| GET /evidence-versions/{id}/anchor | status、confirmedTxId、confirmedBy、retryCount、nextActionAt、安全 lastError，以及原 transactionId/attemptPhase/validationCode/blockNumber | 与版本详情一致 |

版本详情包含 id、evidenceId、versionNo、requirementId、taskId、subtaskId、executionId、filename、contentType、size、sha256、reviewStatus、createdAt、anchor。响应不包含 S3 对象键、下载链接、私钥、证书、transactionBytes 或 commitBytes。ORG_ADMIN 不自动具备证据读取权限；任务路径仍要求业务角色和成员身份，T020 的有效精确版本共享提供独立读取路径。未知和不可访问版本统一返回 403，格式错误编号返回 400，匿名或失效身份返回 401。接口查询数据库状态，原文件与上传副本的实际字节核验已由下文 T013 接入。

手动调用见 [examples/t012.http](../examples/t012.http)。先按 T009～T011 创建真实任务并完成上传，从会话进度取得 versionId，再轮询 `/anchor`。COMPLETED 只表示文件定版，最终应以 anchor.status=CONFIRMED 与 confirmedTxId 判断链上登记是否已被核对。

真实验收需现有 MySQL、Garage、RabbitMQ、Fabric 网络和链码均正常；选择 JDK 21 后执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-anchor.py
```

探针启动隔离后端与随机 RabbitMQ 拓扑，通过真实 HTTP 创建任务和上传，再由两个实际消费者完成文件定版及 Fabric 存证。验证 MySQL 二进制检查点可通过 T006 的身份、通道、链码作用域校验，重新向真实 Peer 查询同一 commitBytes，并分别用 OrgA、OrgB 读取完全一致的五字段记录。故障分支在真实 queryCommit 已返回 VALID 后人为丢弃一次响应，检查 UNKNOWN 持久状态，再恢复原 commitBytes，确认尝试编号、交易号与两份二进制检查点保持不变，未执行新的背书或提交。重复消息、非法消息死信及版本接口权限一并验证。

探针要求无既有待派发 Outbox、VERIFYING 上传或待恢复存证，避免推动其他待处理工作。结束时仅删除本次账号、任务、SQL 关联记录、私有对象和随机消息拓扑，核对原十五张业务表及演示凭据未变，保留追加审计。Fabric 上已提交的证据记录永久保留，报告只记录 evidenceId、版本、摘要、交易号、块号及检查点长度和 SHA-256，不输出检查点原文。私有日志与报告位于 `.local/anchor-probe/<runId>/`（0600），`.local/anchor-probe/latest.json` 指向最近成功报告，执行结果见 [验证记录](../docs/verification.md)。2026-10-22 验证通过：后端 129 项测试；真实存证探针 45 次 HTTP 调用、407 项断言，完成 2 个真实链上版本与 2 条交易尝试，其中一次 UNKNOWN 使用原交易恢复成功；原十五张业务表及演示凭据保持不变。

## 存储文件、上传副本与核验历史（T013）

核验接口同时启用 database、storage、ledger，正常业务启动仍使用 `python3 deploy/scripts/run-backend.py --demo --ledger`。任务路径调用者须为当前业务成员；T020 也允许持有效精确版本授权的注册用户核验该版本，ORG_ADMIN 不会自动获得额外权限。存储读取、摘要计算与链查询不持有数据库事务，结果写入前再读取并校验当前账号、tokenVersion 及本次任务或共享权限。I/O 期间失去权限时返回 401/403，不保存或披露本次核验结果。

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

## 设备管理、独立认证与上报（T015）

设备管理由 database profile 提供，要求 OrgB 的 OPERATOR_TASK_ADMIN；ORG_ADMIN 单独不获得设备管理权限。创建只接受 code，服务端固定 operatorOrgId，生成 64 位小写十六进制 deviceToken，仅在创建响应中返回一次。MySQL 保存令牌 SHA-256，后续列表和详情只返回安全设备信息；重复代码返回 409，不通过再次创建取回令牌。

| 方法与路径 | 请求及返回 |
| --- | --- |
| POST /api/v1/drones | `{code}`；返回 `{device,deviceToken}`，deviceToken 应立即保存在本地安全位置 |
| GET /api/v1/drones | page、pageSize；返回 items/page/pageSize/total |
| GET /api/v1/drones/{id} | 返回 id、operatorOrgId、code、enabled、lastSeenAt、online、lockVersion、createdAt、updatedAt |
| PATCH /api/v1/drones/{id}/enabled | enabled、expectedLockVersion；CAS 冲突返回 409，成功递增版本 |

设备请求使用 `X-Device-Id` 与 `X-Device-Token`，不使用用户 JWT。账号角色不能充当设备身份，设备令牌也不能访问用户、复核或共享接口。每次设备操作读取 MySQL 确认设备存在、启用且摘要匹配；停用后立即失效，包括重放旧事件。设备认证失败返回 401 和 `WWW-Authenticate: Device`，用户接口仍使用 Bearer。

`POST /api/v1/device/reports` 接收 bootId、seq、eventId、executionId、type；PROGRESS 还必须提供 0～100 的 progress，HEARTBEAT 不接受 progress。UUID 必须规范小写，seq 必须为 JSON 整数，范围为 0～9007199254740991。设备可上报分配给自己的当前 RUNNING 批次；同一活动任务中，原设备也可对非当前 CLOSED 批次记录历史事件并刷新自身在线时间，旧执行与当前执行的 progress 均保持不变。他机或不可访问执行返回 403，其他不允许状态返回 409。

事件去重键为 `(droneId,bootId,seq)` 和 `(droneId,eventId)`。相同键、相同内容返回原 id、receivedAt、progress 与当时的 executionProgress 快照，并将 duplicate 标为 true；不插入事件、不刷新 MySQL lastSeenAt 或 Redis TTL。相同键而内容不同返回 409 IDEMPOTENCY_CONFLICT。新 bootId 可以从 seq=0 开始；同一执行的 progress 只取更大值，即使设备重启或较低进度迟到也不会回退。进度到 100 仍保持任务与执行 RUNNING，不代替人工复核或任务完成。

lastSeenAt 使用服务端数据库 UTC 接收时间。默认 20 秒内且设备启用时 online=true，到达阈值自动显示离线，不需要后台写入“离线”状态。Redis 只缓存已提交的毫秒时间，Lua 仅接受更大时间且设置 TTL，避免乱序写回退。设备元数据仍取 MySQL；无效、陈旧或未来缓存值不改变数据库事实，Redis 不可达时读取和上报继续使用 MySQL。参数为 REDIS_HOST/PORT/PASSWORD、DEVICE_ONLINE_THRESHOLD_SECONDS、DEVICE_PRESENCE_CACHE_TTL_SECONDS、DEVICE_PRESENCE_KEY_PREFIX，见 .env.example。

设备上传要求 database、storage，使用相同设备头调用 `/api/v1/device/upload-sessions`：POST 创建、GET /{id} 查询、PUT /{id}/parts/{partNo} 上传原始字节。参数和分片规则与用户上传一致，主体由服务端固定为 DEVICE，设备必须属于该执行，只有会话创建设备能读取或写片；用户会话与设备会话分别鉴权。启用 messaging 后才提供设备完成接口 POST /{id}/complete，后续定版复用既有流程；无 messaging 时上传保持 UPLOADING，不生成固定版本。

手动示例见 [examples/t015.http](../examples/t015.http)：先注册一台新设备，再把返回的设备 id 用于 T009 创建并启动一条新任务，保留原 M2 演示任务及其设备。设备令牌只在本地 REST Client 环境中使用，不写入提交文件或日志。

真实验证需 MySQL、Redis、Garage 正常，选择 JDK 21 后执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-devices.py
```

探针使用隔离账号，通过正式接口注册设备、创建任务、分配并启动执行，验证管理权限与 CAS、独立认证、设备范围、幂等冲突、并发重试、原结果重放、单调进度及 100 不完成任务。使用真实 Redis 检查时间值、TTL 与重复不续期；另一临时应用连接不可达端口验证回 MySQL，并通过独占缓存命名空间检查实际在线阈值。设备单片上传实际写入私有 Garage，并核对字节与摘要。

探针仅启用 local,database,storage，不启动 RabbitMQ Worker 或 Fabric 客户端，不处理保留的 M2 数据。结束只删除本次账号、任务、设备、事件、上传对象和专属 Redis 键，比较原十七张业务表全部字段及原演示凭据不变，保留追加审计。私有日志、报告位于 `.local/device-probe/<runId>/`（0600），最近成功报告由 `.local/device-probe/latest.json` 指向，实际结果见 [验证记录](../docs/verification.md)。2026-10-23 验证通过：后端 185 项测试；真实设备探针 79 次 HTTP 调用、606 项断言，验证 7 条去重后的设备事件；原十七张业务表、两个保留的 M2 任务及演示凭据保持不变。

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
| `inspection` | 已实现任务列表/详情、草稿与要求、分配、成员维护、初始执行与启动、CAS 换机、执行历史、当前批次汇总、提交验收/完成及固定版本关系分页 |
| `device` | 已实现设备管理/CAS、独立设备认证、事件去重、单调进度及 Redis presence 回源 |
| `evidence.storage/upload/finalization/verification` | 已实现私有 S3、幂等分片与定版、实际原文件/副本核验、有界处理及核验历史 |
| `review` | 已实现固定版本异常、人工结论 CAS、分页权限和原子审计 |
| `sharing` | 已实现精确版本申请、审批/撤销 CAS、期限鉴权、安全元数据分页及短期下载签发 |
| `blockchain` | 已提供两机构 Gateway、存证消费、MySQL 二进制尝试检查点、原交易恢复及版本/存证状态读取 |
| `audit` | 追加成功审计、按多态资源确定任务归属的权限分页与精确过滤 |
| `messaging` | 已实现 Outbox 租约派发、Confirm + mandatory Return、manual ACK、消费幂等、处理续租、有界业务恢复扫描及持久死信 |

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

可复用接口为 EvidenceLedgerClient，生产适配为 GatewayEvidenceLedgerClient。探针位于 test 源码，不进入可执行应用 JAR，不提供 HTTP 写入口。T006 已实测通过，具体证据见 [验证记录](../docs/verification.md)。M1、M2 已通过，T014 保留式演示及同版本复查均已实际执行；T015 设备管理、独立上报与设备上传已接入；T016 集群模拟、换机及历史批次隔离已通过真实验收；T017 多分片续传与独立版本归档也已验收通过；T018 异常与人工复核已实现，见下文。


## 集群模拟、换机与历史资料验收（T016）

`simulator/swarm.py` 使用 Python 标准库，通过正式设备 HTTP 接口并行模拟 3～5 台设备。默认每 5 秒上报，支持心跳、单调进度、PNG 单片上传、原事件重放和指定轮次断网。发送前持久化待确认的 bootId/seq/eventId、上传 clientRequestId 及传片阶段；重启后先重试原请求，再使用本次新 bootId。设备 token 只能放在 `.local/` 下权限 0600 的本地配置中，不进入命令行、状态或日志。配置步骤见 [模拟器说明](../simulator/README.md)。模拟器的 `uploadsSubmitted` 仅表示 complete 接口已接纳，不代表固定版本、复核或存证确认。

真实验收使用独立测试账号和 5 台测试设备，其中 3 台运行上述模拟器。先完成 V9 迁移，并停止其他后端及 RabbitMQ 消费者，再执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe clean verify
python3 deploy/scripts/verify-swarm.py
```

本轮 199 项后端测试、9 项模拟器离线测试、144 项真实数据库约束和 84 次真实 HTTP/473 项检查通过；最终 JAR 对原 M2 同一成果的 15 次 HTTP/112 项检查也通过。完整证据见 [验证记录](../docs/verification.md)。模拟器测试可用 `python3 -m unittest discover -s simulator -p 'test_swarm.py' -v` 重现。

探针启动随机端口及独占 RabbitMQ 拓扑，启用默认自动 Outbox publisher 和单并发 finalizer，不启用 ledger。启动前检查全库没有待派发、定版或存证恢复工作；本轮关闭两类恢复扫描。业务创建、上报、上传和换机均使用正式 HTTP，不直接调用业务 Bean，也不把数据库改成成功状态。

验收覆盖 3 台设备断网后重放及真实 Redis 在线缓存、3 个子任务的平均进度与必需资料缺失汇总、两个并发 CAS 换机只成功一次、非成员/无业务角色访问拒绝。暂停一个真实 S3 读取，让旧批次在 VERIFYING 时发生换机，再验证定版归属仍为旧批次；后续旧 CLOSED 批次补传得到更大版本号时，当前汇总仍只选择当前 executionId 的最新版本。旧批次新报告只记录历史事件及设备在线时间，批次进度保持冻结；原事件重复不刷新在线时间；进度 100 不会自动完成任务或通过验收。人工复核能力见下文 T018；本段保留 T016 当时的验收范围。

安全报告、模拟器状态和日志位于 `.local/swarm-probe/<runId>/`，`latest.json` 只指向成功验收。探针核对固定对象的实际 PNG 字节、大小和 SHA-256，并保留审计。结束时仅清理本次账号、任务、执行调整、事件、版本、对象和 Redis key；独占 anchor queue 中的事件逐条核对自有 Outbox eventId 后 ACK，PENDING 状态不改成 CONFIRMED，不向 Fabric 新增记录，再删除自己的空拓扑。原 18 张业务表的完整行摘要、演示凭据和 `.env` 必须保持不变。手动接口见 [t016.http](../examples/t016.http)。


2026-10-24 真实验收通过：后端 clean verify **199 项测试**，模拟器离线 **9 项测试**，数据库 **144 项真实约束**。集群探针显式 HTTP 57 次、模拟器 HTTP 27 次，合计 **84 次 HTTP**，Java 检查 473 项；确认 6 个固定版本和 6 条自有待存证消息。清理 1 个测试任务、5 个账号、5 台设备、6 个上传会话、12 个对象和 4 个 Redis 键，保留 36 条审计；原十八表完整快照一致。报告为 `.local/swarm-probe/20261024T023750Z-1ce2538c/report.json`。最终 JAR 对原 M2 同版本复查也通过，只追加 3 条核验，无新增版本或链交易；完整证据见 [验证记录](../docs/verification.md)。T017 的多分片乱序、断点续传和并发完成已验收通过，见下文；此处保留当时尚未完成 M3 的范围；当前 M3 已通过，完整 MVP 仍待后续阶段。

## 多分片续传与独立版本归档（T017）

T017 已通过真实验收。现有上传进度返回固定 `partSize`、`partCount`、`expiresAt`，以及按片号排序的 `uploadedParts`、每片大小/摘要和总 `uploadedSize`。客户端从 `1..partCount` 减去已收片号得到缺失片，只补缺片；刷新或应用重启不会重新分配会话规格或延长有效期。同片同内容幂等，不同内容返回 `PART_CONTENT_CONFLICT`；VERIFYING/COMPLETED 冻结写片。

通用用户或设备续传工具为 `simulator/upload.py`，私有配置和状态说明见 [模拟器文档](../simulator/README.md)。先用 `--max-parts 1` 中断一次，再对同配置运行可继续补缺片；状态不保存 JWT 或设备 token。complete 的 202 仅表示异步请求已接纳，需要继续读取服务端进度，不能视为文件定版或链上确认。

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe clean verify
python3 deploy/scripts/verify-multipart.py
```

探针在两个独立 JVM 依次启动实际后端。第一阶段按 1024 字节分片规格创建 2500 字节有效 PNG，乱序提交第 3、1 片并退出；第二阶段把应用规格改成 512，先检查旧会话仍按原 1024/3 片及原 expiresAt，再补第 2 片。新 PNG 则用通用客户端的两个独立 Python 进程完成一片中断和继续上传。另以自然到期的短 TTL 会话验证期限配置，以真实多片 PDF、MP4 结构样例验证普通文件归档；MP4 不测试播放、转码或解码。

真实检查包括完整分片与最终对象的实际长度、SHA-256、S3 字节，短片/长片/范围/配置类型和大小拒绝，已登记片冲突，冻结期拒绝写片，并发 complete 只形成一个固定版本，新旧 PNG 不同摘要且旧对象保持。故意修改本次专属 S3 分片得到 FAILED，无正式版本。新版本复核保持 PENDING；探针不启用 ledger，不向 Fabric 新增记录。

运行前须停止其他后端及消息消费者，并确保全库无待派发、校验或待恢复存证。探针使用随机端口、独占 RabbitMQ 拓扑、独立账号/任务/对象，仅清理本次关联资源并保留审计；自有 anchor 事件逐条核对 Outbox 后 ACK，PENDING 不改成 CONFIRMED。报告、日志和安全检查点位于 `.local/multipart-probe/<runId>/`（0600），汇总为 `verify.json`，两个阶段为 `prepare.json` 和 `report.json`；临时账号密码文件和客户端 JWT 配置在成功清理时删除，原 `.env`、演示凭据和十八表全字段/BLOB 快照保持不变。手动请求见 [t017.http](../examples/t017.http)。


2026-10-24 真实验收通过：后端 **212 项测试**，续传客户端 **11 项离线测试**；两个独立后端 JVM 共 **79 次 HTTP、648 项检查**，两个独立客户端进程另发起 **9 次 HTTP**，合计 **88 次实际 HTTP**。客户端第一次仅上传 1 片并退出，第二次复用同一会话补 5 片后接纳 complete；后端重启后的原 1024/3 片规格和 expiresAt 保持。4 个正式版本覆盖不同 PNG、有效 PDF 和 MP4 结构样例；损坏分片的会话为 FAILED，无版本。核对并 ACK 4 条本次待存证事件，无 Fabric 写入。

报告为 `.local/multipart-probe/20261024T083321Z-d70b567b/verify.json`，阶段报告和安全检查点保留；清理本次 1 个任务、4 个账号、1 台设备、6 个会话和 28 个私有对象，保留 50 条安全审计，原十八张业务表、二进制检查点及凭据保持。T018 异常与人工复核已实现，见下文；本段 T017 验收范围为上传与独立版本归档。

## 版本异常与人工复核（T018）

T018 已通过真实验收。异常和结论都绑定固定 `evidence_version`，不把“存在异常”自动等同于退回，也不把链上确认等同于人工通过。

| 方法与路径 | 请求/结果 | 权限 |
| --- | --- | --- |
| POST /issues | `{versionId,description}`，description 非空白且不超过 4000，返回版本异常 | OrgB OPERATOR_USER 且为任务成员 |
| GET /evidence-versions/{id}/issues | page/pageSize，按 createdAt ASC、id ASC 返回 items/page/pageSize/total | 当前有该任务版本读取权限的业务成员 |
| POST /evidence-versions/{id}/review | `{decision,reason?,expectedReviewStatus:"PENDING"}`，decision 为 APPROVED 或 RETURNED | OrgA OWNER_REVIEWER 且为任务成员 |
| GET /evidence-versions/{id}/review | versionId/reviewStatus/review，未复核时 review=null | 当前有该任务版本读取权限的业务成员 |

RETURNED 必须填写非空白且不超过 1000 字符的 reason；通过时可省略 reason。每个固定版本只有一个正式结论，版本 CAS、唯一 review_record 与成功审计在同一事务内提交；并发或再次决策返回 409 VERSION_CONFLICT。新版本重新 PENDING，旧结论、异常和正式文件保持。复核独立于链状态，APPROVED 但 anchor=PENDING 的资料仍不满足验收条件；异常登记不修改复核或存证状态。

权限每次读取数据库身份、业务角色和任务成员，ORG_ADMIN 不自动获得异常登记或复核权限；设备 token 不能调用这些用户接口。未知和不可访问版本统一 403。正式接口操作见 [t018.http](../examples/t018.http)，显式真实验收如下：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe clean verify
python3 deploy/scripts/verify-reviews.py
```

探针使用独立账号、两个任务、实际 PNG 上传和正常自动 Outbox/定版，关闭恢复扫描，不启用 ledger。检查版本异常分页/归属、角色及成员撤销、设备身份拒绝、v1 通过后 v2 重新待复核、两个复核人并发不同决定只有一次成功，以及 v3 单独退回。原文件、新文件、旧结论和异常均实际读取核对，链状态保持 PENDING，不向 Fabric 新增交易。运行前要求全库无待处理工作，且无其他项目后台进程或消息消费者。

报告和日志位于 `.local/review-probe/<runId>/`，权限 0600，`latest.json` 指向最近成功报告。结束仅清理本次 issue/review_record、任务、账号、版本、对象及独占消息拓扑，逐条核对自有 anchor 事件后 ACK，保留追加审计；原二十张业务表的完整行/BLOB 快照、`.env` 和演示凭据保持。本段 T018 当时尚未接入正式提交验收/完成接口，相关状态流仅由 21 项复核服务单测覆盖；当时的 167 项真实 SQL 回滚检查验证表结构与约束。T019 现已补齐真实 HTTP 的退回重开、历史版本不重开和完成后拒写，见下文，保留 T018 当时的验收范围。


2026-10-24 验收通过：后端 clean verify **233 项测试**，其中新增 21 项复核服务测试；真实数据库 **167 项约束**。显式探针 **108 次 HTTP、780 项检查**通过，实际定版 4 个不同内容的 PNG，登记 4 条异常、3 条正式复核结论。并发不同决定恰好一个成功、一个 409；通过但未上链的版本 acceptanceReady=false，新版重新 PENDING，旧异常、结论和文件保持。

报告为 `.local/review-probe/20261024T123905Z-f324778b/report.json`。清理本次 2 个任务、8 个账号、1 台设备、4 个上传会话与 8 个私有对象；4 条自有存证事件核对后 ACK，存证保持 PENDING，无 Fabric 写入；保留 49 条安全审计。原二十表完整行/BLOB、凭据及独占拓扑清理检查通过。T019 正式提交验收与任务完成现已验收通过，见下文；本段保留 T018 当时的状态流验证边界。

## 正式提交验收与任务完成（T019）

T019 已通过真实验收。完成时间 `completed_at` 与状态配对约束原本就在 V1，本轮沿用 V1～V10 和二十张业务表，DTO 新增显式 `completedAt`，没有新增迁移或回改历史脚本。

| 方法与路径 | 操作者 | 条件与结果 |
| --- | --- | --- |
| POST /tasks/{id}/submit-acceptance | OrgB OPERATOR_TASK_ADMIN 且为成员 | `{expectedLockVersion}`；RUNNING 且当前每项 required 有固定版本，进入 WAITING_ACCEPTANCE，允许复核或存证仍待处理 |
| POST /tasks/{id}/complete | OrgA OWNER_TASK_ADMIN 且为成员 | `{expectedLockVersion}`；WAITING_ACCEPTANCE，当前每项 required 的最新固定版均 APPROVED+CONFIRMED，当前所有资料均无 VERIFYING，进入 COMPLETED 并设置数据库 UTC completedAt |

判断使用子任务 `currentExecutionId` 和该批次资料的最新固定版本，旧批次较大版本号、旧版批准和旧版确认不能代替当前成果。当前 optional 的 VERIFYING 也阻止完成；当前 UPLOADING 草稿和历史批次 VERIFYING 不阻止完成。接口只在短数据库事务内校验资格、CAS 变更及写成功审计，不执行 S3 或 Fabric I/O。并发提交或完成只有一次成功，重复状态请求返回 409。

WAITING_ACCEPTANCE 期间，当前最新版本退回、当前批次产生新固定版或人工换机会回到 RUNNING；旧版退回和历史补传不重开。COMPLETED 后禁止新增任务成果、资料和子任务变更、换机、复核和异常登记；原处理中 Worker 在最终写入前重新检查终态，不能再产生版本。已完成上传会话的 complete 仍可幂等返回原进度，不重新定版。管理员仍可维护成员以撤销历史读取权限，原 completedAt 保持。

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe clean verify
python3 deploy/scripts/verify-acceptance.py
```

真实探针启用 `local,database,storage,messaging,ledger`，使用原普通 User1 身份、原通道与链码，默认自动派发、单消费者定版/存证和恢复扫描；随机本机端口及独占消息拓扑。启动前检查没有其他后台进程/消费者，且全库没有待处理工作。任务、成员、上传、复核、提交、换机和完成全部经过正式 HTTP，不直接调用业务 Bean，不用 SQL 伪造批准、确认或任务状态。

单个测试任务含一个 required 和一个 optional，实际登记七个固定版本。真实背书前短暂停留可验证本地 PENDING 不等于确认，再释放并完成真实 Fabric 提交；分别隔离“已通过但未确认”和“已确认但未复核”两个条件，确认任一缺失都不能完成；真实 S3 流暂停可验证当前 optional VERIFYING 阻止完成、历史 VERIFYING 不阻止完成和终态拒绝迟到定版。完成后使用原 commitBytes 向 Fabric 再查询，双方机构查询同一首笔交易及摘要，并核对正式对象实际字节。

安全报告、日志位于 `.local/acceptance-probe/<runId>/`（0600），`latest.json` 指向成功记录；报告保留 evidenceId/versionNo、首笔交易号、验证码、区块号及检查点摘要，不输出凭据、对象键或原始检查点。结束只清理本次链外测试数据、对象和自有空消息拓扑，保留追加审计；Fabric 记录追加保留，原二十表所有原列（含 completed_at）与 BLOB、原 `.env` 和演示凭据保持。手动请求见 [t019.http](../examples/t019.http)。共享、审计查询和完整 MVP 验收仍待后续任务。


2026-10-24 验收通过：最终后端 clean verify **262 项测试**，真实数据库 **172 项约束**；沿用原十版迁移，没有新增迁移。正式验收探针 **132 次 HTTP、968 项检查**通过，实际完成单个任务，`completedAt=2026-10-24T13:56:32.355Z`。分别验证未复核、未确认及当前 optional VERIFYING 独立阻止完成，并发提交/完成各只有一次成功，旧批次较大版本与迟到 Worker 均不能绕过当前成果或终态条件。

本次 **7 个固定版本均获真实 CONFIRMED**，原交易再查询 VALID、validationCode=0，区块号 14～20；OrgA/B 查询一致，链记录永久保留。报告为 `.local/acceptance-probe/20261024T135552Z-32ad456a/report.json`，包含每条 evidenceId、版本、首笔交易号及检查点摘要。保留 69 条安全审计，清理本次 1 个任务、6 个账号、2 台设备、9 个上传会话与 17 个私有对象；原二十表所有列和 BLOB、原凭据及自有拓扑清理检查通过。T020 共享申请、批准、撤销与受控下载已通过真实验收，见下文；此处保留当时尚未完成 M3 的范围；当前 M3 已通过，完整 MVP 仍待后续阶段。

## 精确版本共享与受控下载（T020）

T020 已通过真实验收。V11 新增 `access_grant`，申请与审批/撤销保存在同一行，唯一 `(applicant_id,client_request_id)`；授权只对应一个固定版本，不扩展到其他版本、整个任务或同机构其他用户。

| 方法与路径 | 行为与权限 |
| --- | --- |
| POST /access-grants | 有效注册用户申请 `{clientRequestId,versionId,reason,requestedValidUntil}`，只返回安全申请元数据；同键同内容重用原状态，不同内容 409 |
| GET /access-grants?mine=true | 当前申请者自己的请求分页；与 taskId 互斥 |
| GET /access-grants?taskId=... | 仅任务成员中的 OrgA OWNER_DATA_APPROVER 可读审批队列 |
| POST /access-grants/{id}/decision | 同上角色批准/拒绝，携带 expectedLockVersion；批准期限不超过申请期限，拒绝须填写原因 |
| POST /access-grants/{id}/revoke | 同上角色撤销仍有效的 APPROVED 授权，携带原因及 CAS 版本 |
| GET /evidence-versions | 先按任务或精确授权路径过滤，再 COUNT/LIMIT 分页，可选 taskId/requirementId |
| POST /evidence-versions/{id}/download-url | 按当前身份/权限签发 `{url,expiresAt}`，响应 no-store；只记录 URL_ISSUED，不把签发当作实际下载 |

申请无需先完成任务，也无需版本已经复核或存证；批准时必须满足该固定版本 APPROVED+CONFIRMED。未批准的申请只让申请者查看请求元数据，不开放详情、存证状态、下载或核验；未知版本申请统一 403。申请/授权列表按 createdAt DESC、id DESC 排序。实际权限使用数据库时间判定，APPROVED 超过 validUntil 即派生 EXPIRED，不依赖后台改状态。

共享读取路径允许同机构非成员及 ORG_ADMIN-only 注册账号，但只授予被批准版本的详情、列表、存证状态、下载和实际核验/历史。没有整任务权限；新版本需要独立申请，旧版撤销不会撤销新版的独立授权。STORED/COPY 核验仍读取实际字节并查询真实链，保存结果前重新验证当前身份及精确授权；I/O 期间撤销或禁用会阻止结果保存。

下载地址签名有效期为 300 秒与授权剩余秒数的较小值，不足 1 秒拒绝签发。签名使用数据库时间并回传实际 SigV4 到期时间；服务端在本地签发与安全审计同事务完成，不读取对象。撤销后不再签发新地址；已经签发的地址仍可能使用至原 expiresAt。私有桶拒绝无签名访问，地址不能写进应用日志、审计、共享元数据或提交记录。

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe clean verify
python3 deploy/scripts/verify-sharing.py
```

真实探针使用原 Fabric User1 身份与原网络，默认自动派发/定版/存证，独占随机消息拓扑；创建单个始终 RUNNING 的任务和两个不同 PNG 版本，正式复核并真实确认。覆盖请求幂等、批准资格、决策/撤销并发 CAS、精确版本与 SQL 分页权限、两机构非成员和 ORG_ADMIN-only 共享、STORED/COPY 实际 MATCH/MISMATCH、自然到期、撤销与禁用期间的核验最终重鉴权，以及真实签名 GET/无签名拒绝及撤销后的旧地址边界。禁用仅是对本次隔离账号的安全负例 SQL，不用于伪造任何业务成功状态。

日志与报告位于 `.local/sharing-probe/<runId>/`（0600），不含 JWT、原始签名 URL、对象键或检查点字节。结束清理本次 access_grant、核验及关联链外夹具/对象/独占空拓扑，保留审计和真实链记录，原二十一表所有列/BLOB、原 `.env` 和演示凭据保持。手动调用见 [t020.http](../examples/t020.http)。


2026-10-25 验收通过：最终后端 clean verify **311 项测试**，真实数据库 **206 项约束**，V11 checksum=`1029133993`。共享探针 **123 次显式 HTTP、866 项检查**通过，创建 5 条申请/授权；两个实际版本均真实 CONFIRMED，验证码 0、区块号 21/22，双方机构查询及原交易检查点再查询一致。

任务路径与共享路径下载 TTL 均实测 300 秒，短授权实测 7 秒；自然到期时原 SQL 状态仍为 APPROVED，实际权限和对外状态已 EXPIRED。共享 STORED/COPY 读真实字节及账本得到 MATCH/MISMATCH；撤销和隔离账号禁用发生在 S3 I/O 中时均未保存结果。报告 `.local/sharing-probe/20261025T011035Z-c4276bac/report.json` 不含签名地址，保留 46 条安全审计与两条真实链记录；清理本次 1 个任务、9 个账号、1 台设备、2 个上传会话与 4 个私有对象，原二十一表、BLOB、凭据及自有拓扑检查通过。T021 审计查询与关系整合已完成，见下文；此处保留当时尚未完成 M3 的范围；当前 M3 已通过，完整 MVP 仍待后续阶段。

## 任务审计与证据关系查询（T021）

T021 已通过真实验收，M3 业务补齐通过。沿用 V1～V11、二十一张业务表，不增加迁移。两条接口仅允许当前任务成员中的 OrgA OWNER_TASK_ADMIN、OWNER_REVIEWER、OWNER_DATA_APPROVER，或 OrgB OPERATOR_TASK_ADMIN；OPERATOR_USER、ORG_ADMIN-only 及只获精确版本共享的用户不能读取整任务历史。

| 方法与路径 | 参数与返回 |
| --- | --- |
| GET /tasks/{id}/audit-logs | page/pageSize，最大 100；可选 action/resourceType/traceId 精确过滤，返回 items/page/pageSize/total |
| GET /tasks/{id}/evidence-relations | page/pageSize，最大 100；可选 requirementId/executionId，返回 taskId/items/page/pageSize/total/asOf |

审计按 createdAt DESC、id DESC 稳定分页，先以多态 `resource_type` 和真实外键确定任务归属，再做筛选、COUNT 和 LIMIT。同 traceId、操作者、机构或设备不会让其他任务的事件混入。登录、角色修改及设备注册/启停是全局事件，不归入任务；设备报告通过其实际执行批次归属。返回原事件安全字段及已解析的任务/版本/子任务/执行编号，URL_ISSUED 明确标注“下载地址签发”，不把签发地址当成实际下载成功。

关系查询先对固定版本分页，再仅按当前页不超过 100 个编号批量汇总异常、正式复核、首笔确认交易、共享各状态数量和核验次数，避免一对多表联接造成计数倍增。currentExecution 按子任务当前执行指针判断；latestInExecution 始终在同资料与同执行内比较，不受当前分页影响。active/expired 共享计数使用响应同一数据库 asOf；存证区块号和验证码只来自与 confirmedTxId 对应的持久尝试，不执行链查询。

查询在短数据库事务中重验当前账号、角色和成员；完成任务仍可读取，失去权限立即拒绝。接口不写审计，也不访问对象存储或 Fabric，不返回对象键、原始检查点、下载地址、异常描述或复核/审批原因。

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe clean verify
python3 deploy/scripts/verify-audit.py
```

真实探针使用两个任务共享同一操作者、设备及 traceId，主任务通过正式上传/复核/真实 Fabric 确认产生两个固定版本，再创建异常、五种共享状态、地址签发及实际核验，并通过正式提交/完成接口进入终态。副任务仅建立执行、设备报告和上传草稿。独立 Java oracle 逐条按原 audit resource type 解析外键，size=1 遍历两任务的全部页，核对精确行、顺序、过滤、空页和派生范围；关系嵌套字段及计数逐项与真实 SQL 对照。查询前后审计总数、真实透传 S3/Gateway 调用计数保持，证明读操作不会追加审计或执行外部 I/O。

本轮两版均属同一当前批次，主要验证旧版与最新标记；历史执行 currentExecution=false 分支沿用既有单测及 T016/T019 批次隔离证据。账号禁用只修改本次安全负例账号，角色和成员撤销使用正式接口；探针不伪造批准、确认或完成状态。日志与报告在 `.local/audit-probe/<runId>/`（0600），清理自己的二十一表关联夹具、对象、单个设备 presence key 和独占消息拓扑，保留审计及真实链记录；原 `.env`、演示凭据、原表全字段/BLOB 保持。接口示例见 [t021.http](../examples/t021.http)。


2026-10-25 最终验收通过：后端 clean verify **339 项测试**，真实数据库 **206 项约束**，沿用原 V1～V11，没有新增或修改历史迁移。最终探针 `.local/audit-probe/20261025T070803Z-13612c9f/report.json` 完成 **161 次 HTTP、1836 项检查**；主任务 42 条、副任务 9 条审计均逐页与独立 typed FK oracle 完全一致，同 trace 的 12 条全局身份/设备事件被排除。关系嵌套字段、两版最新标记、异常/核验/共享各状态计数与 SQL 一致，读取前后审计总量及 S3/Gateway 透传调用数不变。

最终运行保留 63 条审计和两个真实 CONFIRMED 链记录，区块 25/26；清理本次 2 个任务、7 个账号、1 台设备、4 个上传会话、6 个私有对象和 1 个 presence key，原二十一表（含原 27 条核验）、BLOB 和凭据保持。首轮报告 `.local/audit-probe/20261025T070335Z-c199b8d7/report.json` 曾在 98 次 HTTP、693 项检查后因探针 JDBC UTC 类型处理过窄失败；只修正测试 oracle 的 LocalDateTime→UTC 与元数据查询，生产代码未变，首轮原数据/夹具清理也通过。首轮已经提交的区块 23/24 同样追加保留，因此本轮合计保留四条真实链记录，不重建或回退账本。

M3 的 T015～T021 已全部完成；下文 T022 已通过 Outbox、消费幂等和安全死信验收，T023 也已通过处理续租及业务恢复验收；T024 的失败预算及人工重新检查、T025 的引用感知清理也已通过验收，T026 的权限及跨模块并发回归见下文。完整故障矩阵、性能测试、部署复现及完整 MVP 仍按后续任务执行。

## 消息派发、消费回执与安全死信（T022）

T022 已通过真实故障验收。V12__durable_message_rejections.sql 在原 V1～V11 之外追加迁移：Outbox 增加不可自动重试的 FAILED；新增 message_rejection，业务快照扩展为二十二表，原历史迁移不改写。

发布器领取事件时重新检查数据库时间、状态和发送租约。只有当前未到期 token 能保存 SENT、PENDING 重试或 FAILED；旧 token 的 CAS 返回无变更，不覆盖后来的发送者。真实 mandatory Return、NACK、未确认与中断均保留原 eventId 重试。严格 UTF-8、四个字符串字段、重复字段、尾随 JSON 以及独立持久 eventType/bizId 的一致性检查失败时，以 INVALID_ENVELOPE 永久 FAILED，不反复领取或发送。

两个生产监听器使用相同的严格信封规则；可选 messageId 存在时必须与 eventId 相同。拒绝格式错误、类型不符、未知持久事件或 messageId 不符的投递前，独立事务先提交 message_rejection 的 consumer、正文 SHA-256、大小、固定原因码及可选规范 UUID；同 consumer、同正文只保留首次元数据。表与日志不保存正文、任意头或异常详情；私有 broker 死信队列仍保留原投递正文。提交成功后才 basicReject(requeue=false)，由真实 broker 送入持久死信队列；提交失败则不 ACK、不拒绝，在数据库事务之外固定等待 5 秒，再关闭通道让消息重新投递。该运行时退避不依赖容器 recoveryInterval。

正常业务 ACK 也要求已提交证据：COMPLETED/FAILED 等终态，或无活动租约且包含明确下一次执行时间的持久重试计划和 DONE 回执。claim 返回空并不自动代表处理完成；仍由其他 Worker 持有活动租约、未保存检查点或丢失租约时，原消息继续保持未确认。prefetch=1、默认消费者并发=1 保持不变，网络和对象 I/O 在数据库事务外进行。

沿用真实基础设施、原 .env、原演示凭据和现有普通 User1 身份，停止其他项目后台实例及消费者后执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-messaging.py
```

探针先通过正式 HTTP 建任务、上传并冻结，不用 SQL 构造正常成功状态。第一个独立 JVM 先制造实际 mandatory UNROUTABLE、按数据库退避重发，在真实 Publisher Confirm 到达后、SENT 更新前故意 halt(73)；第二个 JVM 自然等待未修改的生产 120 秒发送租约，验证并发领取只有一个新 owner、过期及旧 token 均不能保存结果，再以原 eventId 实际重发。因此会有两份投递，数据库仍只创建一个固定版本、一条存证和一个真实 Fabric 尝试。

恢复阶段还覆盖：claim 事务前注入数据库访问异常时保持 unacked 并延迟重投；真实 S3 读取失败后先保存 VERIFYING 重试计划再 ACK；真实恢复 Worker 持有 S3 I/O latch 和活动租约时，重复消息不能被提前 ACK；解除阻塞后实际定版并获得真实 CONFIRMED；重复投递文件与存证事件仍保留首笔交易。两个消费者都检查无效 UTF-8、多余及重复字段、错误类型、未知持久事件、messageId 不符和尾随 JSON 等安全人工负例；拒收事务前注入访问异常时不会提前进入 DLQ，恢复后去重落库并实际死信。注入不关闭共享数据库；Publisher NACK、未确认和 checkpoint 提交失败的确定性分支由单测覆盖，不将真实 mandatory Return 当作实际磁盘故障 NACK。

脚本默认约需 3～4 分钟，发送租约不通过 SQL 提前到期。报告、两个 JVM 日志和安全 checkpoint 位于 `.local/messaging-probe/<runId>/`（目录 0700、文件 0600），总结果为 verify.json；临时测试密码另存 credentials.json（0600），恢复清理后删除，JWT 仅在内存。若已完成预定 halt，但恢复进程尚未启动时外部中断，可执行 `python3 deploy/scripts/verify-messaging.py --recover .local/messaging-probe/REPLACE_HALTED_RUN`。

结束只清理自己的测试账号、任务、对象、业务消息与拒收行，逐条核对后 ACK 自有测试死信，仅删除已空且无消费者的独占队列/交换机；二十二表全部原字段和 BLOB、原凭据及原 M2 数据均比较保留。追加审计和实际 Fabric 记录保留，链网络、共享队列和服务不重置。HTTP 复现入口和阶段含义见 [examples/t022.http](../examples/t022.http)；本轮不代替 T023 的续租或 T027 的完整故障恢复矩阵。

本轮最终 clean verify **384 项测试**、数据库 **220 项真实约束**通过，V12 checksum=`1723908793`。双 JVM 报告 `.local/messaging-probe/20261025T155232Z-95251a9d/verify.json` 为 PASS：**19 次业务 HTTP、1248 项检查**，默认发送租约 120 秒，恢复进程实际等待剩余 111843 毫秒；claim 与拒收失败的实际重投间隔分别为 5025、5014 毫秒。16 次重复投递仍只创建一个版本和一个首次 Fabric 尝试；两个坏 Outbox 永久 FAILED，17 条去重拒收记录对应 19 次实际死信投递。检查项数包含 broker 管理轮询，不作为独立测试数或性能指标。

最终运行保留 19 条审计和区块 28 的真实 VALID/code=0 登记，清理本次 1 个任务、3 个账号、1 台设备、1 个上传会话、2 个对象及 17 条拒收记录；临时测试凭据已删除，独占拓扑及原二十二表、BLOB、原凭据保留检查通过。首轮 `.local/messaging-probe/20261025T154417Z-4c6b2b05/report.json` 因探针将 RabbitMQ 管理指标的采样延迟当作即时状态而失败，仅修正探针等待和断言，生产代码未变；该轮已提交的区块 27 同样保留。因此本轮两次运行合计追加两条链登记，不回退账本。T022 完成；下文 T023 的处理续租及恢复扫描也已通过验收，M4 尚未完成。


## 处理续租与业务恢复扫描（T023）

T023 已通过真实验收：425 项后端测试、220 项数据库约束及双 JVM 恢复探针通过。继续使用 V1～V12 的二十二张业务表，不新增 V13，不重写已有迁移或原业务行。下述默认 120 秒处理租约、30 秒续租间隔与 Outbox 的发送租约分别管理。

文件定版和存证处理共用 ProcessingLeaseKeeper。领取事务提交后先执行一次短续租事务，再由独立的两个固定心跳线程每 30 秒续租；单进程最多登记 64 个活动保护器，不为每个任务创建线程。续租先锁父任务，再锁业务行，只允许原 token、尚未到期且仍可处理的状态，将期限延至数据库 UTC 当前时间后 120 秒；过期 Worker 必须重新领取，旧 token 不能复活。续租不修改 DONE、重试次数或业务结果。

处理器在文件读取、格式验证、对象写入和 Gateway 调用前后，以及保存检查点或结果前检查租约保护。续租条件更新未命中、数据库异常、保护器停止或线程中断会使当前 Worker 停止推进，不将其误归为普通存储或链故障并写入新的重试计划。保护器还以每次成功续租开始时的单调时间计算保守的本地 120 秒期限；最终写入仍由数据库 token、状态及有效期约束。

已经发出的阻塞 I/O 可能不能立即取消；返回后重新检查保护器，失去租约的结果不能创建版本、更新消费回执或推进下一次链调用。最终对象写入后结果不明时保留候选对象，由 T025 按引用处理。终态或持久恢复计划提交后直接关闭保护器、取消心跳并释放容量，不再追加一次租约检查，避免已清空租约的成功事务被并发心跳误报。

默认每 10 秒触发的两个恢复入口沿用原启用开关。BusinessRecoveryDispatcher 以两个固定恢复线程执行工作，不设置排队容量，每类业务最多运行一个批次；忙时由下一次扫描继续，定时线程不承担对象或 Gateway I/O，续租线程也不受恢复 I/O 阻塞。每批选择最多十条到期业务；DurableRecovery 逐条恢复持久 traceId，单条异常不影响同批其他条目，中断后停止选择和领取新工作。应用关闭先中断恢复工作，再停止其依赖的续租器与数据库。

消息消费与业务扫描调用同一处理器，扫描按业务状态直接领取，显式绕过 consumed_event.DONE。VERIFYING 的临时 I/O 故障持续采用 2、10、30、60、300 秒退避，不因次数到达上限变为文件内容错误；UNKNOWN 继续保留原 transactionBytes、commitBytes 和首次交易编号，由原交易恢复路径对账，不要求 RabbitMQ 重新投递事件。

选择 JDK 21，沿用原 .env、演示凭据、私有对象桶、消息服务及普通 User1 身份，停止其他项目后端或业务探针后执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-recovery.py
```

恢复探针使用两个独立 JVM、专属业务夹具及随机 RabbitMQ 拓扑。真实 Garage 读取超过原 120 秒期限，期间观察默认 30 秒心跳，竞争者不能领取仍有效的租约；指定续租 SQL 前注入一次数据访问异常，旧 Worker 返回后不保存结果。持久化文件重试计划及 UNKNOWN 原交易检查点后，第一 JVM 以预定的 halt(73) 退出；第二 JVM 使用默认定时业务扫描恢复，DONE 不会短路业务。探针等待生产默认租约自然到期，不通过 SQL 提前修改期限，也未停止共享数据库。

报告和私有日志位于 `.local/recovery-probe/<runId>/`，使用目录 0700、文件 0600；复现和现有业务接口观察见 [t023.http](../examples/t023.http)。测试只清理自己的链外夹具和空消息拓扑，已提交的审计及真实链登记继续追加保留。T023 已完成，链失败策略和人工重新检查见下述 T024；T025 的引用感知清理见下文；T027 的完整故障矩阵待后续完成，M4 尚未通过。


本轮最终 `.local/t023-build-final.log` 的 JDK 21 clean verify **425 项测试**通过，失败、错误和跳过均为 0；数据库回归 **220 项真实约束**通过，测试写入回滚，V1～V12 校验和保持。双 JVM 汇总 `.local/recovery-probe/20261026T011916Z-128f9416/verify.json` 为 PASS：第一 JVM **20 次业务 HTTP/151 项检查**，第二 JVM **6 次业务 HTTP/121 项检查**，合计 **26 次业务 HTTP/272 项检查**。检查数包含真实依赖状态和 SQL 断言，不作为独立单测数或性能指标；本次完整实际探针一次通过。

真实对象读取持续 **122980 毫秒**，超过最初 120 秒处理期限；文件续租成功 5 次，包含初始续租及 4 次定时心跳，存证续租成功 2 次，包含初始续租及 1 次定时心跳。一次续租访问异常使旧文件 Worker 停止写入；第二 JVM 在启动 Spring 前自然等待剩余 **82327 毫秒**，先证明原业务仍非终态时，过期旧 token 已不能续租、检查点或定版，再启用默认恢复任务。

第二 JVM 暂停自己的两个 MQ 消费者，以默认 10 秒扫描间隔独立恢复业务，实际等待 **22810 毫秒**后三个版本全部 CONFIRMED；文件重试与存证 UNKNOWN 均由扫描绕过已有 DONE 恢复。原第一版本继续使用同一 txId、transactionBytes 和 commitBytes，没有创建第二个尝试；两机构及原 commit 再查询均为 VALID/code=0。新增三条真实登记的 evidenceId 均为 `27bf8a56-db70-4ea2-9a90-87152eff6996`：

| 版本 | 首次交易编号 | 区块号 |
| --- | --- | --- |
| 1 | `d56e460c543b16449737f25bc74371eef7b48981eb67990f510e86a9e31e953c` | 29 |
| 2 | `2bdef08b0fd93aacacc41054d478bd88fc7086f49fc82e35e6cd418930921a99` | 30 |
| 3 | `6a8297c0186842eedc227200a03f08e44b2f85cdea6850b6a9878b0d98cf348b` | 31 |

清理本次 1 个任务、3 个账号、1 台设备、3 个上传会话、6 个私有对象及关联链外夹具，临时凭据文件和独占消息拓扑已删除；保留 30 条安全审计和区块 29～31 的三条真实登记。原二十二表全部字段/BLOB、.env 和演示凭据保持。旧自动流水线回归也通过 **42 次 HTTP/404 项检查**，该回归不启用 ledger，没有新增链登记。各项报告及最终 JAR 兼容性复查见 [验证记录](../docs/verification.md)。


## Fabric 未知态、失败预算与人工重新检查（T024）

T024 已通过真实业务验收。V13__anchor_retry_requests.sql 仅新增 anchor_retry_request，保存申请人、规范 clientRequestId、请求摘要、原因、新 dispatch_no、原最大 attempt_no 基线和对应事件编号；既有十二版迁移、旧表列、业务及交易字节保持。V13 实际 Flyway checksum 为 `-1202069766`，业务快照扩展为二十三表。

明确失败预算独立于业务 retry_count：只计本轮 baseline_attempt_no 之后，phase=INVALID 且验证码为 MVCC_READ_CONFLICT/PHANTOM_READ_CONFLICT（11/12）的交易尝试。每轮最多十次这类失败，**包含首次明确失败**；达到上限后 FAILED 保留 retryable=true、next_action_at=null，等待人工开启下一轮。它不表示额外十次自动重试。retry_count 保留累计检查点次数及退避用途，在整数上限饱和，不因人工申请清零；查询、确认及背书前暂时不可用不消耗明确失败预算，UNKNOWN 持续对账。

有 commitBytes 时优先恢复原确认请求；没有时查询原业务键，再使用原 transactionBytes/txId 受控恢复提交。查询不存在不能证明原交易无效，UNKNOWN 不因检查次数过多而重新背书。只有已明确 INVALID、属于两种允许重试的冲突、业务记录尚不存在且本轮仍有预算时，才追加新尝试；旧交易和首次有效登记编号保留。明确登记权限拒绝、摘要冲突为不可重试 FAILED；交易可能已提交后的确认查询权限或配置错误仍保留 UNKNOWN 及检查点，不能把查询失败解释为原交易无效。

人工接口仅在完整 `database,storage,messaging,ledger` 组合下提供：

```http
POST /api/v1/evidence-versions/{id}/anchor-retries
Content-Type: application/json
Authorization: Bearer <本机任务管理员 JWT>

{"clientRequestId":"<规范 UUID>","reason":"重新检查原因"}
```

只有当前 OrgB 的 OPERATOR_TASK_ADMIN 且仍为该任务成员可以申请，单独 ORG_ADMIN、操作员、OrgA 管理员及版本共享授权均不赋予此操作权限。每次包括幂等重放都在父任务锁后重新认证当前账号；未知或无权访问的版本返回 403。请求原因必填、最多 1000 字符，空白文本拒绝；请求编号为规范 UUID。

成功返回 **202** 和 `id/submissionId/versionId/eventId/dispatchNo/acceptedAt` 安全回执，只表示新检查意图已持久化，不调用 Gateway，也不改变已知链结果。同一存证、申请人及 clientRequestId 的相同规范化原因重放返回原回执，没有新事件或审计；内容不同返回 409 IDEMPOTENCY_CONFLICT。原请求之后已经确认或任务已经完成时，只要当前权限仍有效，重放也仍返回原回执。

新申请要求任务未完成、存证不是 CONFIRMED 或不可重试 FAILED、没有任何处理租约、最新事件已有 DONE 回执。尚待处理的意图、忙 Worker、已到期但仍需扫描接管的租约都返回 409，不通过人工入口清除处理责任。父任务、当前账号、版本及存证按固定顺序加锁，新 dispatch 的四字段 Outbox、申请记录、恢复计划与 EVIDENCE_ANCHOR_RECHECK_REQUESTED 审计同事务提交。原因保存在受控申请表，不写进事件、回执或审计文本；失败重试不删除、重新编号或覆盖旧尝试。

扫描选择当前存证最大的 dispatch_no，领取事务还在父任务锁下重新核对事件编号，避免人工申请提交前选出的旧扫描结果继续处理。消息及扫描都沿用 T023 的默认 120 秒租约、30 秒独立续租和 T022 的 ACK 持久化门槛；人工申请不能把 UNKNOWN 强制写成成功。

复用原依赖、凭据与普通 User1 身份，选择 JDK 21 后在项目根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-database.py
python3 deploy/scripts/verify-anchor-retries.py
```

请求和前后状态观察见 [t024.http](../examples/t024.http)。探针使用独占业务夹具与消息拓扑，实际故障边界和结果以私有报告及 [验证记录](../docs/verification.md) 为准；正常成功状态不通过 SQL 伪造。T025 的引用感知对象清理见下文；T027 的完整跨系统故障矩阵仍未完成，M4 尚未通过。

本轮 `.local/t024-build-initial.log` 的 JDK 21 clean verify **458 项测试**通过，失败、错误和跳过均为 0；后续仅调整独立探针并完成 `.local/t024-probe-compile.log` 的 test-compile，生产代码和上述单测未变。最终数据库报告 `.local/database-probe/20261026T070711Z-6da80706/verify.json` 的 **235 项真实约束**及回滚预算检查通过，覆盖 UNKNOWN 不消耗明确失败预算、十次冲突停止新领取、人工基线开启新一轮并选择最新 dispatch、旧尝试保留及永久失败排除；tenthDefiniteConflictCheckpointVerified=true 还证明当前 Worker 到达第十次明确冲突后仍能提交终止检查点，随后不再被自动领取。

真实探针 `.local/anchor-retry-probe/20261026T070756Z-f089a6a4/verify.json` 为 PASS，完成 **77 次业务 HTTP/599 项检查**，本次实际运行一次通过。两个业务版本真实 CONFIRMED，两个安全负例为不可重试 FAILED；共接受两条人工重新检查申请。已保存 commitBytes 的确认响应实际丢失一次，随后复用原确认请求恢复；另一例在网络提交之前注入一次异常，再恢复原 prepared transaction/txId，不能将它表述为已发送交易的响应丢失。一次登记准备阶段的权限拒绝来自隔离测试装配，没有修改真实链 ACL；摘要冲突则使用实际链登记验证。

业务接口实测覆盖当前任务权限、幂等重放、同请求编号改原因冲突、重复待处理意图拒绝，以及确认后不再创建新意图。检查项数包含真实依赖、SQL 和状态核对，不作为 599 个独立单测或性能指标。原交易和确认字节保持，历史尝试不删除、不重新编号。

本轮共追加保留三条真实 VALID/code=0 链登记；只有前两条对应业务 CONFIRMED，第三条是特意写入错误摘要的冲突负例，业务保持 FAILED：

| 场景 | evidenceId | 版本 | 账本登记交易号 | 区块号 |
| --- | --- | --- | --- | --- |
| 原 commit 恢复成功 | `cb17fff9-8a65-490a-8361-bca665f26895` | 1 | `dd60791cbd7ae58608e91abe9d818dcd2f6fc3e685f03b28e2448c5bbde55e09` | 32 |
| 原 prepared transaction 恢复成功 | `5ba1f2b7-8a0d-4fb7-8f86-568bf49090b4` | 1 | `66ddcb9b118426a12672764e3a89bb48997cdd98bb1024e34837d4d8609d26cf` | 33 |
| 摘要冲突负例 | `5ba1f2b7-8a0d-4fb7-8f86-568bf49090b4` | 3 | `5ea9901bf6892de7b36d5261fa7b47594e50d4e7b58fe437bdeac8f6b6496516` | 34 |

第三条登记摘要为 64 个零，与该版本实际文件 SHA-256 不同；处理器拒绝覆盖，人工入口也拒绝继续重试。三条账本记录和 55 条安全审计继续追加保留，清理本次 3 个任务、7 个账号、1 台设备、4 个上传会话、8 个私有对象及自己的申请/消息/关联业务记录，独占消息拓扑已删除。原二十三表全部字段/BLOB、.env 和演示凭据保持。测试夹具 UUID 在清理后不能用于继续调用 HTTP，原 M2 成果的最终复查见 [验证记录](../docs/verification.md)。T024 完成，引用感知清理见下述 T025；M4 尚未通过。

## 临时文件、分片及孤立对象清理（T025）

清理依赖 `database,storage,cleanup`，不要求 Fabric 身份或 `ledger`。正常启动器通过以下命令显式启用；生产使用 `--profile prod --cleanup`，默认不初始化演示账号。

```bash
python3 deploy/scripts/run-backend.py --cleanup
```

`--cleanup` 可与 `--ledger` 同时使用，但它们分别控制清理和链存证。普通 local/prod 启动不会自动加入清理 profile。V14__storage_cleanup_tasks.sql 新增 `storage_cleanup_task` 保存对象摘要身份、发现时间、大小、状态、尝试次数、下次处理时间和清理租约；既有迁移不修改。清理没有对外删除接口，不向 HTTP 响应或任务审计暴露对象键。

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `CLEANUP_GRACE_SECONDS` | `3600` | 对象和临时残留的宽限时间，单位秒 |
| `CLEANUP_BATCH_SIZE` | `100` | 每个对象前缀、待处理任务及每个临时目录的批量上限；范围 1～100 |
| `CLEANUP_SCAN_DELAY_MS` | `60000` | 定时触发间隔，最小 1000 毫秒 |
| `CLEANUP_SCHEDULER_ENABLED` | `true` | 是否触发自动清理；仅在 cleanup profile 下生效 |

调度器使用一个无排队后台线程，并阻止清理周期重叠；启动和调度线程不执行 S3 I/O。对象扫描仅处理服务端规范的 `upload-parts/`、`evidence-files/` 键，跨轮保存分页位置，每轮按上限推进。一个前缀 LIST 失败会推迟该前缀，仍可处理另一个前缀及已有的到期清理任务。

清理仅处理后续业务已不能采用的对象：正式 `evidence_version` 引用、已登记分片、仍为 UPLOADING 的候选对象及当前有效定版租约对应的候选对象均受保护。UPLOADING 仅在数据库期限到达后转为 EXPIRED；超出宽限的 EXPIRED 或 FAILED 会话、没有正式版本且没有有效处理租约时才退役其已登记分片。COMPLETED 的分片和正式文件继续保留，VERIFYING 的源分片继续供恢复使用。

清理事务按照父任务、上传会话、清理任务的顺序取锁，领取任务后在事务外调用 S3 HEAD，再用短事务重查引用、状态、宽限和租约，最后在事务外 DELETE。任务租约为 120 秒，完成、重试及续租均校验当前 token 和数据库期限；旧 Worker 不能覆盖新处理结果。删除失败持久化 2、10、30、60、300 秒退避计划，对象已不存在按幂等完成处理。已完成清理的同键残留再次出现时，符合条件后可重新激活原清理任务。数据库提交失败不会被误报成对象删除失败。

分片接收、文件定版和 COPY 核验共同使用 `TemporaryFileManager`。文件及所在目录仅授予当前用户权限，持有独立 `.lock` 文件的操作系统锁，文件句柄在实际 I/O 及提交边界结束后才释放；核验请求超时时，尚未结束的读取仍持有保护。清扫仅识别上传、定版和核验三种生成名称，拒绝符号链接并保留未知文件，不依赖进程内集合判断其他 JVM 是否仍在使用。超龄残留必须通过锁和文件身份检查后才删除；目录分页游标使后续批次可越过仍在使用或尚未到期的文件，游标在关闭、目录替换或缓存淘汰时释放。

真实服务探针使用已构建的 `database-probe` 测试入口，运行前停止其他项目后端和消息消费者：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-cleanup.py
```

探针通过正式 HTTP 建立隔离上传，使用真实 MySQL、Garage、RabbitMQ，按受控故障边界驱动实际清理和定版处理器，检查提交结果不明、引用保护、到期退役、删除退避及删除前再次核对引用；另以独立 JVM 检查活动临时文件保护和崩溃残留回收。仅本轮进程使用 2 秒清理宽限和 20 秒上传期限，不修改生产默认值。它不启用 `ledger`，不会新增 Fabric 登记；收尾仅清理自己的链外夹具及空消息拓扑，审计继续追加。报告和日志保存于 `.local/cleanup-probe/<runId>/`，目录 0700、文件 0600；结果以 [验证记录](../docs/verification.md) 为准，观察方法见 [t025.http](../examples/t025.http)。

T025 已通过 508 项后端测试、248 项真实数据库约束，以及独立清理探针的 30 次 HTTP/303 项检查；四个正式文件版本均由真实定版处理器生成，无新增 Fabric 登记。探针收尾核对并保留 37 条任务范围安全审计，清理自己的上传、清理任务、对象及消息拓扑后，原二十四表全字段和二进制检查点保持。最终 JAR 对原 M2 的 15 次 HTTP/111 项复查通过。失败运行、修复原因及完整证据路径见 [验证记录](../docs/verification.md#t025临时文件分片及孤立对象清理)。后续权限及并发回归见下述 T026；M4 尚未通过。

## 权限、状态与跨模块并发回归（T026）

本轮修复两个管理员同时维护不同任务时的真实 MySQL 成员更新死锁。成员替换在父任务锁后预读当前身份，收集本机构当前成员、请求候选及操作者，按账号编号去重排序加锁，再最终重验身份、tokenVersion、角色、成员、任务锁号及候选启用状态/角色。删除按具体成员编号执行，不再使用隐式锁其他账号的多表 DELETE JOIN；创建者与对方机构成员保留。业务和审计仍原子提交，接口及 V1～V14 迁移保持。

选择 JDK 21、构建测试 CLI 后，使用既有服务和凭据运行以下独立探针。运行前停止其他项目后端和消息消费者：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-member-locks.py
python3 deploy/scripts/verify-regression.py
```

成员锁专项只需要真实 MySQL，通过真实 TaskService 创建两个独占任务，让两个管理员分别持有父任务锁后同时竞争相关账号；8 轮/16 次更新均成功且无死锁，核对账号顺序、成员集合、任务锁号和每次唯一审计。它不启动 HTTP、对象、消息或 Fabric Worker，也不计入 HTTP 次数。

综合回归使用真实 MySQL、Garage、RabbitMQ、Fabric 和原 User1，通过正式 HTTP 建立业务。成员、复核、共享审批、提交及完成各以两个请求竞争同一锁号或 PENDING，结果均为一个 200、一个 409，再核对唯一数据库结果和成功审计。实际 S3 读取的事务外门闩控制成员撤销/核验、换机/定版及完成/历史定版交错；同时检查直接 ID、SQL 权限分页、同 JWT 成员撤销、新旧版本精确授权和终态写入限制。

已完成任务仍可维护成员及申请、批准、撤销指定版本共享，这些操作不能重开任务或改变 completedAt；新上传、换机、定版、复核和异常写入继续受终态限制。测试关闭本轮发布和两类恢复扫描，暂停自己的消费者，显式以原 Outbox 事件调用生产处理器；不通过 SQL 伪造正常成功状态。末尾真实发布确认并仅排空本轮 eventId。安全报告与日志分别保存在 `.local/member-lock-probe/`、`.local/regression-probe/`，目录 0700、文件 0600。

513 项后端测试、248 项数据库约束、综合回归 183 次 HTTP/1322 项检查及原 M2 15 次 HTTP/116 项复查通过。四个实际固定版本均通过原有效交易确认及两机构查询，区块 35～38 的登记和追加审计保留；清理自己的链外夹具和空消息拓扑后，原二十四表及凭据保持。修复前死锁证据及精确场景见 [验证记录](../docs/verification.md#t026权限状态与跨模块并发回归)，观察示例见 [t026.http](../examples/t026.http)。T026 完成，完整跨系统故障矩阵见下述 T027。

## 跨系统故障注入与恢复验收（T027）

使用 JDK 21 和既有 MySQL、Garage、RabbitMQ、Fabric、原 User1，先停其他项目后端及消息消费者。总入口顺序执行发送/定版和 Fabric RPC 两组，保留各自分阶段的独立报告：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-faults.py
```

也可分别运行 `verify-publish-crashes.py`、`verify-fabric-crashes.py`。普通 Maven 构建只执行本地测试，不自动提交链交易或触发进程崩溃。真实探针不缩短生产的 120 秒租约，不手改业务成功状态。

发送/定版组由协调 JVM 通过正式 HTTP 建立隔离上传，随后关闭其应用上下文。三个独立子 JVM 分别在 Outbox 领取事务提交后/发送前、真实发布确认后/SENT前、候选文件实际 PUT 后/版本事务前以 73、74、75 退出。原租约自然到期后，新 JVM 启用默认发布、消费者和两类恢复扫描，核对原事件真实重复投递及生产 listener 的 ACK、唯一版本/存证/成功审计，以及候选对象写入后的恢复。

Fabric 组依次使用 setup、提交前、真实提交返回后、真实确认返回后、recover 五个独立 JVM。三次故障均为 halt(73)，原交易与已有确认请求保存在数据库，报告只保留编号及字节摘要。恢复优先沿原检查点；已提交但 commitBytes 未保存时，可凭相同的有效业务登记直接确认，原尝试继续保留 SUBMITTING/null commit 是合法历史，不能补造检查点。

恢复阶段对一项已可能提交的交易，在测试装配的 commit 和业务查询边界同时注入 UNAVAILABLE，核对至少两次默认扫描形成持久 UNKNOWN、DONE 和退避计划；放开边界后实际收敛。该注入不停止共享 Fabric 网络，不表述为网络已经物理宕机。Fabric 组暂停自己的消息消费者以证明扫描器独立恢复；发送组则实际启用消费者并验证重复投递。

总报告位于 `.local/fault-matrix/`，分组报告位于 `.local/publish-crash-probe/` 和 `.local/fabric-crash-probe/`，目录 0700、JSON/日志 0600。夹具明文密码及令牌不写报告或临时凭据文件，检查点不含对象键、JWT、设备令牌或原始交易字节。候选文件崩溃留下的本地 finalize 文件只在本轮私有目录保留作证据，无引用 S3 候选随独占对象清理，不把此收尾计为一次生产 GC 验收。

总入口 `--resume <runDirectory>` 可复用已经通过的组件，但要求实际生产 classes 与运行依赖摘要一致；生产实现变化后必须新建矩阵运行。Fabric 组件的 `--recover <runDirectory>` 用于三个预定 halt 已完成而恢复阶段尚未通过的运行，旧失败报告/日志另名保留；`--cleanup <runDirectory>` 只清理已停止运行的自有夹具。已提交链登记和安全审计始终追加保留。观察方式见 [t027.http](../examples/t027.http)，实际结果见 [验证记录](../docs/verification.md#t027跨系统故障注入与恢复验收)。

本轮总矩阵及两个组件均首轮通过：发送/定版组 23 次 HTTP/120 项协调器检查，Fabric 组 20 次 HTTP/155 项检查，六次预定真实 halt 后各三个版本实际确认。原 120 秒租约自然到期，默认扫描在两次 RPC 不可用时保存 UNKNOWN/退避，再恢复原交易；发布确认丢失后的同事件重发和 DONE 后重复 ACK 已核对。513 项后端测试、248 项数据库约束及原 M2 15 次 HTTP/110 项复查通过；区块 39～44 的六条登记追加保留，清理自有夹具后原二十四表及凭据保持。T027 和 M4 已通过，下一项 T028 提供最小管理界面，M5 和完整 MVP 尚未完成。

## 最小管理界面与浏览器联调（T028）

管理界面的 HTML、CSS、SVG 和 ES modules 位于 `src/main/resources/static/`，由 Spring Boot 静态资源处理器随同一 JAR 提供。没有前端打包步骤，不依赖运行时 Node 服务或外部 CDN；Node 和 Playwright 仅用于开发验证。已有业务服务、Fabric 网络及链码正常运行时，在项目根目录执行：

```bash
python3 deploy/scripts/run-backend.py --ledger
```

打开 [http://127.0.0.1:8080/](http://127.0.0.1:8080/)。启动器沿用原 `.env`、数据库及普通 User1 身份；已有账号直接登录，首次初始化仍按前文流程显式执行 `--demo`。直接 `java -jar` 的独立 HTTP 模式不启用业务数据库，界面加载成功不代表业务接口已经启用。文件指纹需要支持 Web Crypto 的现代浏览器；HTTP 开发地址使用 localhost/127.0.0.1，其他部署地址应使用 HTTPS。

### 界面范围与操作

| 工作区 | 已接入的操作 |
| --- | --- |
| 任务工作台 | 有权限任务的分页、创建、资料要求、运营商分配、按机构维护成员、子任务、初次设备分配/换机、启动、提交验收与完成 |
| 多机进度与资料 | 子任务当前设备、执行批次、进度、当前资料及历史版本；从任务范围进入上传或打开固定版本 |
| 文件上传 | 选择资料项/执行批次、文件指纹、分片进度、暂停、查询服务端进度、同会话续传、异步定版状态与固定版本入口 |
| 版本与复核 | 固定版本及存证状态、待确认提示、人工重新检查、异常登记/查询、通过/退回、原存储文件及上传副本核验、核验历史和短期下载 |
| 共享授权 | 本人申请、按任务审批、批准/拒绝/撤销、有效期及精确版本打开；一个版本的授权不扩展到其他版本或整个任务 |
| 设备与审计 | 设备登记/启停、在线状态及分页；按任务查看实际关联的业务审计和诊断编号 |

现有接口没有全机构用户目录，分配和成员维护使用用户编号；可在“当前账号”中复制编号，结合任务已有成员的机构及角色核对。界面不会嵌入演示账号密码或 Fabric/S3 凭据。登记设备时的一次性设备凭据默认遮盖，刷新页面、关闭凭据对话框或退出登录会清除该页面中的值；不会写入本机续传记录。

关键写入使用当前服务器返回的锁号或 PENDING 复核状态。409 冲突时刷新当前状态并保留表单内容，让用户核对后重新提交，不自动覆盖其他操作。页面按钮根据当前角色和状态提示可用操作，服务端仍重新校验身份、成员、精确授权、状态及锁号；隐藏按钮不能替代权限检查。上传完成接口返回 202 只表示已接纳定版，存证 UNKNOWN 仍显示结果待确认，不显示为已经成功。

### 登录与续传

JWT 仅保存在当前页面的 JavaScript 内存，通过同源 API 的 Bearer 请求头发送，不写 `localStorage`、`sessionStorage` 或 Cookie；页面刷新后需重新登录。退出或身份失效会销毁当前页面模块、终止尚未完成的请求，并停止旧页面回调更新界面。

只有上传的非凭据元数据进入同来源、同标签页 `sessionStorage`，按当前账号编号分区：稳定的 clientRequestId、uploadId、资料项/执行批次、文件名称/类型/大小、内容指纹及处理状态。文件字节、密码、JWT、设备令牌和下载地址不在其中；关闭标签页或清除会话存储后，不保证本机记录仍存在。刷新重登同一账号后，在上传工作区选择已保存会话、重新选择原文件，再查询服务器已登记分片，继续发送缺片。

文件指纹逐块覆盖全部字节，默认每块 4 MiB，并对带版本域的分块摘要列表再次计算摘要，避免只比较文件名或首尾片。该指纹用于重新选择文件的续传一致性检查，不是正式文件的 SHA-256；正式摘要仍由后端实际文件校验产生。实际发送分片的大小和数量以服务器会话为准，不由该指纹块大小决定。暂停或 503 后保留请求编号和会话信息；会话创建响应不明时沿原请求编号查询/重试，不直接新建另一个版本。存储记录不可读或无法保存时，上传停止并显示原因。

### 同源与浏览器边界

只有 GET `/`、`/index.html`、`/ui/**` 及既有状态/登录入口公开；其余 API 和写入方法继续使用原用户或设备安全链。静态路径不提供 API 代理，也没有新增跨域通配配置。页面及接口携带 CSP，脚本/样式/连接仅允许自身来源，禁止对象、外部 base 及页面嵌套，并保留 DENY、nosniff 和 no-referrer；脚本和样式放在外部静态文件。动态业务文本通过 DOM 的文本接口呈现，不解释为 HTML。

短期签名地址只在需要时取得并用于浏览器导航，不保存到续传记录或审计；服务端仍记录“下载地址签发”，不会将其当作实际下载成功。副本核验通过原 COPY 接口，不覆盖正式对象或新建版本。

### 本地协议测试与真实浏览器验证

本机 Node 24 可直接执行两组共 31 项无第三方依赖的协议测试，分别报告 15 项和 16 项：

```bash
node frontend-tests/api.test.mjs
node frontend-tests/upload.test.mjs
```

覆盖 API 正文及身份生命周期、完整文件指纹、服务端缺片计划、创建结果不明后的同键恢复、暂停/重入、账号隔离和损坏存储拒绝等边界。本地协议测试已通过；它不连接真实业务服务，不能替代浏览器、数据库及链确认的联调。

需要浏览器自动化时，可在忽略提交的私有目录准备固定 Playwright 1.56.1 和 Chromium headless shell。它们不是后端运行依赖：

```bash
npm install --prefix .local/browser-tools --no-save --package-lock=false playwright@1.56.1
PLAYWRIGHT_BROWSERS_PATH="$PWD/.local/ms-playwright" node .local/browser-tools/node_modules/playwright/cli.js install chromium --only-shell
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P database-probe,fabric-probe clean verify
python3 deploy/scripts/verify-ui.py
```

真实探针运行前停止其他项目后端和消息消费者。脚本自行启动打包应用及隔离消息拓扑；`UiBrowserFixture` 只初始化随机测试账号、只读检查实际结果并安全收尾，正常任务、上传、复核、共享及核验由浏览器通过正式页面驱动。夹具明文密码和令牌只在进程内存中使用，不进入 argv、报告或临时凭据文件。结束先等待本轮工作静止，停止自己的浏览器/后端，再删除自有链外夹具，比较原二十四表全字段/BLOB；已发生的安全审计和真实链登记继续追加保留。

报告、日志和桌面/窄屏截图位于 `.local/ui-probe/<runId>/`，目录 0700、文件 0600，成功运行由 `.local/ui-probe/latest.json` 指向。截图在关闭凭据对话框后生成，不展示明文设备凭据。T028 最终 JAR 已通过 517 项后端测试、31 项协议测试、204 项真实浏览器检查、248 项数据库约束及原 M2 的 15 次 HTTP/108 项复查。完整页面场景、四条追加链登记、失败修正和清理证据见 [验证记录](../docs/verification.md#t028最小管理界面与真实浏览器联调)；静态资源检查见 [t028.http](../examples/t028.http)。T028 完成，下一项 T029；M5 的最终验收、压测及交付仍单独执行。


## 可保留全量业务演示（T029）

接口及变量统一入口为 [examples/README.md](../examples/README.md) 和 [t029.http](../examples/t029.http)；自动脚本及前置步骤见 [T029 演示说明](../docs/t029-demo.md)。原生 Python 标准库脚本复用现有打包应用运行器，不增加后端业务依赖、迁移或 API。构建需启用 database-probe,fabric-probe，以编译 SELECT-only RetainedDemoInspector。

```bash
python3 deploy/scripts/test_run_demo.py
python3 deploy/scripts/run-demo.py
python3 deploy/scripts/run-demo.py --recheck .local/full-demo/<runId>
```

默认运行保留新任务、四设备、三子任务及四个固定版本：先用三台设备上报，人工换机并验证旧批次迟到隔离，再演示 V1 复核/共享与 V2 独立复核/授权，完成后撤销 V1、批准 V2，实际原文件/副本核验及签名下载。原账号只登录，不调用 logout、不修改角色或密码；新设备凭据只在进程内存使用。演示任务完成后不需要重用这些一次性凭据继续上报。

启动前要求全库在途上传、Outbox、存证、消费及清理工作收敛；独立消息拓扑不能隔离共享数据库的默认恢复扫描。只读 helper 对创建前二十四表逐主键记录全字段/BLOB 摘要和原迁移行，完成后要求全部原行仍在且不变，并实际读取本轮分片/正式对象、双机构账本及原 commit 有效回执。复查另比较本次开始前完整二十四表快照，要求业务行、数量、字段全部保持；登录 AUTH_LOGIN 审计正常追加，业务读取不写新核验。

当四个版本已经实际 CONFIRMED+APPROVED、后续业务中断时，`python3 deploy/scripts/run-demo.py --resume .local/full-demo/<runId>` 先复核持久成果再继续验收/授权/核验，不重复上传或链登记。已有相同核验结果按来源、结果及实际摘要复用；不满足检查点或原授权已不可撤销时按真实状态拒绝，不强写状态。失败与续跑报告分别保留，成功续跑资源由 latest-resume.json 指向，后续 --recheck 可仍使用源目录。

私有运行目录为 `.local/full-demo/<runId>/`（0700，报告/日志0600），正常成果不做夹具删除。只删除自己的空消息拓扑并停止自己的后端。下载地址仅用于内存中实际 HTTP GET，不落盘；样例 PNG 和 JSON 可提交版本库，密码和所有令牌仍使用原私有凭据文件或进程内存。


## 全部 AC 最终功能验收（T030）

运行方式和判读见 [T030 最终验收](../docs/t030-acceptance.md)。`verify-final.py` 不是已有报告的静态汇总：它串行新执行设备/多机/多片、核验/共享/完成/并发、清理/消息/续租/崩溃恢复、链码与真实 UI，再验证数据库及已保留成果。每项运行必须发布新的成功索引，当前矩阵记录原始报告路径和文件哈希。

独占运行前后由 RetainedDemoInspector 获取一致的二十四表快照、原迁移历史及配置/凭据摘要；常规夹具清理后要求完整业务表恢复，只允许最后原 M2 复查追加三条业务核验，审计和真实链登记继续保留。生产 JAR 与完整 target/classes 内容逐文件比较，再锁定生产源码、运行类/资源、依赖和链码 JAR；Java 探针与打包 UI 因而对应同一最终生产实现。链码另核对运行容器内 JAR、镜像、原部署锁、双方提交策略和批准包，不重部署。

```bash
python3 deploy/scripts/test_verify_final.py
python3 deploy/scripts/verify-final.py
python3 deploy/scripts/verify-final.py --resume .local/final-acceptance/<runId>
```

真实业务报告、数据库/对象/账本证据以及逐 AC 门槛均通过才写总 PASS。`--resume` 只复用该矩阵当前产物下已成功且哈希/工具摘要未变的组件；不能用历史 T027 或 T029 的 PASS 直接满足本轮最终验收。若修改生产代码或依赖，应重建并开启新矩阵。仍在使用的随机夹具凭据、未知交易或未来租约均不得通过清库、删对象或强改状态来跳过。


## 性能与资源实测（T031）

方法、实际环境和逐文件结果见[性能记录](../docs/performance.md)。使用T030已经通过的打包JAR，测试源码只新增PerformanceInspector，执行 `test-compile` 编译辅助类；生产JAR、源码/classes/依赖和链码绑定均未变化。正常Outbox扫描1000ms，定版/存证各1个实际消费者、prefetch=1，Hikari最大5；压测不调整这些配置。

```bash
mvn -o -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -Pdatabase-probe,fabric-probe -DskipTests test-compile
python3 -m unittest discover -s deploy/scripts -p '*perf*.py' -v
python3 deploy/scripts/run-performance.py
```

查询每档并发1/3/5，设备active3/5，各正式阶段至少10秒，预热排除；文件是1MiB×3串行、1MiB×3并行、17MiB×5并行。本轮30,343查询、1,756正式上报及11正式文件无错误；12含预热版本均single VALID、双机构账本/原commit及正式对象实际重查一致。查询P95 8.78～26.88ms；5路17MiB的创建至确认观测为5.87～14.22s，包含后台排队与轮询，不代表纯commit延迟。

私有报告位于 `.local/performance/20261028T020815Z-c952459a/`；原24表250行、10正式对象及凭据保持，本轮SQL/对象夹具与空拓扑已清理。两个资源采样线程分别采进程与Docker，CPU/RSS、样本覆盖及观察开销单列；9容器均已实测，资源错误0。工具21项测试通过；本轮没有为未变更的生产代码重复完整T030功能矩阵。下一步T032，M5仍待部署复现及后续交付。


## 部署复现与普通重启（T032）

首次完整部署以[根README](../README.md#首次部署)为入口，隔离步骤与实际证据见[部署复现](../docs/deployment.md)。T032在独立源码目录、新配置/凭据、空业务与账本卷上，重新构建并运行本应用；原target、身份和数据未复制，仅复用已校验的依赖/归档/镜像缓存。

```bash
python3 deploy/scripts/verify-deployment.py
```

本轮42步骤PASS，源码重建517后端/50链码测试通过，另32部署工具测试通过。新环境首次启动执行V1～V14迁移并初始化6账号/1设备，真实三设备模拟器各2报告后，正常业务演示完成4版本CONFIRMED/APPROVED；142次API/923项检查及另6次模拟器HTTP通过。

9容器全部stop/up后，重新运行prepare/channel/deploy和demo初始化均复用原材料/定义/凭据；只读复查30次API/217项检查通过，新24表91行、4正式对象、原commit及链高度8保持。普通后台启动另由initial/restart两次run-backend --demo --ledger证明，不能将禁用后台的只读复查写成后台恢复压测。原24表250行、10对象、原链高度98及容器/镜像/卷均保持。

部署配置新增两个Compose项目名、四Fabric宿主端口及独立CaaS镜像tag。默认值兼容现有环境；非默认namespace只在全新根及新材料使用，两个Gateway endpoint须随Peer宿主端口同步。SERVER_PORT仍由进程环境提供；.env不切换JAVA_HOME。下一项T033，未提前结束M5。


## 演示与面试材料（T033）

[演示讲稿](../docs/demo.md)包含业务与架构、完整三设备/四版本流程，以及Outbox确认后崩溃、Fabric原交易恢复两个源码/真实证据关联案例。[面试材料](../docs/interview.md)提供介绍、简历要点和十个追问，区分Java应用机制与Spring/MySQL/RabbitMQ/S3/Fabric提供的能力。

材料引用T030异常、T031性能及T032完整演示，明确ACK/DONE不等于业务完成、UNKNOWN不等于明确失败、业务CONFIRMED不强制补造本地VALID；处理租约的30秒续租与Outbox发送租约分开。Redis在线判定仍以SQL为权威，不能宣称命中绕过SQL。T033只核对文档和现有证据，没有重跑业务或改生产代码；T034核对结果见[交付说明](../docs/delivery.md)。


## 最终交付核对（T034）

48项接口及HTTP示例、V1～V14迁移/25张应用表与需求设计一致，既有17组件/12AC证据完整。T034新执行只读数据库/对象/链查询：原24表250行、10对象、63核验记录、双机构原登记与链高度98保持，原9容器运行、隔离9容器停止。初次核对发现315个生成class与验收JAR漂移，保留差异和原class后从该JAR恢复，当前源码与五项产物绑定通过。未改生产实现，也未把历史功能/性能/部署PASS写成新跑。M5与本次MVP已完成，入口及后续变更验证方式见[最终交付](../docs/delivery.md)。
