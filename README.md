# 无人机集群巡检数据共享与证据溯源平台

面向园区委托方与无人机运营商，以Java后端管理多机巡检任务、分片上传、固定版本、人工复核、授权共享和摘要核验；Hyperledger Fabric只登记和查询文件摘要，文件与业务元数据保存在链外。

**0.1.0 MVP 已于 2026-10-29 完成交付核对，M0～M5、T001～T034 已完成。** AC-01～AC-12、性能实测、隔离部署与普通重启有实际证据；当前为单实例应用、双机构、单通道、两方法链码和3～5台HTTP设备模拟器。版本、产物摘要、验收范围及已知限制见[最终交付说明](docs/delivery.md)，逐项经过见[任务清单](docs/03-tasks.md)和[验证记录](docs/verification.md)。

## 首次部署

以下命令均在项目根目录运行。已验证Linux x86_64、Temurin JDK21.0.12、Maven3.9.16、Python3.10.12、Docker29.7.2及Compose5.4.0。Fabric准备工具固定Linux amd64；其他系统尚未验证。前端是JAR内的同源静态页面，普通运行不需要额外前端服务。

先让当前终端的`JAVA_HOME`指向已安装的JDK21，并把`$JAVA_HOME/bin`放到PATH前面。系统默认JDK可能不是21，`.env`不会切换Java。确认工具后生成一次性本地凭据：

```bash
java -version
mvn -version
docker compose version
python3 deploy/scripts/init-env.py
python3 deploy/scripts/init-demo-credentials.py
```

两个初始化脚本保留已有文件。`.env`及`.local/demo-credentials.json`均为0600，内含随机秘密，禁止提交。配置按字面读取，不要通过`source .env`执行。修改配置文件中的密码不会修改已有卷内账号。

构建应用、链码并启动基础设施：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -Pdatabase-probe,fabric-probe clean verify
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f chaincode/pom.xml clean verify
docker compose --env-file .env -f deploy/compose.yml config --quiet
docker compose --env-file .env -f deploy/compose.yml up -d --wait --wait-timeout 240
```

首次构建需要取得Maven依赖和固定摘要镜像。离线缓存已经准备好时，可给Maven加`-o`；没有缓存不能把离线命令当作联网安装替代。`config --quiet`只校验配置，不输出展开后的秘密。

首次准备新的Fabric身份、通道和链码，再启动应用：

```bash
python3 deploy/fabric/prepare.py
python3 deploy/fabric/network.py up
python3 deploy/fabric/network.py channel
python3 deploy/fabric/chaincode.py deploy
python3 deploy/fabric/network.py verify
python3 deploy/scripts/run-backend.py --demo --ledger
```

`prepare.py`只在全新目录生成身份，已有材料必须与固定版本和指纹一致；`channel`与`deploy`复用已有相同通道/定义并拒绝静默升级。首个应用启动自动执行Flyway V1～V14，`--demo`只在local/database允许，用于初始化6账号和1设备；等待日志出现`Demo data ready`。演示初始化不创建业务任务，不伪造完成或存证状态。

打开 `http://127.0.0.1:8080/`。用户名是owner-admin、owner-reviewer、owner-approver、operator-admin、operator-user、operator-outsider；各自随机密码只在本机`.local/demo-credentials.json`中。页面不会预填密码。另需端口时使用进程变量，例如 `SERVER_PORT=8081 python3 deploy/scripts/run-backend.py --ledger`；启动器不读取`.env`里的SERVER_PORT。

## 日常启动、停止与数据保留

初次初始化完成后，通常启动：

```bash
docker compose --env-file .env -f deploy/compose.yml up -d --wait --wait-timeout 240
python3 deploy/fabric/network.py up
python3 deploy/scripts/run-backend.py --ledger
```

普通启动不需要重新生成凭据、身份或账本，也不需要再次部署链码。应用前台运行，Ctrl+C停止；随后可停止依赖并保留卷：

```bash
python3 deploy/fabric/network.py stop
docker compose --env-file .env -f deploy/compose.yml stop
```

再次执行日常启动即可继续原数据。不要在日常操作中删除卷、移除`.local/fabric`或重建`.env`。业务库使用Flyway迁移，不能修改已应用的旧迁移。

## 真实演示与模拟器

停止前台后端后，自动演示工具会独占启动自己的随机端口应用，使用正常消息处理和真实Fabric，不应与另一个项目后端或探针同时运行：

```bash
python3 deploy/scripts/deployment_demo.py
```

它启动真实三设备模拟器，再完成四设备/三子任务、人工换机、旧新版本上传/复核、精确共享、核验和任务完成。成功结果保留在数据库、S3和账本；本轮地址、版本、交易、检查记录位于`.local/full-demo/<runId>/`，最新指针为`.local/full-demo/latest.json`。复查同一批成果：

