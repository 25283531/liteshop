# LiteShop

LiteShop 是面向小型门店的会员业务系统。**安卓机顶盒是门店唯一主机，替代电脑**：HDMI 接显示器，USB 接键盘、鼠标、扫码枪和打印机。应用、文件与营业数据保存在机顶盒，断网仍可完成会员查询、开卡、充值、消费、退款、扣次和积分操作。

## 架构

```text
                 微信小程序（客户端待实现）
                            │
                    Cloud API（Docker）
                       只读数据投影
                            ▲
                 Event Sync / 幂等上传
              ┌─────────────┴─────────────┐
         Terminal A                 Terminal B
        安卓机顶盒 APK              安卓机顶盒 APK
         本机 SQLite                 本机 SQLite
         本地交易账本                本地交易账本
```

各终端内置 Web UI，通过原生 Bridge 调用 LocalStore；HDMI 接显示器，USB 接键鼠和外设。A/B 各自拥有独立的门店、设备 ID 和账本，不能通过云端覆盖另一终端的财务数据。目前实现向云端上传，下载游标及跨终端业务协同尚未实现。详细核查见 [架构与实现边界](ARCHITECTURE.md)。

机顶盒 SQLite 是门店营业的唯一事实源；余额与次数可由追加式流水重建。云端故障或外网断开不会阻塞本地交易。微信小程序只能通过云端访问已同步数据，不能直接修改本地余额、积分或次数。

## 当前状态（0.3.0）

- APK 内置 HTML/CSS/ES5 工作台，通过原生桥直接读写本机 SQLite，无需门店电脑、Python 服务或局域网连接配置。
- 支持会员创建/编辑/停用、储值卡/次卡、充值/消费/退款/扣次、积分、流水查询及版本校验。
- 每次业务操作将数据、操作日志、幂等回执和同步事件在同一事务内提交，失败回滚；不确定结果保留原请求重试。
- 数据库位于应用私有目录的 `databases/liteshop.db`，关闭应用、重启机顶盒后保留；卸载应用或清除应用数据会删除账本。
- 键鼠及 HID 扫码枪走 Android 标准输入，F2 定位会员搜索框。
- Cloud API 已实现批量事件幂等接收、顺序校验、会员/卡/积分投影和内部只读查询；终端配置后每 30 秒后台上传，失败保留事件重试。
- 云端下载、备份恢复、USB 打印驱动及打印队列、自启/Kiosk、微信小程序客户端尚未实现。云端邮箱账户、终端绑定和按店铺隔离查询已提供；备份和打印 Bridge 明确返回 `NOT_IMPLEMENTED`。
- Android 实机与外设验收仍待完成，构建和自动化测试说明见下文。

详细进度见 [开发计划](docs/DEVELOPMENT_PLAN.md)。

## 云端 Docker 部署（Debian/Ubuntu）

云端服务统一使用 Docker 运行 Python Cloud API。可以在 Debian/Ubuntu 服务器执行一键脚本，也可以在 GitHub Actions 页面手动构建镜像。

### Docker 配置清单

运行容器前必须设置以下接口鉴权和管理员登录环境变量。令牌应使用不同的随机值，应使用两个不同的随机值（建议至少 32 字节），不要提交到 Git 或写入镜像：

| 环境变量 | 是否必填 | 用途 |
| --- | --- | --- |
| `LITESHOP_TERMINAL_TOKEN` | 是 | 安卓机顶盒上传同步事件时使用的 Bearer 令牌 |
| `LITESHOP_MINIAPP_TOKEN` | 是 | 云端内部读取接口使用的令牌；不要放入微信小程序前端 |
| `LITESHOP_ADMIN_TOKEN` | 是 | 管理 API 第二步令牌；不要与前两个令牌复用 |
| `LITESHOP_ADMIN_USERNAME` | 否 | 管理员第一步登录账户名，默认 `admin` |
| `LITESHOP_ADMIN_PASSWORD` | 是 | 管理员第一步登录密码；一键脚本自动生成 |

Compose 会将容器的 `8787` 端口发布到服务器的 `8787` 端口：

| 映射 | 用途 |
| --- | --- |
| `8787:8787` | Cloud API HTTP 服务；健康检查地址为 `http://服务器地址:8787/healthz` |

