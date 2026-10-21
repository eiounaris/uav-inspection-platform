# 巡检文件摘要链码

本目录是独立的 Java 链码工程。合约名为 `EvidenceContract`，部署到开发通道 `mychannel`，链码名为 `evidence`。业务功能仅包含文件摘要登记和查询。

原始文件由对象存储保存；任务、设备、人工复核及共享权限由 Java 后端管理。链码校验摘要的格式并保存登记值，文件摘要由后端实际读取文件后计算。摘要一致用于判断文件内容是否与登记版本一致，不能单独证明采集地点、时间或现场真实性。

T005 已完成 50 项单元测试和真实网络合约用例，实际证据见 [验证记录](../docs/verification.md)。以下 JSON 均为格式示意。

## 两个业务接口

| 方法 | 调用方式 | 参数 | 返回值 |
| --- | --- | --- | --- |
| `RegisterEvidence` | SUBMIT | `evidenceId`、`version`、`fileHash` | 已登记记录的 JSON 字符串 |
| `QueryEvidence` | EVALUATE | `evidenceId`、`version` | 包含 `found` 和 `record` 的 JSON 字符串 |

完整方法名分别为 `EvidenceContract:RegisterEvidence` 和 `EvidenceContract:QueryEvidence`。通过 Gateway 选择 `EvidenceContract` 后使用相应方法名；`Context` 由 Fabric 提供，不属于客户端业务参数。

| 参数 | 规则 |
| --- | --- |
| `evidenceId` | 小写 UUID，严格采用 `8-4-4-4-12` 十六进制格式，例如 `123e4567-e89b-12d3-a456-426614174000` |
| `version` | 调用时传字符串，取值为 `1`～`2147483647` 的规范十进制正整数；拒绝前导零、正负号、空白、小数和指数形式 |
| `fileHash` | 完整文件的 SHA-256 摘要，严格为64个小写十六进制字符 |

账本使用 `evidence / evidenceId / version` 复合键。不同证据编号和不同文件版本分别保存，文件名及对象路径不作为账本键。这里的文件 `version` 与链码部署版本、生命周期 `sequence` 分别管理。

## 登记记录

记录包含以下五个字段，序列化顺序固定：

| 字段 | JSON 类型 | 来源 |
| --- | --- | --- |
| `evidenceId` | string | 已校验的证据编号 |
| `version` | number | 已校验的文件版本，保存为整数 |
| `fileHash` | string | 已校验的文件摘要 |
| `submitterMsp` | string | `Context.getClientIdentity().getMSPID()`，本版只能为 `Org2MSP` |
| `registeredTxId` | string | 首次写入该业务键时的 `stub.getTxId()` |

```json
{
  "evidenceId": "123e4567-e89b-12d3-a456-426614174000",
  "version": 1,
  "fileHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "submitterMsp": "Org2MSP",
  "registeredTxId": "<首次登记交易编号>"
}
```

查询未找到时返回：

```json
{"found":false,"record":null}
```

找到时返回 `found: true`，`record` 为上述原始记录对象；它不是再次转义的 JSON 字符串。查询及重复登记均保留原记录中的首次交易编号。

## 调用权限与双机构背书

| 调用身份 | 登记 | 查询 |
| --- | --- | --- |
| OrgA 委托方：`Org1MSP` | 拒绝 | 允许 |
| OrgB 运营商：`Org2MSP` | 允许 | 允许 |
| 其他 MSP | 拒绝 | 拒绝 |

合约先检查调用身份，再校验业务参数。机构身份来自签名者的 MSP，不接受客户端另传机构身份来替代。

部署约定的链码背书策略为：

```text
AND('Org1MSP.peer','Org2MSP.peer')
```

