# T032 部署复现与数据保留

T032于2026-10-28完成。已从不含运行数据的源码副本构建并启动完整环境，执行真实三设备模拟器及四版本业务闭环，再对整套新环境做普通停止/恢复。42个执行步骤通过；新、原两个环境的数据分别核对，M5仍待T033/T034收尾。

## 1. 复现范围

本轮是同一Linux主机上的独立源码目录、新凭据、新证书、空业务卷和空账本，复用已校验的下载归档、Maven依赖及固定摘要镜像缓存。未复制原`.env`、demo凭据、targets、Fabric工具安装目录、身份、运行清单或业务/账本数据。它证明从干净业务状态部署和重启可用，不代表全新OS安装、冷网络下载或其他CPU/操作系统平台已验证。

| 项目 | 实测结果 |
| --- | --- |
| 环境 | Ubuntu22.04.5、Linux6.8.0-138、x86_64；Temurin21.0.12+8、Maven3.9.16、Python3.10.12、Docker29.7.2、Compose5.4.0 |
| 运行标识 | `20261028T143649Z-8cd56664`，PASS，42步骤 |
| 时间 | 2026-10-28 14:36:49.645～14:41:13.503 UTC；北京时间22:36:49.645～22:41:13.503 |
| 工作副本 | `.local/deployment-repro/20261028T143649Z-8cd56664/workspace/`，369个源码/文档/示例文件，清单及SHA保留 |
| Compose项目 | `uav-repro-171890206f1d-infra`、`uav-repro-171890206f1d-fabric` |
| 独立链码镜像 | `uav-inspection/repro-171890206f1d:0.1.0`，不覆盖原tag |
| 从源码构建 | 后端517项、链码50项测试通过，失败/错误/跳过均0；targets均重新生成 |
| 部署工具测试 | 32项离线作用域、配置、模拟器、失败收尾测试通过 |
| 初始化 | Flyway V1～V14；2机构、6账号、1设备；任务、上传、版本及设备事件初始均0 |
| 业务运行 | 142次记录API/923项检查；另有真实模拟器6次上报和实际签名下载GET |
| 普通重启 | 9个容器全部停止再恢复，复用同一容器、镜像、持久卷；24表91行业务数据、迁移、配置及凭据完全相等 |
| 对象与链 | 4正式对象实际GET后大小/SHA保持；双机构账本和原commit有效，区块4～7；链高度8及区块哈希保持 |
| 原环境保护 | 原24表250行及全部字段/BLOB、迁移、凭据、原10正式对象、原容器/镜像/卷保持；原链高度98及哈希保持 |
| 最终状态 | 原环境继续运行；新环境保留为停止状态，不删除卷或账本 |

服务版本与原环境相同：MySQL8.4.11、Redis7.4.11、RabbitMQ4.2.9-management、Garage2.4.1、Fabric2.5.16；链码shim2.5.11。四个业务镜像、两个Fabric节点镜像和JRE基础镜像均固定摘要；具体锁见`.env.example`、`deploy/fabric/versions.json`与`chaincode/Dockerfile`。

## 2. 首次部署与日常运行

