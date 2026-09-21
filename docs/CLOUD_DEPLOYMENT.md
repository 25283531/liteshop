# Cloud API 部署与联调

## Docker 部署

云端服务统一使用 Docker 运行 Python Cloud API；API 路径和终端同步协议只有这一套实现。GitHub Actions 提供自动验证和手动镜像构建。

## GitHub Actions 手动构建镜像

打开 [手动构建 Cloud API 镜像](https://github.com/25283531/liteshop/actions/workflows/cloud-image.yml)，点击 **Run workflow**。默认只构建并验证镜像；如果需要发布到 GitHub Container Registry，勾选 `push_image`。发布时镜像地址为：

```text
ghcr.io/25283531/liteshop-cloud-api:<git-sha>
```

工作流使用 `GITHUB_TOKEN` 登录 GHCR，不需要在仓库中保存额外密码。服务器可以使用发布后的镜像，或直接从仓库源码执行 Compose 构建。

## Debian/Ubuntu 一键部署

在仓库根目录运行：

```bash
sudo bash apps/cloud/deploy-debian.sh
```

脚本安装 Docker、生成 `apps/cloud/.env`、构建并启动 Compose 服务，然后检查 `/healthz`。它不会自动开放公网端口或配置证书；生产环境请在前面配置 Nginx/Caddy/Cloudflare Tunnel，并只通过 HTTPS 暴露服务。

需要云端服务器安装 Docker Engine 与 Compose。仓库根目录执行 README 中的令牌生成和 `docker compose -f apps/cloud/docker-compose.yml up -d --build`。Compose 的 build context 是仓库根目录，Dockerfile 为 `apps/cloud/Dockerfile`，服务默认端口 8787。

令牌也可保存于云端服务器的 `apps/cloud/.env`（不要提交到 Git），分别设置 `LITESHOP_TERMINAL_TOKEN`、`LITESHOP_MINIAPP_TOKEN` 和 `LITESHOP_ADMIN_TOKEN`。三个令牌须使用不同的随机值；管理员令牌用于打开根路径的 Web 管理台。`cloud-data` 命名卷挂载 `/data`，数据库为 `/data/cloud.sqlite`，重新创建容器会保留；不要执行 `down -v` 删除数据卷。

部署映射清单：

| 项目 | 配置 | 用途 |
| --- | --- | --- |
| 必填环境变量 | `LITESHOP_TERMINAL_TOKEN`、`LITESHOP_MINIAPP_TOKEN`、`LITESHOP_ADMIN_TOKEN` | 分别用于终端同步、内部读取和 Web 管理台鉴权 |
| 网络端口 | `8787:8787` | 对外提供 Cloud API；生产环境应通过 HTTPS 反向代理转发 |
| 默认持久化 | Docker 卷 `cloud-data:/data` | 保存 `/data/cloud.sqlite` 云端数据库和同步投影 |
| 可选宿主机目录 | `./apps/cloud/data:/data` | 需要直接管理数据库文件或执行文件级备份时使用；与命名卷二选一 |
| 密钥文件 | `apps/cloud/.env` | 保存环境变量，不是数据库目录，不应提交或公开 |
| 容器应用目录 | `/app` | 镜像内的 Python 应用代码，无需映射 |

选择宿主机目录时，先在服务器创建 `apps/cloud/data` 并限制权限，再启动 Compose。该目录只保存云端副本；安卓机顶盒本地 SQLite 仍是交易事实源。

```bash
docker compose -f apps/cloud/docker-compose.yml ps
docker compose -f apps/cloud/docker-compose.yml logs --tail=100
curl http://127.0.0.1:8787/healthz
```

当前服务默认绑定容器的所有接口，Compose 发布 8787 端口。仅在受控测试网络开放；远程连接应通过反向代理提供 HTTPS，Android 4.4 的 TLS/证书兼容性需实机验证。微信 OAuth 和小程序客户端尚未接入；邮箱账户、终端序列号绑定及用户到店铺的数据隔离已实现。正式接入微信前仍应配置 HTTPS、运营者凭据和租户审计。

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

`.github/workflows/cloud.yml` 运行 Python 云端回归测试、Compose 配置检查、构建 Docker 镜像并启动容器检查 `/healthz`。手动镜像工作流负责按需构建并发布 GHCR 镜像；服务器也可以继续通过仓库源码构建。

`.github/workflows/cloud-image.yml` 只响应 Actions 页面中的 **Run workflow**，默认不发布镜像。将 `push_image` 设为 `true` 后，工作流使用 `GITHUB_TOKEN` 发布 `ghcr.io/25283531/liteshop-cloud-api:<git-sha>` 和 `latest` 标签。

`.github/workflows/ci.yml` 构建调试 APK、lint、Android API 19/28 设备测试，以及本地业务和浏览器回归。APK artifact 为 `liteshop-terminal-debug`，正式签名和真机外设验收另行完成。

## 账户接口与邮件配置

账户页面为 `/account`。注册和登录接口分别为 `POST /api/v1/auth/register`、`POST /api/v1/auth/login`；登录后可使用 `GET /api/v1/account`、`POST /api/v1/account/terminals`、`GET /api/v1/account/members`、`POST /api/v1/account/services` 和 `POST /api/v1/account/message-campaigns`。终端绑定使用终端显示的 16 位序列号；会员和群发任务都按用户绑定的 `shop_id` 过滤。

注册邮件需要设置 `LITESHOP_SMTP_HOST`、`LITESHOP_SMTP_PORT`、`LITESHOP_SMTP_USERNAME`、`LITESHOP_SMTP_PASSWORD`、`LITESHOP_SMTP_FROM` 和 `LITESHOP_SMTP_SSL`。只有邮件发送成功后才返回注册成功提示，邮件正文包含用户注册的用户名。
