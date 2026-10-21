# 本地业务基础设施

T003 使用一个 Compose 项目运行 MySQL、Redis、RabbitMQ 和 Garage S3。后端仍在宿主机独立启动，业务客户端随后续任务接入。

T004 的双机构链网络使用独立 Compose 项目，T005 的两个 Java CaaS 实例也由该项目统一管理；构建部署、验证及保留账本的启停操作见 [Fabric 网络说明](fabric/README.md)。

## 首次启动

在项目根目录执行，需要 Python 3、Docker daemon 和 Compose 插件：

```bash
python3 deploy/scripts/init-env.py
docker compose --env-file .env -f deploy/compose.yml config --quiet
docker compose --env-file .env -f deploy/compose.yml pull
docker compose --env-file .env -f deploy/compose.yml up -d --wait --wait-timeout 240
docker compose --env-file .env -f deploy/compose.yml ps
```

初始化脚本以 `0600` 权限生成 `.env` 和随机凭证，已有文件会保留。若之前已手动复制样例，需自行填齐非空密码及密钥；Compose 会拒绝空必填值。镜像同时固定版本标签与内容摘要。`.env` 已忽略提交，避免把展开后含凭证的 `docker compose config` 输出粘贴到文档或日志；检查配置使用 `config --quiet`。

Compose 显式读取根目录 `.env`，Spring Boot 仍只读取进程环境和自身配置。初始化数据库/消息账号与存储密钥用于本机开发；修改 `.env` 不会自动修改持久卷中的已有账号密码。

## 地址与凭证

| 服务 | 宿主机后端访问 | 同一 Compose 网络内访问 |
| --- | --- | --- |
| MySQL | 127.0.0.1:3306 | mysql:3306 |
| Redis | 127.0.0.1:6379 | redis:6379 |
| RabbitMQ AMQP | 127.0.0.1:5672 | rabbitmq:5672 |
| RabbitMQ 管理 API/界面 | http://127.0.0.1:15672 | http://rabbitmq:15672 |
| S3 API | http://127.0.0.1:9000 | http://s3:3900 |

端口可在 `.env` 调整；修改 S3_PORT 时同步修改 S3_ENDPOINT 和 S3_PUBLIC_ENDPOINT。表中为默认值，全部宿主端口仅绑定本机。数据库及消息应用账号默认 `uav_app`，密码、S3 凭证保存在 `.env`，不在文档预置通用密码。

`S3_ENDPOINT` 用于应用读写；`S3_PUBLIC_ENDPOINT` 用于生成浏览器可达的下载签名地址。后端当前运行在宿主机，两者默认相同。后续若容器化后端，可将应用内部地址设为 `http://s3:3900`，签名地址仍使用浏览器能访问的地址，必须以该地址签名，不能签名后替换 Host。`S3_INTERNAL_ENDPOINT` 记录容器内地址，当前后端未读取它。

Garage region 固定为 `garage`，与 garage.toml 一致；使用路径式 S3 地址和私有桶 `uav-evidence`。启动自动建立默认桶及对应访问密钥。RPC/admin 端口不发布，不提供对象存储管理网页；可通过以下命令查看节点和桶，不需要输出密钥：

```bash
docker compose --env-file .env -f deploy/compose.yml exec -T s3 /garage status
docker compose --env-file .env -f deploy/compose.yml exec -T s3 /garage bucket list
```

MinIO 历史候选镜像获取失败后，选定 `dxflrs/garage:v2.4.1`；仍使用设计中的 S3 兼容适配接口。部署方式依据 [Garage 官方快速开始](https://garagehq.deuxfleurs.fr/documentation/quick-start/)。首版只使用私有对象和签名访问，不依赖 Garage 未实现的完整 S3 ACL/bucket policy。

## 运行验证与数据保留

服务健康后先写入随机探针数据：

```bash
python3 deploy/scripts/verify-infra.py --phase seed
```

脚本保存 `.local/infra-probe.json` 作为本次验证状态，文件不含密码或签名 URL。保持该文件，移除本项目容器、保留命名卷，再重建并检查：

```bash
docker compose --env-file .env -f deploy/compose.yml down
docker compose --env-file .env -f deploy/compose.yml up -d --wait --wait-timeout 240
python3 deploy/scripts/verify-infra.py --phase check
```

探针验证数据库应用账号读写、Redis 读写、RabbitMQ 持久消息，以及 S3 字节内容、匿名拒绝和预签名访问；重建后检查原数据而非重新插入替代。签名访问验证还包含 2 秒有效期后的拒绝；Garage 的到期响应为 `400/InvalidRequest`，消息为 `Bad request: Date is too old`，脚本同时校验错误内容，避免把任意 400 视为通过。检查通过后仅清理本次随机探针的数据。RabbitMQ 消息使用管理 HTTP API 发布和取回，检查时重新入队以支持失败后重试，最终删除本次独立探针队列；这不代表后续 Java AMQP 客户端、Outbox 或 publisher confirm 已实现。

MySQL 使用 mysql-data 卷；Redis 使用 redis-data 卷并开启 AOF；RabbitMQ 使用 rabbitmq-data 卷和固定节点主机名；Garage 的索引、对象分别使用 s3-meta、s3-data 卷。Compose 项目名为 `uav-inspection`，卷名和网络按该项目隔离。

日常停止和恢复使用：

```bash
docker compose --env-file .env -f deploy/compose.yml stop
docker compose --env-file .env -f deploy/compose.yml up -d --wait --wait-timeout 240
```

`down` 保留命名卷；不要在日常启动时使用删除卷的选项。MySQL 业务表迁移从 T007 开始。验证脚本使用 `infra_probe_<runId>` 独立表，成功检查后仅删除本次表，保持业务库可用于后续 Flyway 首次迁移；不要使用 `--keep` 在准备迁移的空库保留探针表。
