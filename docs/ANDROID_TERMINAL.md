# Android 机顶盒独立客户端

## 运行方式

工程：`apps/terminal/android`，包名 `com.liteshop.terminal`，minSdk 19（Android 4.4）。机顶盒作为门店唯一主机，HDMI 接显示器，USB 接键鼠和外设。

APK 内置 Web UI；`MainActivity.TerminalBridge.request()` 在有界单线程队列中调用 `LocalStore`，使用 Android 原生 `SQLiteOpenHelper`。没有门店电脑地址、终端密钥或 Python 运行依赖，网络断开可继续营业。

本机数据库是 `Context.getDatabasePath("liteshop.db")`，通常为 `/data/data/com.liteshop.terminal/databases/liteshop.db`。页面缓存和待确认请求也在本机应用私有空间。启动自动初始化门店、设备、店主、储值卡及次卡；重启不会重建已有账本。应用卸载或清除数据会删除本机数据；备份恢复尚未实现。

## 账本实现

- Android 与 Python 开发参考实现共用 `001_initial.sql`，构建时打入 APK。
- 启用外键和 FULL 同步，schema 升级必须有显式迁移，禁止自动删库重建。
- 每个业务命令在一个 SQLite 事务中写入业务行、追加流水/操作日志、`sync_event` 和 `command_receipt`。
- 相同请求编号和参数重试返回首次结果，参数不同返回 `IDEMPOTENCY_CONFLICT`；JSON 字段排列不影响指纹。
- 余额与次数、卡类型、过期/停用、累计退款上限、积分不足及资料版本均在事务内检查。
- 数据库错误或响应丢失保留待确认请求；业务明确拒绝返回 `error.definitive=true`。
- outbox 未配置时本地累积；配置云端后独立网络线程每 30 秒上传最多 100 条，完整确认后标记 SYNCED，失败保留原事件重试。
- 当前默认操作人为店主，员工身份验证和权限管理待实现。

## 构建和安装

需要 JDK 17、Gradle 8.9、Android SDK Platform 35 与对应构建工具。设置 `ANDROID_HOME` 后：

```powershell
cd E:\code\liteshop\apps\terminal\android
gradle --no-daemon assembleDebug lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

AGP 固定为 8.7.3；工程不附带 Gradle Wrapper。`syncWebAssets` 自动复制共用 Web 资源与 SQL schema，安装后不联网取资源。targetSdk 28 用于旧设备侧载验收，非应用商店发布配置。GitHub Actions 提供 `liteshop-terminal-debug` 构建产物。

## 使用

1. 连接 HDMI 显示器和 USB 键鼠，安装并打开 APK。
2. 工作台显示「本地账本已连接」，新增会员、开卡并进行交易。
3. F2 定位搜索框，输入姓名、手机号或会员号后 Enter；HID 扫码枪扫描对应内容并发送 Enter。
4. 不确定结果显示「重试原请求」，始终使用原编号确认结果。
5. 可重载页面；「数据位置」展示设备上的存储路径。
6. 「云端同步」配置可达服务地址及终端令牌；留空地址关闭同步。见 [部署与联调](CLOUD_DEPLOYMENT.md)。

旧版 0.2 的电脑数据库不会自动导入 APK，新版本启动独立本机账本。已有真实数据迁移需要后续导入工具和校验，不应误认为升级 APK 已迁移电脑数据。

## Bridge v2

| 方法 | 行为 |
| --- | --- |
| getTerminalInfo() | platform、api、androidVersion、model、storage=ANDROID_SQLITE、bridgeVersion=2 |
| reportReady(payload) | 更新页面就绪状态，最多 512 字符 |
| request(id, method, path, body) | 异步 GET/POST 白名单；直接访问本机 SQLite |
| window.LiteShopReceive(id, status, jsonText) | 原生回调 JSON 结果 |
| requestBackup() | 返回 NOT_IMPLEMENTED |
| requestPrint(payload) | 返回 NOT_IMPLEMENTED，USB 驱动和队列待实现 |

请求路径沿用 [本地 API](LOCAL_WEB_UI.md)，Bridge 中省略 `/api/v1/`。Android 不暴露 HTTP 记账端口，也不要求浏览器测试密钥。

合成地址 `http://liteshop.invalid` 仅用于 WebView 同源页面标识，资源由 APK assets 拦截返回；不是局域网或云端服务器。非白名单资源与外部导航被拦截，file/content 访问关闭。业务数据由 SQLite 保存，localStorage 仅用于待确认请求等页面状态。

## 验证与未完成项

```powershell
gradle --no-daemon connectedDebugAndroidTest
```

原生测试覆盖初始化、储值/次卡交易、退款上限、积分、版本冲突、失败回滚、重复提交以及关闭数据库后重新打开；HTTP 同步测试检查 event_id 映射、缺失回执保留待同步事件及确认后重启持久化。自动化不替代断电和 USB 实机测试。

- [ ] Android 4.4 真机安装、横屏、HDMI 分辨率和键鼠验收。
- [ ] 拔网线/关闭 Wi-Fi 后完成完整营业流程。
- [ ] 强制结束进程、重启机顶盒后读取原数据及恢复待确认请求。
- [ ] 低内存、断电恢复、长期使用及较新 Android 版本兼容性。
- [ ] 实际 USB 扫码枪及打印机型号验证。
- [ ] 备份恢复、USB 打印驱动、自动启动、Kiosk 和云端下载。

当前开发机无可用 JDK/Android SDK；APK 编译和设备测试结果以 CI 实际运行为准，尚不能宣称真机验收通过。