数据库必须持久化到容器外。默认 Compose 配置使用 Docker 命名卷 `cloud-data`，映射到容器内的 `/data`；数据库文件为 `/data/cloud.sqlite`。该卷保存云端事件、会员/卡/积分查询投影和终端登记信息，删除容器不会删除数据，**不要执行 `docker compose down -v`**。

如果需要在服务器文件系统中直接看到数据，可将 `apps/cloud/docker-compose.yml` 中的：

```yaml
volumes:
  - cloud-data:/data
```

替换为：

```yaml
volumes:
  - ./apps/cloud/data:/data
```

此时宿主机目录 `apps/cloud/data/` 用于保存云端 SQLite 数据，必须限制访问权限并纳入备份；不要把它提交到 Git。`apps/cloud/.env` 仅保存两个环境变量，属于部署配置和密钥文件，也不要提交。容器内 `/app` 只是应用代码目录，不需要映射，镜像更新时会被替换。

Debian/Ubuntu 可在仓库根目录执行一键脚本：

```bash
sudo bash apps/cloud/deploy-debian.sh
```

脚本会安装 Docker、生成令牌、启动 Cloud API 并检查健康状态。公网生产环境仍需配置 HTTPS 反向代理或 Cloudflare Tunnel。完整边界见 [云端说明](docs/CLOUD_DEPLOYMENT.md)。

## 云端 Docker 与 GitHub 构建

在云端服务器的仓库根目录执行（Bash）：

```bash
export LITESHOP_TERMINAL_TOKEN="$(openssl rand -hex 32)"
export LITESHOP_MINIAPP_TOKEN="$(openssl rand -hex 32)"
export LITESHOP_ADMIN_TOKEN="$(openssl rand -hex 32)"
docker compose -f apps/cloud/docker-compose.yml up -d --build
curl http://127.0.0.1:8787/healthz
```

部署完成后访问 `http://服务器地址:8787/` 打开云端管理台，输入 `LITESHOP_ADMIN_TOKEN` 管理令牌即可查看门店、终端、会员、会员卡、同步事件统计并修改管理台显示设置；`/healthz` 仍返回 JSON 健康状态。请保存三个令牌用于后续重启；终端顶部「云端同步」填写服务地址和终端令牌。命名卷 `cloud-data` 保存云端投影，机顶盒账本仍保存在各自设备。部署、联调和 HTTPS 配置边界见 [云端说明](docs/CLOUD_DEPLOYMENT.md)。终端同步令牌只用于受控终端上传；普通用户使用邮箱账户和终端序列号绑定，微信小程序仍不得持有内部读取令牌。

