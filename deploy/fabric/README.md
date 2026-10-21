# Fabric 开发网络

T004 沿用官方 test-network 的组织和通道配置，为本项目单独管理两个 Peer、一个 Raft Orderer、证书及三份账本卷。首版只创建 `mychannel`，T005 使用独立 Java 链码服务（CaaS），Java Gateway 调用在 T006 完成。

## 固定版本

| 内容 | 基线 |
| --- | --- |
| Peer、Orderer、主机 CLI | Fabric 2.5.16，镜像同时固定内容摘要 |
| 主机工具 | Linux amd64 官方归档，SHA-256 见 versions.json |
| 官方 samples | commit `5789681b4f4d24e58fa40f19a69f5496892374b6` |
| 通道及共识 | mychannel；etcdraft；一个排序节点；无系统通道 |
| 状态库 | Peer 默认 LevelDB |
| 开发身份 | cryptogen 生成；每个业务机构有 Admin、User1；Peer 和 Orderer 启用 TLS |
| Java 链码 | shim 2.5.11；JDK/JRE 21.0.12；evidence 0.1.0、sequence 1 |

锁定信息见 [versions.json](versions.json)，实际镜像摘要见 [compose.yml](compose.yml)。samples 使用已实际解析的完整提交，不假定存在与 Fabric 同名的版本标签。

