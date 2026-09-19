# LiteShop

LiteShop 是一个面向小型门店的本地优先会员业务系统。它把低成本 Android 终端、SQLite 本地数据和可选云服务组合起来，让门店在断网时仍能完成会员查询、充值、消费和积分操作。

项目定位来自产品设计讨论：先做一个轻量、可离线运行的通用会员内核，再通过行业插件和云端服务扩展到美容、洗车、健身、培训等场景。

## 目标

- **本地优先**：没有网络也能正常营业；云端是可选能力。
- **账本可追溯**：充值、消费、退款、扣次和积分都产生流水，不直接覆盖历史事实。
- **同步可恢复**：本地业务提交和 `sync_event` 在同一个事务中完成，网络恢复后通过幂等事件同步。
- **兼容旧设备**：终端 UI 控制包体和浏览器特性，目标兼容 Android 4.4（API 19）及以上 WebView。
- **通用内核**：会员、卡、交易、积分、预约和员工能力放在 Core，行业差异通过插件扩展。
- **数据可迁移**：支持本地备份/恢复，为后续云端绑定和换机保留路径。

## 当前状态

当前已实现 SQLite 交易核心、本地 Web 工作台及 Android 4.4 WebView 接入工程。Web 可直接运行；Android 内置页面并连接门店电脑上的本地服务，独立 Android 记账内核尚未实现。整体状态见 [开发计划](docs/DEVELOPMENT_PLAN.md)。

## 预期架构

```text
┌──────────────────────────────────────────┐
│              可选云端服务                 │
│  账号 / 备份 / 同步 / 微信查询 / 预约通知   │
└────────────────────┬─────────────────────┘
                     │ Event-based Sync
┌────────────────────▼─────────────────────┐
│             LiteShop Terminal             │
│  Local Web UI → Use Cases → Repository    │
│                    │                      │
│                 SQLite                   │
│       transaction + sync_event ledger     │
└───────────┬───────────────┬──────────────┘
            │               │
       USB 键鼠/扫码枪    网络打印/备份
```

首版建议采用清晰分层：

```text
UI → UseCase → Repository → SQLite
                 └──────→ Sync Queue
```

业务层不直接依赖 SQL，方便未来把本地实现和云端实现组合起来。

## 首版范围（MVP）

1. 门店和设备基础信息
2. 会员新增、编辑、搜索、停用
3. 卡类型与会员卡（储值卡、次卡）
4. 充值、消费、退款、扣次和人工调整
5. 交易明细与余额校验
6. 积分账户和积分流水
7. 员工/操作记录
8. 本地同步事件队列、重试和幂等键
9. JSON/SQLite 备份与恢复
10. 适配大屏和键鼠操作的轻量 Web UI

Android APK 外壳工程已提供；云端 API、微信小程序客户端、行业插件和网络打印属于后续阶段，详见开发计划。微信小程序 API 的路径、鉴权、只读边界和预约契约已记录在 [微信小程序 API 预留说明](docs/API_WECHAT_MINIPROGRAM.md)。

## 核心数据原则

- 金额使用整数分，禁止使用浮点数保存金额。
- 所有实体使用 UUID/ULID，避免多设备离线创建时发生主键冲突。
- `member_card.balance` 是快速查询缓存；最终事实来自交易账本。
- 充值、消费、退款和调整必须记录 `balance_before`、`amount`、`balance_after`。
- 本地业务变更和对应 `sync_event` 必须在同一个 SQLite transaction 中提交。
- 云端按 `event_id` 幂等处理，重复上传只返回首次处理结果。
- 财务交易采用追加事件，不能通过云端覆盖本地余额。

## 本地开发

需要 Python 3.11+，不依赖 Python 第三方包。在仓库根目录运行：

    python -m apps.web.server --shop-name "我的门店"

打开 http://127.0.0.1:8765 ，输入控制台显示的终端访问密钥。默认数据库为 data/liteshop.sqlite，重新启动保留业务数据。

工作台支持会员搜索/编辑、储值卡/次卡、充值/消费/扣次/退款、积分及最近流水。Android 加载 APK 内置页面，通过受限原生桥连接门店局域网服务。

- [本地 Web 与 API 使用说明](docs/LOCAL_WEB_UI.md)
- [Android 4.4 构建、接入与验收](docs/ANDROID_TERMINAL.md)
- [微信小程序 API 预留契约](docs/API_WECHAT_MINIPROGRAM.md)
- [变更记录](CHANGELOG.md)

运行验证：

    python -m unittest discover -s tests -v
    node --check apps/web/public/app.js

测试涵盖账本、积分、退款回滚、幂等重试、HTTP 鉴权、并发请求和重启持久化。Android APK 编译及真机验收情况见终端文档；GitHub Actions 配置了 APK 构建和 lint。

## 目录结构

| 目录 | 内容 |
| --- | --- |
| apps/web | Python 本地 API、HTML/CSS/ES5 工作台 |
| apps/terminal/android | API 19 WebView 外壳、原生 Bridge |
| liteshop/core | 会员、卡、交易和积分用例 |
| liteshop/storage | SQLite schema、migration、repository |
| tests | 核心及 HTTP 工作流测试 |
| docs | 数据字典、API 契约、使用与开发计划 |

![本地工作台](docs/screenshots/web-workspace.png)

## 许可证与商业授权

个人、教育、评估和非商业内部使用免费。商用部署、销售、托管、集成付费产品或以本项目产生收入，必须先取得版权方书面商业授权。完整条款见 [LICENSE](LICENSE)。引入第三方代码时必须记录来源、版本和许可证，不能将第三方项目的许可证替换为本项目条款。

## 远程仓库

<https://github.com/25283531/liteshop>

