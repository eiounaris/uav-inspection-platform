# 接口示例与样例数据

本文按当前 Controller 的实际映射整理接口。业务 REST 以 `/api/v1` 为前缀，按“方法 + 路径”去重共 **48 项**；内部 `/error`、Actuator 及静态页面不计入业务接口。全部目录项均有对应请求文件，T029 的连续演示模板见 [t029.http](t029.http)。实际运行结果由 [验证记录](../docs/verification.md) 记录，示例目录不等于 AC-01～AC-12 最终验收通过。

## 使用顺序与变量

1. 按 [项目说明](../README.md) 准备原基础设施、Fabric 网络、链码、演示身份及 JDK 21，构建后启动 `python3 deploy/scripts/run-backend.py --ledger`。首次演示账号初始化按后端说明显式使用 `--demo`；日常启动沿用原数据和凭据。
2. 使用支持 `.http` 文件的客户端，或者按文件内容在其他 HTTP 客户端发送请求。口令仅从本机忽略提交的 `.local/demo-credentials.json` 或实际分配渠道取得，保存在私有客户端环境；不把真实密码、JWT、设备令牌、私钥或签名地址写进这些文件。
3. 登录响应 `data.accessToken` 是有效期 30 分钟的用户 Bearer JWT，过期后重新登录并更新本地变量；`GET /users/me` 返回当前用户编号及角色。注册设备返回 `data.device.id` 和只返回一次的 `data.deviceToken`，后者必须私密保管，设备列表不能找回它。
4. 任务、资料项、子任务和执行批次编号从创建/读取任务响应中取得。资料项按名称定位，子任务按名称定位，不依赖列表或数组顺序。`versionId` 来自处理完成的上传会话，`evidenceId` 是资料项的稳定编号，二者不能互换；新固定版本有新的 `versionId`。
5. 每个修改操作按它的资源读取最新 `lockVersion`：任务操作取任务锁号，初次分配/换机取子任务锁号，设备启停取设备锁号，审批/撤销取授权锁号。出现 409 时先读取最新状态并核对，不仅替换锁号后盲目重放。复核使用 `expectedReviewStatus=PENDING`。
6. 上传和共享的同一次业务重试沿用原 `clientRequestId` 与相同内容；新上传、新版本或新申请使用新的 UUID。设备事件的 `eventId` 及 `(bootId, seq)` 都要保持一致地重试，新事件递增 seq，进程重启使用新的 bootId。

T029 模板按登录 → 建任务及成员 → 四设备/三子任务 → 启动与设备上报 → 人工换机/旧批次迟到 → V1 定版/复核/存证及精确共享 → 同执行 V2 独立定版/复核 → 其余两项资料 → 核验 → 提交验收/完成 → 撤销 V1/独立授权 V2 → 审计的顺序组织。它需要逐段读取响应并填回变量，不是“发送整个文件”即可完成的脚本；重复整段会新建真实业务。它只操作新任务、新设备，不修改原 M2 保留成果。

## 身份与响应约定

| 标记 | 请求身份及资源范围 |
| --- | --- |
| 匿名 | 状态与登录；T028 静态 GET 另列 |
| 用户 | `Authorization: Bearer <JWT>`；后端按当前数据库身份、业务角色、任务成员或精确版本授权检查 |
| 设备 | 只用 `X-Device-Id` 和 `X-Device-Token`，仅访问 `/device/**`；用户 JWT 不能代替设备令牌，设备令牌也不能代替用户 JWT |

除 HTML/静态资源外，接口返回统一 `{code,message,data,traceId}`。成功请求通常为 HTTP 200；上传完成和人工存证重检返回 HTTP 202，表示意图已接纳。必须查询上传 `COMPLETED` 和版本 `anchor.status=CONFIRMED`，才能分别认定已经定版和已取得确认。`UNKNOWN` 显示结果待确认，不能当作成功或篡改；人工重检不强制确认或生成新的文件版本。

只获得一个版本共享授权时，只能读取、下载和核验该版本，不能读取整个任务、任务审计、另一个版本或修改复核结论。资料复核与链状态互相独立；新版本重新为 PENDING，不继承旧版的 APPROVED/RETURNED。任务完成要求当前执行批次各必交项的最新固定版本达到 APPROVED + CONFIRMED；设备上报 100% 不会直接完成任务。

## 全量 REST 目录