配置来源为 [固定提交的官方 test-network](https://github.com/hyperledger/fabric-samples/tree/5789681b4f4d24e58fa40f19a69f5496892374b6/test-network)，其 Apache-2.0 许可保留在 `.local/fabric/SAMPLES-LICENSE`。初始化适配包括：只生成一个排序节点的身份；将两个组织的锚节点直接写入创世配置；按官方 core.yaml 配置将运行副本的 vm.endpoint 置空，关闭 Docker 链码构建后端，保留默认外部链码构建器。不会执行官方 network.sh 的清理或重置逻辑。

## 首次准备与启动

在项目根目录执行，需要 Linux amd64、Python 3.9+、Docker daemon、Compose 插件及网络访问：

```bash
python3 deploy/fabric/prepare.py
python3 deploy/fabric/network.py up
python3 deploy/fabric/network.py channel
python3 deploy/fabric/network.py verify
```

prepare 首次下载锁定的官方工具与样例归档、核对 SHA-256、生成身份和通道块。全部存放在忽略提交的 `.local/fabric/`，不安装到系统目录，也不改变全局 JDK。再次执行会校验并复用现有材料；材料不完整或与锁定信息不一致时拒绝自动替换。

up 启动项目名为 `uav-inspection-fabric` 的三个节点并等待内部 `/healthz` 通过。镜像未包含 curl，健康检查使用已有 Bash 向本机 operations 端口发送 HTTP 请求。operations 端口不发布到宿主机。

channel 通过 mTLS Participation API 让 Orderer 加入通道，再用各组织管理员让 Peer 加入。已有通道和 Peer 成员关系会复用。verify 实际查询两个 Peer 的通道与账本信息，查询 Orderer 活跃状态，并从 Orderer 取回 block 0 核对创世配置；报告写入 `.local/fabric/status.json`。

## 组织与地址

| 业务映射 | MSP | 宿主端点 | 容器网络端点 |
| --- | --- | --- | --- |
| OrgA 委托方 | Org1MSP | localhost:7051 | peer0.org1.example.com:7051 |
| OrgB 运营商 | Org2MSP | localhost:9051 | peer0.org2.example.com:9051 |
| 独立排序组织 | OrdererMSP | localhost:7050 | orderer.example.com:7050 |
| 排序节点管理 API | mTLS | localhost:7053 | orderer:7053 |

所有发布端口仅绑定 `127.0.0.1`。两个业务组织共享一个通道；排序组织不作为第三个业务参与方。容器、网络、卷均使用本项目独立前缀。

`gateway-paths.json` 记录每个组织 User1 的证书、私钥文件路径及 Peer TLS 根证书路径；只记录路径，不输出私钥内容。T006 的 verify-gateway.py 读取该表并向 Java 探针传递配置；默认 Web 应用不读取它。网络工具和节点版本由 versions.json 与 Compose 固定，根目录 `.env` 的 Fabric 字段作为显式连接的参数契约。

Peer 未挂载宿主 Docker socket，也未启用特权模式。Java 链码由宿主构建镜像后以 CaaS 独立运行，Peer 默认外部构建器只处理连接包，不在节点内运行 Maven 或 Docker 构建。

## 构建、部署与验证链码

先选择 JDK 21，在项目根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f chaincode/pom.xml clean verify
python3 deploy/fabric/chaincode.py deploy
python3 deploy/fabric/verify-chaincode.py
```

部署脚本复用现有通道：生成确定性的 CaaS 连接包，分别安装到两组织，构建固定 JRE 摘要的本地镜像，启动两个服务，再由管理员批准并提交一个 evidence 定义。静态背书策略为 `AND('Org1MSP.peer','Org2MSP.peer')`；脚本解码已提交的策略核对两个 Peer 角色。合约写权限由交易调用者 MSP 决定，双背书不赋予 OrgA 登记权限。

两个组织使用同一 JAR、同一合约，各有一个实例，连接地址为 `evidence-org1:9999`、`evidence-org2:9999`。CaaS 包地址不同，因此 package ID 不同，这是正常现象。Java 官方 ContractRouter 读取 `CORE_CHAINCODE_ID_NAME` 和 `CHAINCODE_SERVER_ADDRESS` 启动服务。[官方 Java CaaS 说明](https://hyperledger-fabric.readthedocs.io/en/release-2.5/cc_basic.html)

9999 只在本项目 Docker 网络内开放，不映射宿主端口；这段 gRPC 连接使用明文，仅用于本机开发验证。Peer/Orderer 的客户端 TLS 配置继续有效。链码服务使用非 root 用户、不挂载私钥或账本，不连接数据库或对象存储。

`.local/fabric/chaincode/` 保存包、runtime.env、构建产物锁、deployment.json 和 verification.json，不提交版本库。部署记录包含 JAR 摘要、镜像 ID、package ID 及实际策略。CaaS package ID 只绑定连接包，因此脚本另行校验 JAR/镜像构建文件摘要，拒绝在原版本下静默替换；正式修改合约须明确升级版本及 sequence。可重复部署复用已安装包、批准和定义，不重复创建通道。

验证脚本的业务调用全部使用两个组织的 User1：检查查询空记录、OrgB 登记、两机构读取、同摘要不写状态且保留首次交易编号、摘要冲突、OrgA 写入拒绝、非法版本及不同版本隔离。成功登记须等待事件，并从两个 Peer 的 QSCC 区块读取 `VALID=0`；拒绝项须收到明确的链码错误 payload，不能把连接失败算作通过。每次运行生成新的测试证据编号，保留这些真实账本记录。

详细接口、参数、错误码及并发规则见 [链码说明](../../chaincode/README.md)。T005 使用真实 CLI 与账本，T006 已另行通过 Java Gateway 双机构及文件检查点验证，M1 已通过。选择 JDK 21 后，在根目录执行：

```bash
mvn -B -ntp -Dmaven.repo.local="$PWD/.local/m2" -f backend/pom.xml -P fabric-probe clean verify
python3 deploy/fabric/verify-gateway.py
```

后者以独立 JVM 完成 prepare、submit、confirm、verify，保留原交易编号及文件检查点；默认后端 Web 启动不连接链。报告在 .local/fabric/gateway-probe/，分阶段命令、原身份恢复要求及当前边界见 [后端说明](../../backend/README.md)。

## 停止、恢复和保留账本

日常使用：

```bash
python3 deploy/fabric/network.py stop
python3 deploy/fabric/network.py up
python3 deploy/fabric/network.py verify
```

也可执行 `python3 deploy/fabric/network.py restart`，该命令停止并重新启动本项目服务。首次部署生成 runtime.env 后，管理脚本自动合并 chaincode.compose.yml，统一启停三个节点和两个链码实例；首次部署前只管理三个节点。`remove` 移除本项目容器和网络，但保留命名卷，随后可用 up 恢复。

必须同时保留 `.local/fabric/network/organizations`、通道文件及以下账本卷：

- uav-inspection-fabric_orderer-ledger
- uav-inspection-fabric_org1-peer-ledger
- uav-inspection-fabric_org2-peer-ledger

日常启动不重新生成证书，不删除卷。官方样例的 `network.sh down/restart` 含清理卷的逻辑，不用于本项目日常操作。

在没有并发提交交易时，可按以下步骤核对容器重建后账本是否一致：

```bash
python3 deploy/fabric/network.py verify --output .local/fabric/before-recreate.json
python3 deploy/fabric/network.py remove
python3 deploy/fabric/network.py up
python3 deploy/fabric/network.py verify --compare .local/fabric/before-recreate.json --output .local/fabric/after-recreate.json
```

比较项包含通道、Peer 高度及区块哈希、排序节点高度、创世头及证书指纹。T004 验证时只有初始配置块；已有链码和存证时也应保留原材料、运行镜像和账本。T006 已验证 SDK 文件检查点恢复，数据库和完整故障恢复仍由后续业务任务验证。