```bash
python3 deploy/scripts/run-demo.py --recheck .local/full-demo/<runId>
```

将`<runId>`替换为实际目录。复查只读取已有业务、对象与账本，认证审计追加；不会再次上传或上链。单独模拟器的私有配置及续传方式见[模拟器说明](simulator/README.md)，完整保留演示见[T029说明](docs/t029-demo.md)。

## 项目讲解与面试准备（T033）

[演示讲稿](docs/demo.md)按业务流程、架构、约10分钟讲解顺序和两个真实异常案例组织；[面试材料](docs/interview.md)提供30秒/2分钟介绍、简历要点及十个关联源码的追问。重点是Java任务/批次、可靠上传、资源权限和恢复编排，Fabric保持两方法摘要登记的定位。

异常案例分别解释“发布确认后、SENT前崩溃”与“链交易结果不明时恢复原交易”，使用既有T030证据；完整演示引用T032真实三设备/四版本记录，指标引用T031对应负载，未为整理材料重新生成业务或压测。

## 隔离部署复现（T032）

已有当前产物的T030成功报告、保留演示及固定缓存，且无其他项目后端/探针运行时，可执行以下命令，验证从干净业务状态开始的完整部署与普通重启；该入口在同机新建隔离环境，不重置现有成果：

```bash
python3 deploy/scripts/verify-deployment.py
```

工具复制源码，排除原`.env`、凭据、targets、身份及账本；只复用已校验的Maven/归档/镜像缓存。新项目使用随机宿主端口、独立卷、新证书及独立链码镜像标签，完成后保留为停止状态。报告位于`.local/deployment-repro/<runId>/`，复现边界、恢复命令及实际结果见[部署复现记录](docs/deployment.md)。

## 地址与配置

| 服务 | 默认宿主地址 |
| --- | --- |
| 工作台/后端 | http://127.0.0.1:8080 |
| MySQL / Redis | 127.0.0.1:3306 / 127.0.0.1:6379 |
| RabbitMQ AMQP / 管理 | 127.0.0.1:5672 / http://127.0.0.1:15672 |
| S3 API | http://127.0.0.1:9000 |
| Fabric OrgA / OrgB Gateway | localhost:7051 / localhost:9051 |

所有宿主监听仅绑定loopback，CaaS9999只在各自项目网络内提供本地开发明文连接。S3实现是Garage2.4.1，桶为私有，签名必须使用浏览器实际可达的地址。配置项见[.env.example](.env.example)，业务基础设施与Fabric细节分别见[deploy/README.md](deploy/README.md)、[Fabric说明](deploy/fabric/README.md)。

同机多套环境必须同时隔离源码根、Compose项目名、宿主端口、卷、身份和链码镜像。只改端口或cwd不能隔离所有脚本；不要在已有材料上更改namespace。修改Fabric Peer宿主端口时，两个GATEWAY_ENDPOINT也须同步。完整规则见[部署复现记录](docs/deployment.md)。

## 验证与性能

