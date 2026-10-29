# T033 面试说明与简历表述

本材料面向Java后端岗位，依据当前代码和实际验证记录。介绍时先说明业务问题和后端机制，再按追问展开Fabric。完整操作与异常案例见[演示讲稿](demo.md)，业务范围见[需求](01-requirements.md)。这是个人实践项目，设备通过HTTP模拟；简历只保留自己能够解释、复现并真实参与的部分。

## 1. 30秒介绍

> 我做了一个无人机集群巡检资料平台，模拟园区委托方和运营商协作。Java后端负责多机任务、人工换机、分片上传、固定版本、人工复核和精确授权共享。重点处理了上传重试、数据库与消息的一致性、旧批次迟到隔离，以及链结果不明后的恢复。Fabric只登记和查询文件摘要，业务规则与文件保存在链外。项目已经完成真实服务联调、崩溃恢复、短时性能测量和隔离部署验证。

不要在开场逐个罗列中间件。先讲“谁交付什么、如何判断交付合格”，面试官追问一致性时，再画一次上传和存证过程。

## 2. 两分钟展开

第一段讲业务：委托方定义资料要求并分配运营商；运营商拆分子任务、绑定设备和执行批次。设备可以换机，旧设备迟到上报仍归原批次。一个资料项可以有多个固定版本，每版分别复核和存证，任务完成需要检查当前执行的最新必交成果。

第二段讲后端：使用Spring Boot单应用按业务包分层，MyBatis访问MySQL，文件放私有S3兼容存储。上传创建使用请求编号和内容指纹，分片使用编号、长度和SHA-256判重。完成接口返回202，短事务写入业务状态和Outbox，后台定版后再异步存证；消息重复通过持久消费记录、业务唯一约束和租约条件写入收敛。数据库事务不包含长时间文件或链RPC。

第三段选一个难点：我更关注“请求已经生效，但调用方没拿到结果”。例如Fabric提交后进程退出，重新生成交易可能留下重复尝试。我保存原交易及确认检查点，对UNKNOWN保留不确定性，恢复时依据检查点优先查询原commit，或通过登记对账/恢复原交易继续处理；只有明确失败且满足策略时才进入新的尝试。另一类问题是权限在外部文件读取期间改变，保存核验结果时还要重新鉴权。

最后讲证据：已执行AC-01～AC-12、六个真实JVM崩溃窗口及原交易恢复；性能仅使用记录的单机、小数据集和短窗口结果；独立新环境从源码构建、真实三设备演示及整套普通重启也已通过。本项目没有接入真实飞控，也未验证生产高可用或生产规模运行。

## 3. 可核对的简历要点

以下可以用于“个人项目”部分，按实际掌握程度选3～4条，不直接改写为公司线上职责：

- 基于Spring Boot、MyBatis和MySQL实现双机构巡检协作，设计任务—子任务—执行批次—固定版本模型，通过当前执行指针隔离换机后的迟到报告及历史成果，完成复核、验收和审计闭环。
- 实现分片上传幂等与续传，以稳定请求编号、请求指纹、分片长度/摘要及数据库约束处理重试；通过本地事务Outbox、持久消费幂等及带token的租约/续租协调文件定版与异步存证。
- 实现角色、任务成员和精确版本授权组合鉴权，将可见范围放入SQL后再分页；下载签名受300秒及授权剩余期限约束，核验落库前重新检查当前权限。
- 接入Fabric双机构Gateway及两方法Java链码，保存原交易/确认检查点并处理UNKNOWN；通过真实崩溃与恢复验证、固定产物性能测量和隔离部署验证检查实现，明确框架能力及运行边界。

对应代码分别为[TaskService](../backend/src/main/java/com/uav/inspection/inspection/TaskService.java)、[UploadTransactions](../backend/src/main/java/com/uav/inspection/evidence/upload/UploadTransactions.java)、[OutboxPublisher](../backend/src/main/java/com/uav/inspection/messaging/OutboxPublisher.java)、[权限服务](../backend/src/main/java/com/uav/inspection/identity/ResourcePermissionService.java)和[AnchorProcessor](../backend/src/main/java/com/uav/inspection/blockchain/anchor/AnchorProcessor.java)。无法解释某一条时，先补理解或缩小表述，不用“熟练”“精通”代替证据。