面向新开发者的完整命令以[根README](../README.md#首次部署)为准，所有命令在所选项目根目录执行。先选择JDK21，然后初始化本地配置/演示凭据、构建两个Maven模块、启动四个业务服务、准备新Fabric材料、建通道/部署链码，最后运行`run-backend.py --demo --ledger`。首次启动自动迁移；不得通过清库或回改旧迁移解决升级。

普通启动使用`run-backend.py --ledger`，按需显式启用`--cleanup`；只有首次本地演示初始化需要`--demo`。日常只执行Compose `up`和Fabric `up`；停止使用各自的`stop`，不需要重建身份、重新登记文件或重新部署定义。

`.env`不会自动切换JAVA_HOME，也不会被Spring直接读取。启动器按字面读取既有服务配置；需要替代Web端口时用进程`SERVER_PORT`。原凭据应保留，重新生成文件不会更新已有数据库/Redis/RabbitMQ账号。查看随机演示密码只能读取本机0600凭据文件，页面及公开文档不预填密码。

## 3. 同机隔离配置

独立环境必须有独立源码根目录，因为脚本的ROOT来自脚本位置，单纯更改cwd不能隔离`.env/.local`。源码复制显式排除隐藏运行配置、targets、私有目录并拒绝路径穿越/符号链接逃逸；子进程去掉继承的服务地址、凭据、Spring及Compose覆盖，避免原设置压过新`.env`。

| 配置 | 默认值 | 作用 |
| --- | --- | --- |
| `INFRA_COMPOSE_PROJECT_NAME` | uav-inspection | 四业务服务的项目/网络/卷；基础设施探针状态绑定该项目 |
| `FABRIC_COMPOSE_PROJECT_NAME` | uav-inspection-fabric | 两Peer、Orderer与两CaaS的项目/网络/卷 |
| `FABRIC_ORDERER_PORT` / `FABRIC_ORDERER_ADMIN_PORT` | 7050 / 7053 | 只调整宿主端口 |
| `FABRIC_ORGA_PEER_PORT` / `FABRIC_ORGB_PEER_PORT` | 7051 / 9051 | 只调整宿主Gateway/CLI端口 |
| `FABRIC_CHAINCODE_IMAGE` | uav-inspection/evidence-chaincode:0.1.0 | 复现时须独立显式tag，避免替换原全局镜像标签 |
| `FABRIC_ORGA_GATEWAY_ENDPOINT` / `FABRIC_ORGB_GATEWAY_ENDPOINT` | localhost:7051 / localhost:9051 | 必须与上述两个Peer宿主端口同步修改 |
| `DB_PORT`、`REDIS_PORT`、`RABBITMQ_PORT`、`RABBITMQ_MANAGEMENT_PORT`、`S3_PORT` | 原默认端口 | 隔离五个业务宿主端口；S3_ENDPOINT/PUBLIC_ENDPOINT也同步 |

内部DNS、节点监听端口及锚节点保持不变。新材料记录`deployment_namespace`，后续准备、启动、停止、移除及部署必须匹配同一项目、端口和镜像；漂移直接拒绝。历史未带标记的原材料只允许原默认namespace，兼容旧环境且不重写它。

本轮新宿主端口分别为MySQL37767、Redis42159、RabbitMQ47779/32839、S3 37819、Orderer44297/46759、OrgA45715、OrgB44373。它们是该次记录，不是下一次固定端口；脚本每轮重新选择并交由Docker实际绑定，若竞争导致端口不可用则如实失败。

Fabric镜像还会在`/etc/hyperledger/fabric`和`/var/hyperledger`创建匿名卷。校验只允许这两个位置上的64位随机卷名；实际业务/账本命名卷仍必须属于显式项目。普通stop/up比较所有卷标识，不把匿名卷误称为业务账本卷。

## 4. 一键验证流程

已有当前生产产物的T030成功报告、原保留成果和固定离线缓存，且无其他项目后端/探针运行时，在原项目根目录、选定JDK21后执行：

```bash
python3 -m unittest discover -s deploy/scripts -p 'test_deployment*.py' -v
python3 -m unittest discover -s deploy/scripts -p 'test_verify_deployment.py' -v
python3 -m unittest discover -s deploy/fabric -p 'test_settings.py' -v
python3 deploy/scripts/verify-deployment.py
```

这32项测试与实际42步骤不同，不把检查数或脚本步骤数当作独立业务单测。实际流程为：

1. 只读记录原24表/迁移/凭据、原网络及容器/镜像/卷；校验原生产产物仍绑定T030成功版本。
2. 创建新源码副本，复制并核对两个固定归档，复用独立复制的依赖缓存；生成新秘密并分配独立项目与端口。
3. 从零targets离线`clean verify`两个模块；校验生产源码及classes/资源与原产物相等。
4. 启动空业务卷，运行基础设施探针seed→stop/up→check，清理自己的探针表后再让Flyway迁移。
5. 从固定工具/样例生成全新证书和genesis，启动新网络、加入通道、安装批准/提交新链码定义。
6. 首次`--demo --ledger`实际迁移和初始化；状态、首页、`/ui/app.js`HTTP检查通过后停止自己的应用。
7. `deployment_demo.py`启动正常打包应用；三设备模拟器各2轮，3个HEARTBEAT加3个PROGRESS，共6个新事件；随后原业务演示完成换机、4个版本、复核、共享/撤销、核验与正式完成。
8. 记录新24表快照、正式对象及链快照，另写数据库/Redis/RabbitMQ/S3持久探针；停止全部9容器，再用同样配置恢复。
9. 重复prepare/channel/deploy验证保留材料与已有定义；基础设施探针验证原数据并精确清理，重复初始化脚本保留配置及凭据。
10. 普通`--demo --ledger`启动再次通过；停止后只读复查原演示（30次API/217项检查），再比较24表、对象、账本和容器/卷。
11. 停止并保留新环境；重新实读原10对象、原24表及原链，与最初基线比较，全部满足后才PASS。

模拟器自身配置必须临时写入0600文件，包含一次性设备令牌；成功、失败、超时均删除，状态及报告不含token。重查的应用禁用后台处理以保持只读；普通后台启动另由两次`--demo --ledger`启动及真实四版本处理证明，不把只读重查称为故障恢复负载。

## 5. 实际证据与复查

本轮根目录为`.local/deployment-repro/20261028T143649Z-8cd56664/`，目录0700，报告/日志0600：

| 文件 | 证据 |
| --- | --- |
| report.json、42份编号日志 | 每步骤状态/时长/日志SHA、构建结果、重启及原环境护栏 |
| source-manifest.json | 369份实际源码/文档/示例的路径和SHA |
| post-checks.json | 679个JAR条目、195个依赖、独立密钥、临时配置删除和110份生成JSON/日志隐私审计 |
| original-before/after.json | 原24表250行及敏感BLOB摘要、完整迁移和配置/凭据SHA |
| copy-before/after-restart.json | 新24表91行完全相等；含4版本、3核验、16设备事件，审计另行追加 |
| original-chain-before/after.json | 原链高度98、当前/前块哈希、genesis与身份材料一致 |
| copy-chain-before/after.json | 新链高度8、哈希、genesis和namespace一致 |
| original-objects.json | 原10正式对象字节/SHA及原双机构登记实读 |
| copy-objects-before/after.json | 新4正式对象（3×79B、1×74B）及双机构登记前后一致 |
| workspace/.local/full-demo/20261028T143857Z-0e100632/ | 业务创建及inspection；simulator.log、summary和3份state的SHA |

原业务快照SHA为`15ac4cf5d0f59ef7af6df9da77d7a29dc338da2308645482e3f90e8f3d04711d`；新业务重启前后SHA均为`5585b5fbc0cd2a7b68f95004fbd87ffc3494e866e7494e31850230bdfab03895`。两个快照域均不包含追加审计表；登录审计允许追加，原六账号没有因复现被登录/退出或重置。

4个新登记只存在于独立新账本，区块4～7均VALID/code0，双机构五字段与版本完全相等。原链没有新增交易；新网络第二次复用deploy前后没有新增块，首次部署则正常创建批准及定义。首次deployment.json被幂等部署更新，未保存两份JSON；对应两次日志、实际同一镜像manifest及前后链快照共同证明保留。

链码JAR从源码重建的SHA仍为`612301eb01625740f04dae8dae55fb1d8c1ce62f9f5bfb6f09ea1d81ed35bb17`。后端源码和完整classes/资源摘要也与原产物相等；后端JAR整体SHA不同，但679个ZIP条目的解压内容逐字节相同，差异在打包元数据，不能称整个JAR字节级复现。195条依赖文件名及实际JAR摘要逐项相等；classpath清单因绝对目录不同而SHA不同。两套各16个私钥摘要集合无交集，均0600；110份生成JSON/日志的已知秘密、JWT/私钥/签名地址及业务原始对象键检查通过。预期含秘密的.env/凭据文件另查权限，不纳入内容无秘密结论；新API设备令牌在运行时由写入护栏精确检查，停止后无法再按其原值扫描。

首次尝试`20261028T143448Z-6e1eaf3c`只读预检因匿名卷被过严拒绝，没有创建或启动新环境；修正卷类型规则和失败收尾后，以新目录完整运行并通过。失败报告保留，未修改为PASS。

## 6. 恢复本轮保留环境

以下仅针对本轮副本；先停止其他项目应用/探针，选好JDK21，然后在其源码根操作：

```bash
cd .local/deployment-repro/20261028T143649Z-8cd56664/workspace
docker compose --env-file .env -f deploy/compose.yml up -d --wait --wait-timeout 240
python3 deploy/fabric/network.py up
python3 deploy/scripts/run-backend.py --ledger
```

需要只读重查时先Ctrl+C停止自己的前台应用，再执行：

```bash
python3 deploy/scripts/run-demo.py --recheck .local/full-demo/20261028T143857Z-0e100632
```

共享授权按真实时间自然到期；现有完整演示重查支持APPROVED/EXPIRED，到期时验证非成员空列表及详情403，正常到期本身不会造成复查失败。若检查过程中跨过到期边界，或授权被另行撤销/成员关系改变，应据实际状态判读，不强改旧授权或把预期失权当成数据丢失。业务完成、正式对象及ledger记录仍可复核；需要新的写入演示时另建新运行。

## 7. 已知限制与下一步

当前是单实例应用、单Orderer的本地开发部署，非生产HA、公网TLS/证书轮换或备份恢复方案。镜像/工具固定Linux amd64；联网首次下载及其他平台不是本次验证范围。重新构建生产源码后仍需按变更执行相应测试和新功能验收，本轮没有重复全部17组件T030矩阵。

完整MVP尚未收尾。下一项T033整理演示与面试材料，随后T034做最终交付核对；本页不能代替这些任务。