- [Validate 工作流](https://github.com/25283531/liteshop/actions/workflows/ci.yml)：Python、浏览器、APK 构建、lint、API 19/28 模拟器测试；下载产物 `liteshop-terminal-debug` 获取 APK。
- [Cloud API 验证工作流](https://github.com/25283531/liteshop/actions/workflows/cloud.yml)：云端测试、Compose 校验及容器健康检查。
- [手动构建 Cloud API 镜像](https://github.com/25283531/liteshop/actions/workflows/cloud-image.yml)：在 Actions 页面点击 **Run workflow** 手动构建镜像，可选发布到 GitHub Container Registry（GHCR）。
- [手动构建安卓 APK](https://github.com/25283531/liteshop/actions/workflows/android-build.yml)：在 Actions 页面点击 **Run workflow**，下载 `liteshop-terminal-debug` 构建产物。

## 机顶盒安装与使用

按 [Android 构建及验收说明](docs/ANDROID_TERMINAL.md) 构建或从 GitHub Actions 下载调试 APK，安装后直接启动。首次打开自动建立门店、设备、店主和两种卡类型；当前操作人固定为初始化店主，员工登录和权限尚待实现。顶部「数据位置」可查看本机数据库路径。

硬件接线、HID 扫码和打印适配边界见 [机顶盒与外设](docs/HARDWARE.md)。调试 APK 用于验收，正式营业还需备份恢复和实机稳定性验证。

## 本地开发与验证

电脑仅用于开发、构建和测试，不参与门店运行。Python 3.11+ 参考实现可在普通浏览器预览 UI：

```powershell
python -m apps.web.server --shop-name "开发测试门店"
```

打开 http://127.0.0.1:8765 并输入控制台测试密钥。此工具数据库为 `data/liteshop.sqlite`，与机顶盒数据库相互独立，不自动迁移或同步。

```powershell
python -m unittest discover -s tests -v
node --check apps/web/public/app.js
node tests/browser_smoke.cjs
```

浏览器测试依赖安装方法见 [Web UI 与接口说明](docs/LOCAL_WEB_UI.md)。Android 原生数据库测试位于 `app/src/androidTest`，使用 `gradle connectedDebugAndroidTest` 在模拟器或设备运行；GitHub Actions 配置构建、lint 及模拟器测试。

## 数据与接口原则

当前终端 Web UI 已支持实时模糊搜索会员姓名、手机号和会员卡号，会员可记录邀请人并统计邀请注册人数。设置入口可修改店铺名、配置储值/计次会员卡类型和本地设置密码；删除会员必须使用该密码，交易账本保留审计记录。

Android 终端是独立客户端，数据保存在机顶盒 SQLite。云端地址固定为 `https://liteshop.250886.xyz`，同步请求使用 HTTPS、时间戳、随机数和 HMAC-SHA256 校验。云端提供终端事件投影、终端登记、店铺列表和按店铺会员查询骨架；微信小程序只能通过 Cloud API 访问，不能直接修改终端余额、积分或次数。登录/平台注册入口已预留在固定域名，正式账号服务由云端部署启用后接入。

- 金额为整数分，积分和次数为整数，禁止浮点记账。
- UUID 标识实体，交易、积分和审计流水只追加；退款关联原消费并限制累计可退数量。
- 请求回执、业务变更、同步事件同事务提交；同一请求编号不得用于不同操作。
- 云端按事件 ID 幂等接收，逐条回执确认；游标下载、备份和换机恢复后续实现。
- [微信小程序 API 契约](docs/API_WECHAT_MINIPROGRAM.md) 单独预留登录、会员绑定、只读卡包/余额/积分/流水和预约接口。
- [数据字典](docs/DATA_MODEL.md) / [变更记录](CHANGELOG.md)。

## 目录

| 目录 | 内容 |
| --- | --- |
| apps/terminal/android | 独立 Android 客户端、原生 SQLite、Bridge、设备测试 |
| apps/web | APK 共用的 Web UI，以及开发用 Python HTTP 服务 |
| apps/cloud | 云端事件接收、只读投影、Docker 与 Compose |
| liteshop/core | Python 参考业务实现 |
| liteshop/storage | Android 与 Python 共用 SQL schema、Python 数据层 |
| tests | Python 核心/HTTP 与浏览器测试 |
| docs | 架构、计划、API、硬件与验收说明 |

## 许可证与商业授权

个人、教育、评估和非商业内部使用免费。商用部署、销售、托管、集成付费产品或以本项目产生收入，必须先取得版权方书面商业授权。完整条款见 [LICENSE](LICENSE)。第三方代码保留原有许可证。

远程仓库：<https://github.com/25283531/liteshop>

## 云端账户、终端绑定与微信增值服务

部署完成后打开 `http://服务器地址:8787/account`，使用邮箱注册或登录。注册成功后会通过 SMTP 发送包含注册用户名的确认邮件；登录后输入机顶盒“终端序列号”页面显示的 16 位大写字母数字序列号，即可绑定终端并查看该用户已绑定店铺的会员数据。云端查询始终按用户绑定关系过滤，用户不能读取其他店铺。

可申请小程序通知、查询、预约、公众号通知和公众号群发。公众号群发任务按店铺创建，只统计并发送到该店铺的关注者；运营者仍需配置公众号凭据并审核后执行实际发送。共用的小程序/公众号必须在服务端通过会员与店铺关系进行隔离，会员只能看到自己注册过会员的店铺。

邮箱注册、SMTP、小程序和公众号配置均在管理台的“云端设置”页面中完成。配置值保存在云端 SQLite 数据库，不需要写入 Docker 环境变量；密码、AppSecret 和 Token 只显示配置状态，不会回显。根路径是公共注册/登录入口，管理员先输入 `LITESHOP_ADMIN_USERNAME` / `LITESHOP_ADMIN_PASSWORD`，再输入 `LITESHOP_ADMIN_TOKEN` 进入 `/admin` 管理台。