## 4. 十个核心追问

### 4.1 为什么不能只在任务上保存一个设备编号？

一次子任务可能先后由两台设备执行，替换编号会丢失前后任和旧资料归属。本项目保留`task_execution`历史，通过子任务的`currentExecutionId`选择当前执行。人工换机在同一事务关闭旧批次、创建新批次、切换指针并记录原因/操作者。旧批次可以保留迟到数据，查询当前成果时只选当前执行内的最新版本。

源码：[TaskService.createExecution / replaceExecution](../backend/src/main/java/com/uav/inspection/inspection/TaskService.java)、[TaskMapper.requirementProgress](../backend/src/main/java/com/uav/inspection/inspection/persistence/TaskMapper.java)、[DeviceTransactions.report](../backend/src/main/java/com/uav/inspection/device/DeviceTransactions.java)。这是人工换机和归属隔离，没有自动调度或路径规划。

### 4.2 如何判断任务完成？旧版通过能否让新版直接通过？

服务端检查资料结构非空、当前执行有效、每项必交资料存在当前执行内的最新固定版本；正式完成时还要求该版APPROVED、CONFIRMED，且没有当前执行的VERIFYING上传。设备进度100%不能代替这些条件。新版本重新PENDING，旧版结论和共享授权均不自动继承；历史批次较大的版本号也不会成为当前成果。

源码：[TaskService.requireAcceptance](../backend/src/main/java/com/uav/inspection/inspection/TaskService.java)、[ReviewService.review](../backend/src/main/java/com/uav/inspection/review/ReviewService.java)、[V10迁移](../backend/src/main/resources/db/migration/V10__issues_and_reviews.sql)。复核记录绑定固定版本且有唯一约束，并发决策通过条件更新及约束共同保护。

### 4.3 上传请求超时重试，为什么不会创建两份会话或覆盖分片？

创建会话时，相同身份、`clientRequestId`和请求指纹复用原会话；同一编号换内容返回幂等冲突。同一会话的同编号分片按实际长度和SHA-256比较，完全一致返回原结果，内容不同拒绝覆盖。并发上传各写唯一候选对象，提交后能明确判定未引用的输家对象才删除；数据库提交结果不明时保留候选，交给引用感知清理。

源码：[UploadService.uploadPart](../backend/src/main/java/com/uav/inspection/evidence/upload/UploadService.java)、[UploadTransactions.create / storePart](../backend/src/main/java/com/uav/inspection/evidence/upload/UploadTransactions.java)、[CleanupProcessor](../backend/src/main/java/com/uav/inspection/evidence/cleanup/CleanupProcessor.java)。续传先查询会话和已有分片，不能以本地“已发送”推定服务端已持久化。

### 4.4 为什么把文件处理放在事务外？对象存储和数据库怎样一起回滚？

长I/O放进事务会延长锁和连接占用，而且本地事务无法回滚已经完成的S3写入。项目拆成准备/领取、外部I/O和最终短事务：最终提交重查状态与租约，再落正式引用。对象写入成功而SQL结果不明时保留候选，清理任务按引用和有效租约判断是否可删；没有宣称MySQL与S3具备跨系统原子事务。

源码：[FinalizationProcessor](../backend/src/main/java/com/uav/inspection/evidence/finalization/FinalizationProcessor.java)、[FinalizationTransactions](../backend/src/main/java/com/uav/inspection/evidence/finalization/FinalizationTransactions.java)、[ProcessingLeaseKeeper](../backend/src/main/java/com/uav/inspection/common/lease/ProcessingLeaseKeeper.java)。处理租约120秒、续租30秒，失去租约后拒绝旧Worker推进结果。

### 4.5 Outbox是否保证消息恰好一次？

本地事务把业务变更与发送意图一起提交；发布器等待broker确认，再按原领取token更新SENT。确认之后、SENT之前崩溃仍可能重发，因此消费者需要事件编号、持久消费状态和业务幂等；业务扫描还能补偿消息路径遗漏。ACK、broker确认和业务完成是不同阶段，不能把其中一个当成端到端恰好一次。

