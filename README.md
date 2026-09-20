# LiteShop

LiteShop 是面向小型门店的会员业务系统。**安卓机顶盒是门店唯一主机，替代电脑**：HDMI 接显示器，USB 接键盘、鼠标、扫码枪和打印机。应用、文件与营业数据保存在机顶盒，断网仍可完成会员查询、开卡、充值、消费、退款、扣次和积分操作。

## 架构

```text
                 微信小程序（客户端待实现）
                            │
                    Cloud API（Workers/Docker）
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
- 云端下载、备份恢复、USB 打印驱动及打印队列、自启/Kiosk、微信登录及小程序客户端尚未实现。备份和打印 Bridge 明确返回 `NOT_IMPLEMENTED`。
- Android 实机与外设验收仍待完成，构建和自动化测试说明见下文。

详细进度见 [开发计划](docs/DEVELOPMENT_PLAN.md)。

## 云端部署（Cloudflare Workers 或 Debian/Ubuntu）

### Cloudflare Workers 一键部署

Workers 版本使用 D1 保存云端事件和查询投影。**请先创建 D1 数据库，再点击部署按钮**：

1. 登录 [Cloudflare Dashboard](https://dash.cloudflare.com/)，进入 **Workers & Pages → D1 SQL Database**，创建数据库，名称必须填写 `liteshop-cloud`。
2. 记下创建结果中的 `database_id`。部署向导要求将 Worker 的 D1 binding `DB` 绑定到这个数据库；不要新建其他名称的数据库。
3. 点击下面的按钮，使用标准 GitHub 仓库地址完成部署。仓库根目录的 Wrangler 配置会自动指向 `apps/cloud/workers` Worker 源码；向导中按提示绑定 `liteshop-cloud`，并设置 `LITESHOP_TERMINAL_TOKEN` 与 `LITESHOP_MINIAPP_TOKEN` 两个 secret（使用随机且不同的值）。

<a href="https://deploy.workers.cloudflare.com/?url=https://github.com/25283531/liteshop"><img src="https://deploy.workers.cloudflare.com/button" alt="Deploy LiteShop Cloud API to Cloudflare Workers" /></a>

按钮负责导入并部署 Worker，但出于安全原因不会替用户生成令牌。首次部署时，在向导中把 `DB` binding 指向你预先创建的 `liteshop-cloud`，并在 Worker 的 **Settings → Variables and Secrets** 检查两个 secret；根目录部署脚本会自动执行 D1 migration 初始化表结构。若使用旧的子目录链接，请改用上面的根仓库按钮。

它适合无服务器托管，固定域名可以绑定到 Worker 自定义域名。

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
docker compose -f apps/cloud/docker-compose.yml up -d --build
curl http://127.0.0.1:8787/healthz
```

请保存两枚令牌用于后续重启；终端顶部「云端同步」填写服务地址和终端令牌。命名卷 `cloud-data` 保存云端投影，机顶盒账本仍保存在各自设备。部署、联调和 HTTPS 配置边界见 [云端说明](docs/CLOUD_DEPLOYMENT.md)。目前共享令牌只适用于受控联调，微信正式登录和按设备授权尚待实现，小程序端不得持有内部读取令牌。

- [Validate 工作流](https://github.com/25283531/liteshop/actions/workflows/ci.yml)：Python、浏览器、APK 构建、lint、API 19/28 模拟器测试；下载产物 `liteshop-terminal-debug` 获取 APK。
- [Cloud API 工作流](https://github.com/25283531/liteshop/actions/workflows/cloud.yml)：云端测试、Compose 校验、Docker 镜像构建及容器健康检查。当前构建验证，不推送镜像仓库。

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