下表路径均省略 `/api/v1`。可选查询参数、字段及正负例以链接的请求文件和当前请求模型为准；列表默认从第 1 页开始，pageSize 最大 100。

### 状态与用户身份（5 项）

| 方法 | 路径 | 身份/用途 | 请求示例 |
| --- | --- | --- | --- |
| `GET` | `/system/status` | 匿名，应用状态 | [t002](t002.http) |
| `POST` | `/auth/login` | 匿名，用户名/密码登录 | [t008](t008.http)、[t029](t029.http) |
| `POST` | `/auth/logout` | 用户，撤销本次 tokenVersion 的登录 | [t008](t008.http) |
| `GET` | `/users/me` | 用户，当前编号、机构和角色 | [t008](t008.http)、[t029](t029.http) |
| `PUT` | `/users/{id}/roles` | 本机构 ORG_ADMIN，适用角色及账号 CAS | [t008](t008.http) |

### 任务、成员与执行批次（13 项）

| 方法 | 路径 | 身份/用途 | 请求示例 |
| --- | --- | --- | --- |
| `GET` | `/tasks` | 用户，可访问任务 SQL 分页 | [t009](t009.http)、[t029](t029.http) |
| `GET` | `/tasks/{id}` | 用户，当前任务成员路径 | [t019](t019.http)、[t029](t029.http) |
| `POST` | `/tasks` | OrgA OWNER_TASK_ADMIN，建立草稿及必交项 | [t009](t009.http)、[t029](t029.http) |
| `PUT` | `/tasks/{id}/requirements` | 委托方任务管理员，维护稳定资料项 | [t009](t009.http) |
| `POST` | `/tasks/{id}/assign` | 委托方任务管理员，分配运营机构/管理员 | [t009](t009.http)、[t029](t029.http) |
| `PUT` | `/tasks/{id}/members` | 相应机构任务管理员，替换本机构成员 | [t009](t009.http)、[t029](t029.http) |
| `POST` | `/tasks/{id}/subtasks` | 运营方任务管理员，绑定已有资料项 | [t009](t009.http)、[t029](t029.http) |
| `POST` | `/subtasks/{id}/executions` | 运营方任务管理员，初次分配/人工换机 | [t016](t016.http)、[t029](t029.http) |
| `GET` | `/subtasks/{id}/executions` | 任务成员，当前及历史批次分页 | [t016](t016.http)、[t029](t029.http) |
| `GET` | `/tasks/{id}/progress` | 任务成员，当前批次进度/缺失/就绪资料 | [t016](t016.http)、[t029](t029.http) |
| `POST` | `/tasks/{id}/start` | 运营方任务管理员，复用 READY 批次启动 | [t009](t009.http)、[t029](t029.http) |
| `POST` | `/tasks/{id}/submit-acceptance` | 运营方任务管理员，正式提交验收 | [t019](t019.http)、[t029](t029.http) |
| `POST` | `/tasks/{id}/complete` | 委托方任务管理员，按当前最新成果完成 | [t019](t019.http)、[t029](t029.http) |

### 设备管理与设备事件（5 项）

| 方法 | 路径 | 身份/用途 | 请求示例 |
| --- | --- | --- | --- |
| `POST` | `/drones` | OrgB OPERATOR_TASK_ADMIN，注册并返回一次性令牌 | [t015](t015.http)、[t029](t029.http) |
| `GET` | `/drones` | 运营方任务管理员，本机构设备分页 | [t015](t015.http)、[t029](t029.http) |
| `GET` | `/drones/{id}` | 运营方任务管理员，本机构设备详情 | [t015](t015.http) |
| `PATCH` | `/drones/{id}/enabled` | 运营方任务管理员，设备启停 CAS | [t015](t015.http) |
| `POST` | `/device/reports` | 设备，HEARTBEAT/PROGRESS，双键幂等 | [t015](t015.http)、[t029](t029.http) |

### 用户与设备上传（8 项）