源码：[OutboxWriter](../backend/src/main/java/com/uav/inspection/messaging/OutboxWriter.java)、[OutboxPublisher](../backend/src/main/java/com/uav/inspection/messaging/OutboxPublisher.java)、[ManualMessageDelivery](../backend/src/main/java/com/uav/inspection/messaging/ManualMessageDelivery.java)。详细触发点、恢复证据和边界见[演示异常案例](demo.md)。

### 4.6 为什么既有lockVersion又有FOR UPDATE？是否解决所有死锁？

客户端锁号用于拒绝陈旧页面的写入，数据库父任务行锁用于串行化跨表业务决策；条件更新再次校验版本和状态。成员变更还会涉及多账号的外键锁，项目按账号ID排序取得同一锁顺序，并在锁内重读身份和候选资格。该改动来自一次真实跨任务成员更新死锁；不能泛化为消除了所有可能死锁。

源码：[TaskService.replaceMembers](../backend/src/main/java/com/uav/inspection/inspection/TaskService.java)、[TaskMapper](../backend/src/main/java/com/uav/inspection/inspection/persistence/TaskMapper.java)。代价是同父任务写入可能排队，当前Hikari最大5；没有用“加锁后吞吐更高”替代测量。

### 4.7 使用JWT后，为什么还查询数据库权限？为什么要先鉴权再分页？

角色、账号启用状态、tokenVersion及成员/授权可能变化。当前JWT只保存用户主体、tokenVersion及签发/有效期等必要认证声明，不保存业务角色；服务端查询当前数据库状态。版本列表的COUNT和分页SQL共用可见范围：任务成员路径或指定用户对指定版本的未到期批准授权。先取一页再过滤，会让页数、total和可见结果失真。ORG_ADMIN管理机构账号，不自动拥有全部任务业务权限。

源码：[ResourcePermissionService](../backend/src/main/java/com/uav/inspection/identity/ResourcePermissionService.java)、[AnchorQueryMapper.VISIBLE](../backend/src/main/java/com/uav/inspection/blockchain/anchor/persistence/AnchorQueryMapper.java)。外部文件/链读取期间权限可能变化，核验结果保存前由[VerificationTransactions.save](../backend/src/main/java/com/uav/inspection/evidence/verification/VerificationTransactions.java)再次鉴权。

### 4.8 撤销共享后，下载链接为什么还可能可用？

撤销后应用拒绝读取及新签发，已签出的S3 URL仍由存储端按签名与到期时间验证，不会逐次查询业务授权表。项目将有效期限制为不超过300秒，且不晚于该授权剩余期限；签发审计也只证明签发行为。若要每次下载实时重鉴权，可以评估受控代理或存储侧令牌机制，当前未实现这种能力。

源码：[DownloadService.issue](../backend/src/main/java/com/uav/inspection/sharing/DownloadService.java)、[S3DownloadUrlSigner](../backend/src/main/java/com/uav/inspection/evidence/storage/S3DownloadUrlSigner.java)。不能在演示中承诺“撤销即刻让旧URL失效”。

### 4.9 Redis宕机会不会让设备离线或上报回滚？缓存命中节省了哪些SQL？

设备报告先在数据库提交事件、接收时间和进度，再尽力补写Redis；缓存失败不会撤销已提交事件。在线判定以SQL的`lastSeenAt`和服务器时间为权威，Redis用Lua仅接受更大的接收时间并带TTL。当前查询仍读取SQL，不应声称Redis命中绕过了数据库，或把它说成分布式锁、主要一致性来源。

源码：[DeviceService.view / report](../backend/src/main/java/com/uav/inspection/device/DeviceService.java)、[RedisDevicePresenceCache](../backend/src/main/java/com/uav/inspection/device/RedisDevicePresenceCache.java)。更高读压下能否安全减少SQL是后续设计题，需要先明确权威数据和失效语义。

### 4.10 Fabric具体增加了什么？为什么UNKNOWN不能直接失败重试？

