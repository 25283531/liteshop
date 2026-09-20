# Cloud API 部署与联调

## Docker 部署

部署方式有两种：Cloudflare Workers + D1 适合无服务器托管；Debian/Ubuntu 一键脚本使用 Docker 运行 Python Cloud API。两者 API 路径和终端同步协议保持一致。

## Cloudflare Workers + D1

先在 Cloudflare Dashboard 创建名称为 `liteshop-cloud` 的 D1 数据库，再点击 README 或 [Workers 部署说明](../apps/cloud/workers/README.md) 中的部署按钮。部署向导中把 D1 binding `DB` 绑定到该数据库，并设置 `LITESHOP_TERMINAL_TOKEN`、`LITESHOP_MINIAPP_TOKEN` 两个 secret；按钮不会替用户生成令牌。部署脚本会自动执行 `migrations/0001_initial.sql` 初始化表结构，并检查 `/healthz`。

按钮入口使用 GitHub 仓库根地址，根目录 Wrangler 配置会指向 `apps/cloud/workers` Worker 源码，避免 Cloudflare 对 monorepo 子目录 URL 的校验问题。如果部署向导仍不能完成部署，进入 `apps/cloud/workers` 按 Workers 说明中的 Wrangler 备用命令执行。Workers 版本使用 D1，不读取 Docker 的 `/data/cloud.sqlite`；从 Docker 迁移时需要重新上传未同步事件或制作专用数据迁移，不要直接把 SQLite 文件上传到 D1。

## Debian/Ubuntu 一键部署

在仓库根目录运行：

```bash
sudo bash apps/cloud/deploy-debian.sh
```

脚本安装 Docker、生成 `apps/cloud/.env`、构建并启动 Compose 服务，然后检查 `/healthz`。它不会自动开放公网端口或配置证书；生产环境请在前面配置 Nginx/Caddy/Cloudflare Tunnel，并只通过 HTTPS 暴露服务。

需要云端服务器安装 Docker Engine 与 Compose。仓库根目录执行 README 中的令牌生成和 `docker compose -f apps/cloud/docker-compose.yml up -d --build`。Compose 的 build context 是仓库根目录，Dockerfile 为 `apps/cloud/Dockerfile`，服务默认端口 8787。

令牌也可保存于云端服务器的 `apps/cloud/.env`（不要提交到 Git），分别设置 `LITESHOP_TERMINAL_TOKEN` 和 `LITESHOP_MINIAPP_TOKEN`。两者须使用不同的随机值。`cloud-data` 命名卷挂载 `/data`，数据库为 `/data/cloud.sqlite`，重新创建容器会保留；不要执行 `down -v` 删除数据卷。

```bash
docker compose -f apps/cloud/docker-compose.yml ps
docker compose -f apps/cloud/docker-compose.yml logs --tail=100
curl http://127.0.0.1:8787/healthz
```

当前服务默认绑定容器的所有接口，Compose 发布 8787 端口。仅在受控测试网络开放；远程连接应通过反向代理提供 HTTPS，Android 4.4 的 TLS/证书兼容性需实机验证。本骨架没有微信登录、设备注册和按租户授权，不应直接作为生产小程序后端。

## 终端 A/B

分别安装 APK 并建立各自本地会员。在顶部「云端同步」输入 Cloud API 的可达地址及终端令牌（至少 16 字符），保存后立即尝试上传，此后应用运行期间每 30 秒上传一批，最多 100 条。留空地址并保存可关闭同步。应用退出期间不上传，重新打开后继续。

网络线程与本地交易线程分离，上传失败不撤销本地交易、不阻塞离线营业。更多积压将在后续轮询发送；重试仍使用原 event_id。固定周期重试已实现，指数退避及错误详情尚待实现。

每个终端都必须保留完整的 outbox。云端 sequence 检查从 1 开始；若删除云端卷而本地已标记 SYNCED，本版本没有自动重放/恢复流程。云端投影不是机顶盒备份。

## 已实现的 API

| 接口 | 鉴权 | 行为 |
| --- | --- | --- |
| GET /healthz | 无 | 服务健康检查 |
| POST /api/v1/terminal/sync/events | Bearer 终端令牌 | 原子接收一批事件，最多 500 条、请求体 1 MiB |
| GET /api/v1/miniapp/me | Bearer 内部读取令牌 + X-LiteShop-Member | 内部联调会员、卡和积分投影；未知会员 data=null |

同步请求：

```json
{"events":[{"event_id":"e1","shop_id":"s1","device_id":"d1","sequence":1,"schema_version":1,"event_type":"MEMBER_CREATED","entity_id":"m1","payload":{"id":"m1","name":"测试会员"},"created_at":1790000000000}]}
```

响应：

```json
{"ok":true,"data":{"accepted":[{"event_id":"e1","status":"ACCEPTED"}]}}
```

相同事件重复返回 `ALREADY_ACCEPTED`；同 ID 内容变化、序列缺口、未知 schema 或其他终端试图覆盖已有实体均返回 400，整批回滚。终端必须验证响应正文和全部事件确认，不能仅凭 HTTP 200 清除 outbox。

内部读取令牌目前能选择任意已同步会员，仅给受信任的联调人员/服务使用，不能嵌入微信小程序。正式实现将用微信会话映射会员并校验门店权限；该契约以及尚未实现的接口见 [微信 API](API_WECHAT_MINIPROGRAM.md)。未实现路径返回 404。

## GitHub 验证

`.github/workflows/cloud.yml` 运行 Python 云端回归测试、Compose 配置检查、构建 Docker 镜像并启动容器检查 `/healthz`。当前 `push: false`，没有发布到 GHCR 或 Docker Hub；服务器通过仓库源码构建。

`.github/workflows/ci.yml` 构建调试 APK、lint、Android API 19/28 设备测试，以及本地业务和浏览器回归。APK artifact 为 `liteshop-terminal-debug`，正式签名和真机外设验收另行完成。