| 方法 | 路径 | 身份/用途 | 请求示例 |
| --- | --- | --- | --- |
| `POST` | `/upload-sessions` | 任务 OPERATOR_USER，创建/同键找回会话 | [t010](t010.http)、[t017](t017.http)、[t029](t029.http) |
| `GET` | `/upload-sessions/{id}` | 创建会话的用户，查询真实已收片/状态 | [t010](t010.http)、[t017](t017.http)、[t029](t029.http) |
| `PUT` | `/upload-sessions/{id}/parts/{partNo}` | 创建用户，按服务器规格上传原始分片 | [t010](t010.http)、[t017](t017.http)、[t029](t029.http) |
| `POST` | `/upload-sessions/{id}/complete` | 创建用户，冻结并异步定版 | [t011](t011.http)、[t017](t017.http)、[t029](t029.http) |
| `POST` | `/device/upload-sessions` | 设备，在其所属执行批次内创建会话 | [t015](t015.http)、[t016](t016.http)、[t029](t029.http) |
| `GET` | `/device/upload-sessions/{id}` | 创建设备，查询原会话进度 | [t015](t015.http) |
| `PUT` | `/device/upload-sessions/{id}/parts/{partNo}` | 创建设备，原始分片上传 | [t015](t015.http)、[t016](t016.http) |
| `POST` | `/device/upload-sessions/{id}/complete` | 创建设备，异步定版 | [t016](t016.http) |

文件名、声明 MIME 与实际格式均需一致；支持 JPG/JPEG、PNG、PDF、MP4。分片 PUT 使用 `application/octet-stream`，正文为字节，不是 JSON/Base64 或 multipart。多片前先查询会话的 partSize/partCount；同 partNo 重试必须使用相同字节。完整文件定版、版本号分配和存证异步处理，不能把“分片齐”直接当成版本已存在。

### 版本查询、存证及重检（4 项）

| 方法 | 路径 | 身份/用途 | 请求示例 |
| --- | --- | --- | --- |
| `GET` | `/evidence-versions` | 成员/精确授权用户，按任务/资料项 SQL 分页 | [t020](t020.http)、[t029](t029.http) |
| `GET` | `/evidence-versions/{id}` | 成员/精确授权用户，固定版本与存证摘要 | [t012](t012.http)、[t020](t020.http)、[t029](t029.http) |
| `GET` | `/evidence-versions/{id}/anchor` | 成员/精确授权用户，实际持久存证状态 | [t012](t012.http)、[t027](t027.http)、[t029](t029.http) |
| `POST` | `/evidence-versions/{id}/anchor-retries` | 当前运营方任务管理员成员，幂等重新检查 | [t024](t024.http) |

### 异常和人工复核（4 项）

| 方法 | 路径 | 身份/用途 | 请求示例 |
| --- | --- | --- | --- |
| `POST` | `/issues` | 任务 OrgB OPERATOR_USER，登记具体版本异常 | [t018](t018.http)、[t029](t029.http) |
| `GET` | `/evidence-versions/{id}/issues` | 有适用业务角色的任务成员，异常分页 | [t018](t018.http)、[t029](t029.http) |
| `POST` | `/evidence-versions/{id}/review` | 任务 OrgA OWNER_REVIEWER，通过/退回，唯一结论 | [t018](t018.http)、[t029](t029.http) |
| `GET` | `/evidence-versions/{id}/review` | 有适用业务角色的任务成员，独立复核状态 | [t018](t018.http)、[t029](t029.http) |

### 核验（2 项）

| 方法 | 路径 | 身份/用途 | 请求示例 |
| --- | --- | --- | --- |
| `POST` | `/evidence-versions/{id}/verifications` | 成员/精确授权用户，STORED/COPY 实际字节核验 | [t013](t013.http)、[t020](t020.http)、[t029](t029.http) |
| `GET` | `/evidence-versions/{id}/verifications` | 成员/精确授权用户，核验历史分页 | [t013](t013.http)、[t029](t029.http) |

STORED 的查询串必须恰为 `source=STORED`，不带请求体及 Content-Type。COPY 的查询串必须恰为 `source=COPY`，multipart 只包含一个名为 file 的文件项；客户端设置与实际边界一致的 Content-Type。`MATCH`/`MISMATCH` 比较实际字节和固定登记；未确认、读文件失败、链查询失败分别返回对应结果，不混作篡改。

### 共享与短期下载（5 项）

| 方法 | 路径 | 身份/用途 | 请求示例 |
| --- | --- | --- | --- |
| `POST` | `/access-grants` | 有效注册用户，申请一个已知固定版本 | [t020](t020.http)、[t029](t029.http) |
| `GET` | `/access-grants` | mine=true 为本人申请；taskId 为任务审批队列 | [t020](t020.http)、[t029](t029.http) |
| `POST` | `/access-grants/{id}/decision` | 任务 OrgA OWNER_DATA_APPROVER，批准/拒绝 CAS | [t020](t020.http)、[t029](t029.http) |
| `POST` | `/access-grants/{id}/revoke` | 任务 OrgA OWNER_DATA_APPROVER，撤销有效授权 | [t020](t020.http)、[t029](t029.http) |
| `POST` | `/evidence-versions/{id}/download-url` | 成员/精确授权用户，重新鉴权后短期签发 | [t020](t020.http)、[t029](t029.http) |