链码只有RegisterEvidence和QueryEvidence：OrgB按evidenceId/版本登记摘要，双方查询同一记录；相同摘要保留首次登记，冲突摘要拒绝。链上保存evidenceId、version、fileHash、submitterMsp、registeredTxId。任务、对象位置、人工复核、授权和核验历史由Java/MySQL维护，不把这些元数据都说成链上可信数据。

提交RPC超时可能发生在交易已被接收之后，项目保存原准备交易/确认请求，用原txId恢复查询或提交；结果不明继续UNKNOWN。明确失败、新尝试与人工重检有单独策略，不能把所有失败都原地无限重试。源码：[EvidenceContract](../chaincode/src/main/java/com/uav/inspection/chaincode/EvidenceContract.java)、[AnchorProcessor](../backend/src/main/java/com/uav/inspection/blockchain/anchor/AnchorProcessor.java)、[GatewayEvidenceLedgerClient](../backend/src/main/java/com/uav/inspection/blockchain/GatewayEvidenceLedgerClient.java)。

摘要一致证明被核验字节与登记版本一致，不证明现场拍摄真实。User1证书代表机构应用身份，不是每个业务上传人的链身份；本机单后端持有两套开发身份也不构成生产机构运维隔离。

## 5. 自己实现与依赖能力

| 层次 | 项目中编写的应用机制 | 依赖提供的基础能力 |
| --- | --- | --- |
| Web与身份 | 角色/成员/版本授权、tokenVersion重验、审计与状态错误语义 | Spring MVC/Security、JWT签名校验、校验与异常基础设施 |
| 数据事务 | 表关系、唯一键选择、短事务边界、CAS条件及锁顺序 | MySQL事务/行锁/约束、Spring事务管理、MyBatis SQL映射 |
| 文件 | 分片规格/摘要判重、定版状态机、候选引用与安全清理 | S3兼容存储、AWS SDK流式请求与SigV4 |
| 消息与恢复 | Outbox、事件信封/消费状态、租约及业务扫描 | RabbitMQ持久队列、发布确认、手动ACK；非自行编写broker |
| 设备 | bootId/seq/eventId幂等、批次归属、单调进度、在线判定 | HTTP、线程库、Redis TTL/Lua；设备由Python模拟 |
| 摘要登记 | 两方法链码业务规则、机构选择、原交易检查点和UNKNOWN恢复编排 | Fabric身份/背书/排序/验证/账本、Gateway SDK；共识协议由Fabric提供 |

## 6. 指标怎么讲、还有哪些改进空间

可以说：在记录的单机环境，按端点分别运行并发1/3/5、每档约10秒闭环查询，30,343次查询无错误，P95为8.78～26.88ms；3/5设备上报共1,756次，五机档P95为66.49ms。详细样本、预热、连接复用和资源表见[性能记录](performance.md)。

必须同时说明：版本列表只有本轮1个可见版本，JVM/连接/缓存有预热，客户端和服务同机；这是小数据集短时结果，不代表生产容量或链TPS。11个正式文件分3/3/5小样本，P95即组最大；PREPARED检查点至VALID持久化含提交/查询/落库，不能改称纯Fabric共识或commit RPC延迟。

部署证据可说：独立源码根、新身份和空业务/账本卷从源码构建，9容器普通stop/up后数据及账本保持，517后端/50链码测试通过。缓存复用、JAR打包元数据差异及完整证据见[部署记录](deployment.md)。不把517单测、923运行检查或42脚本步骤混加为独立测试总量。

后续可以评估更大数据集索引与分页、长期混合负载/GC、任务锁粒度、按任务或业务键扩大消费并行、常规监控告警、生产证书与高可用部署。每项都应先明确一致性约束再测量；这些是改进候选，当前没有宣称已经实现。最终交付仍以[任务清单](03-tasks.md)的T034为准。


## 7. 面试前自查

- 能从一个uploadId追到固定版本、Outbox事件、链提交及原txId，区分请求接纳与最终业务完成。
- 能现场解释一个409、一个UNKNOWN和一次旧批次迟到结果，并指出对应事务、SQL条件及验证记录。
- 能解释上表中至少一个设计代价，例如父任务锁的串行开销或短期签名的撤销窗口。
- 能说清实测环境、样本及哪些能力没有实现；引用本轮真实报告，不复述未经核对的行业QPS。
