# 无人机集群 HTTP 模拟器

本工具只调用后端正式接口，不控制飞行器。每台设备有独立线程、bootId 和递增 seq，可并行模拟 3～5 台设备的心跳、单调进度和 PNG 单片上传。任务、子任务、资料要求和执行批次需要先通过用户接口创建；设备通过 `POST /api/v1/drones` 注册，明文 token 只返回一次。

先启动 MySQL、Redis、Garage、RabbitMQ 及后端 `local,database,storage,messaging` profiles。未启用 messaging 时，将 `completeUploads` 设为 false，只创建会话和传片。模拟器不会尝试上链；后端是否启用 ledger 由启动配置决定。

```bash
mkdir -p .local/simulator
chmod 700 .local/simulator
cp simulator/config.example.json .local/simulator/config.json
chmod 600 .local/simulator/config.json
# 在本地文件内填入真实 deviceId、deviceToken、executionId、requirementId
python3 simulator/swarm.py --config .local/simulator/config.json --validate-only
python3 simulator/swarm.py --config .local/simulator/config.json
```

配置文件只能放在被 Git 忽略的 `.local/`，权限必须为 0600。不要把 token 放进命令行、HTTP 示例或日志。`count` 必须为 3～5，且与 `devices` 数量相同；默认间隔 5 秒。`cycles` 控制轮数，`progressStep` 控制每次进度增量，`uploadEveryCycles: 0` 可关闭上传，`repeatEveryCycles` 控制原事件重放，`networkOutageCycles` 指定从 1 开始的模拟断网轮次。

发送前会将完整待确认 report、上传 clientRequestId、上传阶段和文件摘要原子写入 0600 状态文件。断网或超时后保留原 bootId/seq/eventId、原请求体和同一上传会话，后续重试不会生成替代事件。每次启动生成新 bootId；上次未确认的旧事件先按原内容重试，确认后才使用新 bootId。状态文件不保存 token。不要删除仍有未确认请求的状态文件。

`stateDirectory` 也必须位于 `.local/`，同一目录有进程锁。每台设备依次执行心跳/进度、可选重放和上传；设备之间并行。日志只给出设备序号与结果；安全汇总在状态目录的 `summary.json`。`partsUploaded` 表示传片接口已接纳，`uploadsSubmitted` 表示 complete 接口已接纳请求；202 可能仍为 VERIFYING，模拟器通过不代表版本已定版、复核通过或上链确认。结束时仍有待确认请求会返回非零退出码，再次运行可继续。4xx 表示配置、权限或业务状态需要处理，工具不会自动改任务或更换执行批次。换机后应为新设备配置新的当前 executionId；旧设备可向原 CLOSED 批次补传历史资料，旧批次新报告只记录历史事件并刷新该设备在线时间，冻结批次进度，不能推进新的当前执行。

真实集成验收由 `python3 deploy/scripts/verify-swarm.py` 自动创建独立测试账号和 5 台测试设备，其中 3 台运行本模拟器，验证汇总、并发换机和历史资料归属。验收会清理自己的业务数据、对象、Redis key 和独占 RabbitMQ 拓扑，保留审计，不向 Fabric 新增记录。

## 通用文件多分片续传

`upload.py` 使用 Python 标准库，通过用户或设备的正式上传接口发送 PNG、JPEG、PDF、MP4；视频作为普通文件处理。先准备已有任务的 requirementId、executionId 和真实文件。需要演示多分片时，文件必须大于后端配置的分片大小，不能只用 79 字节 PNG 证明多片续传。

在 `.local/` 创建权限为 0600 的配置文件，例如 `.local/uploader/config.json`：

```json
{
  "formatVersion": 1,
  "baseUrl": "http://127.0.0.1:8080",
  "filePath": ".local/uploads/inspection.pdf",
  "requirementId": "填写资料项UUID",
  "executionId": "填写执行批次UUID",
  "stateFile": ".local/uploader/inspection.state.json",
  "auth": {
    "type": "USER",
    "accessToken": "在此私有文件内填写当前用户JWT"
  }
}
```

本地相对路径以项目根目录解析；`stateFile` 可省略，默认使用配置文件同目录下的 `<配置名>.state.json`。`contentType` 默认按文件扩展名推断，可显式填写对应 MIME。设备模式将 auth 替换为 `{"type":"DEVICE","deviceId":"设备UUID","deviceToken":"设备令牌"}`，工具自动使用 `/api/v1/device/upload-sessions`；用户模式使用 `/api/v1/upload-sessions`。令牌只放在私有配置中，不放进命令行。

```bash
chmod 700 .local/uploader
chmod 600 .local/uploader/config.json
# 上传最多一个尚缺分片后正常退出，不调用 complete
python3 simulator/upload.py --config .local/uploader/config.json --max-parts 1
# 再次运行，查询服务端进度，仅补传缺片，再请求完成
python3 simulator/upload.py --config .local/uploader/config.json
python3 -m unittest discover -s simulator -p 'test_upload.py' -v
```

创建前，工具将 clientRequestId、文件绝对路径、完整 SHA-256、长度、任务资料范围和身份编号原子写入 0600 检查点。检查点不保存 JWT、设备令牌或原始服务器响应。同一状态文件有独占进程锁，创建响应丢失时仍使用原请求编号；已保存 uploadId 后每次先 GET 服务端会话，以数据库中的 partSize、partCount 和 uploadedParts 为准。已有分片逐个核对本地实际长度及摘要，任一不符即停止，不覆盖服务端内容。分片响应丢失后再次运行会通过 GET 复用已受理的分片。

文件、后端地址、身份或资料范围改变时拒绝沿用原状态；用户 JWT 到期可在配置内替换为同一账号的新 JWT。上传新内容应保留原文件和状态，使用另一份配置及新的 stateFile 创建新会话。后端重启或调整默认分片大小不会改变已有会话规格，工具不使用本地 partSize 配置。全文件摘要和分片传输均按最多 64 KiB 的缓冲处理，不将整片或完整文件读入内存。

stdout 只输出一行安全 JSON，包含 uploadId、status、httpRequests、partsUploadedThisRun、completeRequested 和 completionAccepted。`--max-parts N` 是主动断点，退出码为 0，始终停在 complete 之前；不带该参数会补齐分片并请求完成。status=VERIFYING、completionAccepted=true 仅代表异步处理已接纳，不能视为定版、复核或上链成功；再次运行可查询当前状态，COMPLETED 时返回 versionId，不再请求完成。完成接口需要后端 messaging profile。

网络或业务错误返回非零退出码，保留检查点供原配置重试；FAILED、EXPIRED、权限变更或已有片内容冲突需要先处理对应原因。工具拒绝重定向、不继承环境代理，不输出供应商正文、请求头或令牌。它不删除对象、不修改任务，也不替代正式核验接口。