申请、审批期限使用真实未来 UTC ISO-8601 时间；批准不能晚于申请截止时间。只有申请的那个版本已 APPROVED + CONFIRMED 才能批准。签发记录表示“下载地址签发”，不表示实际下载成功；原始地址在私有客户端中当次使用，不进入示例和报告。撤销后不再签发新地址，已签发地址可能在原 expiresAt 前继续有效，最长 300 秒且不超过授权剩余期。

### 任务审计和证据关系（2 项）

| 方法 | 路径 | 身份/用途 | 请求示例 |
| --- | --- | --- | --- |
| `GET` | `/tasks/{id}/audit-logs` | 任务管理/复核/审批成员，按 action/resourceType/traceId 筛选 | [t021](t021.http)、[t029](t029.http) |
| `GET` | `/tasks/{id}/evidence-relations` | 相同范围，按 requirementId/executionId 查询固定版关系 | [t021](t021.http)、[t029](t029.http) |

## 连续演示脚本

在原服务就绪、JDK 21 已选中且没有其他项目后端/消费者运行时，于仓库根目录执行 `python3 deploy/scripts/run-demo.py`。它使用正式接口生成四设备、三子任务、一次人工换机、四个固定版本及共享/核验流程；成功结果保留在 `.local/full-demo/<runId>/resources.json`、report.json、inspection.json，便于随后在管理界面查看。完整运行方式、保留范围及实际结果见项目 README 和验证记录。

`python3 deploy/scripts/run-demo.py --recheck .local/full-demo/<runId>` 对已有结果复查，不能把整个创建流程重复运行当作复查。保留的 V2 精确授权有有效期（批准约 12 小时，请求约 24 小时），超时后应按实际权限结果解释，不能改数据库时间或伪造有效状态。资源清单仅含安全编号/状态，不放密码、JWT、设备令牌或签名下载地址；设备管理响应中的一次性令牌仍应私密保管。

## 样例文件与专项入口

| 资料 | 用途和边界 |
| --- | --- |
| [data/t029-task.json](data/t029-task.json) | 三个必交资料项的新任务请求，只含公开业务样例；不预置服务器编号、成功状态或凭据 |
| [files/inspection-sample.png](files/inspection-sample.png) | 79 字节合法 PNG，普通单片样例；完整 SHA-256 为 ec81ce39789f63a8f58cd7c6ecaef171b4b944d665ea92d79b2c92175ea2726f |
| [files/inspection-revised.png](files/inspection-revised.png) | 74 字节合法修订 PNG，用于北区 V2，完整 SHA-256 为 720067a8fbaf5b49eb10dde16503ffdc0b97a0e9aad4658e8a379645b392912f；核验 V1 时可作为不同内容副本 |
| 分阶段业务请求 | 各业务接口的前置条件、正文和正负例，按上表选择 t008～t021 的相应文件 |
| [t022](t022.http)、[t023](t023.http)、[t024](t024.http)、[t025](t025.http)、[t026](t026.http)、[t027](t027.http) | 消息、续租、未知态、清理、跨模块并发及真实崩溃的专项入口；不是额外业务 REST 接口 |
| [t028](t028.http) | 管理工作台同源静态 GET、缺失静态 404 和 API 鉴权边界 |
| [多机模拟器](../simulator/README.md)、[通用续传工具](../simulator/upload.py) | 3～5 机模拟与持久重试；样例配置不包含真实设备令牌 |

需要生成修改副本时，在自己的忽略目录中复制文件并修改字节，保留原样例；不要覆盖正式对象。若要展示合法格式的新版本，重新生成合法文件并申报其真实大小。不要把 PNG 文件末尾的任意填充伪称为合法大 PNG；T028 已验证完整格式检查会拒绝它。

运行中断且四个版本已实际 CONFIRMED+APPROVED 时，可用 `python3 deploy/scripts/run-demo.py --resume .local/full-demo/<runId>` 从后续业务步骤继续，不重新上传或登记；失败报告和续跑分别保留。适用检查点、只读复查和成功资源指针见 [演示说明](../docs/t029-demo.md)。