ACL 决定谁可以发起业务操作；背书策略要求两机构 Peer 对执行结果签名。OrgB 发起登记时，两侧 Peer 执行时看到的调用者仍是 OrgB，因此可以共同背书。OrgA Peer 的背书不代表委托方人员已人工复核。`QueryEvidence` 采用 evaluate 查询，不形成新的排序提交交易。[官方背书策略说明](https://hyperledger-fabric.readthedocs.io/en/release-2.5/endorsement-policies.html)

## 幂等、并发与错误

- 业务键不存在时，登记五字段记录。
- 相同业务键已有相同摘要时，原样返回已有 JSON，不调用 `putStringState`，不修改首次交易编号。
- 相同业务键已有不同摘要时，拒绝覆盖；更正文件应由后端分配新文件版本。

两个请求并发首次登记同一业务键时，都可能在背书阶段读到“尚不存在”。其中一个提交后，另一个可能因 `MVCC_READ_CONFLICT` 无效。后端应先确认提交结果并查询原业务键：已有同摘要记录可视为业务目标已达成；已有不同摘要记录应报告冲突。明确原交易无效且仍无登记时，再重新背书产生新交易。提交超时保持待确认，不能据此直接认定失败。[官方读写集与版本校验说明](https://hyperledger-fabric.readthedocs.io/en/release-2.5/readwrite.html)

显式业务错误使用 `ChaincodeException`；错误码位于 UTF-8 payload，消息为固定文本，不回显非法输入。这些是链码错误码，HTTP 状态由后端接入层处理。

| 错误码 | 含义 |
| --- | --- |
| `FORBIDDEN` | 调用者 MSP 不具备本次操作权限 |
| `INVALID_EVIDENCE_ID` | 证据编号不符合规范小写 UUID 格式 |
| `INVALID_VERSION` | 文件版本格式不规范、非正数或超出 int32 范围 |
| `INVALID_FILE_HASH` | 摘要不是64位小写十六进制字符串 |
| `EVIDENCE_HASH_CONFLICT` | 同一证据编号和版本已登记不同摘要 |

`MVCC_READ_CONFLICT` 属于 Fabric 提交验证结果，与上述合约主动抛出的业务错误分别处理。背书成功或取得方法返回值，也仍需等待有效 commit 确认。

## 独立构建

构建使用 JDK21、Maven，以及 POM 固定的 `fabric-chaincode-shim:2.5.11`。JUnit Jupiter 和 Mockito 用于 ACL、参数边界、首次交易编号、证据及版本隔离、重复登记和冲突不写入等业务测试。

在项目根目录执行，先确认 Maven 实际使用 JDK21：

```bash
java -version
mvn -version
mvn -f chaincode/pom.xml -Dmaven.repo.local="$PWD/.local/m2" clean verify
```

构建产物为 `chaincode/target/evidence-chaincode-0.1.0.jar`，包含运行依赖，入口为官方 `org.hyperledger.fabric.contract.ContractRouter`。`chaincode/Dockerfile` 使用 JRE21 运行该 JAR。

## 本地 CaaS 部署与验证

前提是 T004 的两个业务组织、通道及证书材料已就绪。项目使用官方 ContractRouter 的 Chaincode as a Service 运行方式，Org1 与 Org2 各运行一个链码服务实例，由各自 Peer 连接。服务地址和对应包 ID 由部署流程配置。[官方 CaaS 说明](https://hyperledger-fabric.readthedocs.io/en/release-2.5/cc_service.html)

在项目根目录使用以下入口：

```bash
python3 deploy/fabric/chaincode.py deploy
python3 deploy/fabric/verify-chaincode.py
```

链码服务配置位于 `deploy/fabric/chaincode.compose.yml`，与 Fabric 基础 Compose 合并运行。两个实例分别在容器内监听 `0.0.0.0:9999`，通过 Compose 内网提供明文服务，不映射宿主端口。本地验证保留 Peer、Orderer 及管理员接口原有的 TLS 配置；链码服务这一段连接未启用 TLS，该部署方式仅用于本地验证。

部署脚本另存 JAR 与 Dockerfile 摘要锁，因为 CaaS package ID 只绑定连接配置。原版本下构建产物变化会被拒绝，合约变更须显式升级；不要删除锁来绕过校验。同一源码使用固定输出时间戳，重新打包应保持 JAR 摘要一致。Mockito 在测试 JVM 启动时显式作为 agent 加载。

业务验证使用两个组织的普通 User1；从双方 Peer 读取真实区块并要求有效验证码，同时检查重复调用没有写入证据状态。请在无其他并发交易的本地环境运行，脚本用调用前高度定位本次区块。报告写入 `.local/fabric/chaincode/verification.json`；每次生成新证据编号并保留账本记录。T006 已另外验证 Java Gateway 和跨进程文件检查点，M1 已通过，调用方法见 [后端说明](../backend/README.md)。