完整矩阵需要已有M2及完整演示成果。上述`deployment_demo.py`创建完整演示；全新环境还应先执行一次`python3 deploy/scripts/verify-mvp.py`创建保留的M2成果，已有成果不必重建。T030另需按[后端浏览器说明](backend/README.md#最小管理界面与浏览器联调t028)准备Node、Playwright和Chromium；普通部署不需要这些依赖。准备好并停止其他应用后执行：

```bash
python3 deploy/scripts/verify-final.py
python3 deploy/scripts/run-performance.py
```

两者都要求当前环境独占，执行真实业务并实际追加链登记。T030入口新执行17组件并逐项验证12个AC，不能用历史PASS替代；改动生产代码后应重建并开启新验收矩阵。[性能记录](docs/performance.md)来自已验收产物：30,343次查询、1,756次正式上报和11次正式上传全成功，查询P95为8.78～26.88ms，五机上报P95为66.49ms。202接收、定版、确认观测和DB检查点分别统计；短时同机结果不代表生产TPS/SLA。

## 项目文档

| 文档 | 内容 |
| --- | --- |
| [需求说明](docs/01-requirements.md) | 功能范围、权限及 12 项验收场景 |
| [技术设计](docs/02-design.md) | 模块、数据、接口、状态及异常恢复 |
| [开发任务](docs/03-tasks.md) | 阶段、依赖及完成条件 |
| [最终交付](docs/delivery.md) | 0.1.0 MVP 版本、完成日期、产物绑定与已知限制 |
| [验证记录](docs/verification.md) | 实际环境、固定版本、执行证据及历史修正 |
| [配置样例](.env.example) | 当前应用和部署脚本的本地配置约定 |
| [后端说明](backend/README.md) | 构建、运行、包结构和 HTTP 基础约定 |
| [管理界面说明](backend/README.md#最小管理界面与浏览器联调t028) | 同源工作台、分片续传、身份边界及浏览器验证命令 |
| [T002 HTTP 示例](examples/t002.http) | 状态接口、请求标识及 404/405 调用 |
| [T008 HTTP 示例](examples/t008.http) | 登录、当前身份、角色修改、退出及令牌失效 |
| [T009 HTTP 示例](examples/t009.http) | 双机构创建/分配任务、维护成员、细化资料、分配设备及启动 |
| [T010 HTTP 示例](examples/t010.http) | 创建上传会话、上传附带 PNG、查询进度及同片重试 |
| [T011 HTTP 示例](examples/t011.http) | 请求异步定版、重复完成及轮询版本编号 |
| [T012 HTTP 示例](examples/t012.http) | 固定版本详情、存证状态、实际交易编号及越权拒绝 |
| [T013 HTTP 示例](examples/t013.http) | 原文件/相同副本/修改副本核验、历史查询与权限拒绝 |
| [T014 HTTP 示例](examples/t014.http) | 从登录到任务、上传、异步定版、真实存证和文件核验的完整调用顺序 |
| [T015 HTTP 示例](examples/t015.http) | 设备登记、独立令牌上报、重复事件、设备上传和启停 CAS |
| [T016 HTTP 示例](examples/t016.http) | 并发换机、执行历史、当前进度及旧批次历史补传 |
| [T017 HTTP 示例](examples/t017.http) | 多分片进度、乱序、重试、冻结和多版本归档 |
| [T018 HTTP 示例](examples/t018.http) | 版本异常登记、分页、人工通过/退回及结论查询 |
| [T019 HTTP 示例](examples/t019.http) | 提交验收、确认完成、当前批次资格及 completedAt |
| [T020 HTTP 示例](examples/t020.http) | 指定版本申请、审批、拒绝、撤销、可见版本分页及短期下载 |
| [T021 HTTP 示例](examples/t021.http) | 按任务分页筛选审计，以及固定版本的业务关系摘要 |
| [T022 HTTP 示例](examples/t022.http) | 完成接口的重试与轮询，以及双 JVM 消息故障验证入口 |
| [T023 HTTP 示例](examples/t023.http) | 长 I/O 续租、过期 Worker 与应用重启后恢复的验证入口 |
| [T024 HTTP 示例](examples/t024.http) | 人工重新检查的申请、幂等、权限及原交易恢复验证入口 |
| [T025 HTTP 示例](examples/t025.http) | 清理验证入口，以及上传状态与任务审计的观察方法 |
| [T026 HTTP 示例](examples/t026.http) | 权限、分页及跨模块并发回归入口和观察方法 |
| [T027 HTTP 示例](examples/t027.http) | 跨 JVM 故障矩阵、恢复入口和原交易观察方法 |
| [T030 最终功能验收](docs/t030-acceptance.md) | 17 个新组件、12 项 AC、原数据护栏及同构建恢复方法 |
| [T031 性能与资源实测](docs/performance.md) | 查询/多机/上传原始负载、真实确认耗时、CPU/RSS及测量限制 |
| [T032 部署复现](docs/deployment.md) | 干净源码/数据隔离、真实模拟器、整套重启与原成果保护 |
| [T033 演示讲稿](docs/demo.md) | 业务/架构、完整演示、两类异常恢复及指标讲法 |
| [T033 面试材料](docs/interview.md) | 项目介绍、简历要点、源码追问及实现/依赖能力边界 |
| [T029 全量接口目录](examples/README.md) | 48 项实际接口、身份/变量及样例对应关系 |
| [T029 完整演示](docs/t029-demo.md) | 四设备/三子任务、换机、旧新版本、共享、核验及保留复查 |
| [T028 界面与验证示例](examples/t028.http) | 同源工作台、续传边界、浏览器联调入口及静态/API 权限检查 |
| [多机模拟器](simulator/README.md) | 3～5 台设备并行、断网重试、持久请求及上传阶段恢复 |
| [通用续传客户端](simulator/upload.py) | 用户/设备文件上传、私有检查点、查询服务端缺片后继续上传 |
| [M2 演示说明](docs/m2-demo.md) | 自动验收、保留结果复查和面试演示步骤 |
| [基础设施说明](deploy/README.md) | 四个业务服务、地址、持久卷及验证命令 |
| [Fabric 网络说明](deploy/fabric/README.md) | 固定版本、通道创建、证书及保留账本的启停 |
| [Java 链码说明](chaincode/README.md) | 两个合约接口、参数、权限、幂等及 CaaS 部署 |


## 能力边界

无人机是HTTP模拟器，不包含真实飞控、路径规划或自动缺陷识别。摘要核验只能证明文件与登记版本一致，不证明现场内容真实；机构应用身份代表其机构，链外成员/复核/授权元数据并未逐项上链。单Orderer、开发证书和本地CaaS连接用于开发演示，生产高可用、证书轮换和公网部署不在本轮验证范围。
