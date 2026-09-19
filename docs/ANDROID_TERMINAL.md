# Android 4.4 WebView 终端

## 当前交付模式

工程：`apps/terminal/android`，包名 `com.liteshop.terminal`，minSdk 19。

APK 内置与浏览器相同的 Web UI，通过 JavaScript Bridge 连接门店电脑运行的 Python/SQLite 本地服务。**本阶段不在 Android 上运行 Python 或 SQLite 业务内核，因此不是 Android 单机独立记账版。** 断外网不影响局域网营业；本地服务或局域网中断时只能查看已载入页面并等待恢复。完整单机模式需要后续原生 Repository/UseCase 移植。

## 构建和安装

需要 JDK 17、Gradle 8.9、Android SDK Platform 35 和对应构建工具。可用 Android Studio 打开该目录并配置 SDK，或设置 ANDROID_HOME：

```powershell
cd E:\code\liteshop\apps\terminal\android
gradle --no-daemon assembleDebug lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

当前不包含 Gradle Wrapper 二进制；请使用固定版本 Gradle 8.9。AGP 固定为 8.7.3；构建前 `syncWebAssets` 自动复制 `apps/web/public` 下三个资源文件到 build 目录，避免维护两份页面。首次构建需要联网下载工具依赖，安装后页面资源无需联网。

GitHub Actions 的 Validate 工作流也会执行 Android 构建与 lint，并在成功后提供 `liteshop-terminal-debug` APK artifact。targetSdk 暂为 28，用于旧设备侧载验收；不是应用商店发布包。

## 连接

1. 按 [本地 Web 启动说明](LOCAL_WEB_UI.md) 启动服务并设置固定密钥。
2. 设备和门店电脑连接同一可信局域网。
3. 点击 APK 顶部「连接设置」，填写如 `http://192.168.1.10:8765` 与密钥。不要把 0.0.0.0 当作连接地址。模拟器连接宿主机可用 `http://10.0.2.2:8765`。
4. 保存后，工作台显示「本地服务已连接」。新建会员、开卡、充值、消费并核对流水。
5. 启动异常可点击「重载页面」，连接异常可修改设置后重试原请求。

地址仅允许 HTTP 私有 IPv4、回环 IPv4 或 localhost，不接受路径、用户信息或重定向。HTTP 只适用于可信门店内网；未实现公网 TLS 接入与证书管理。

## Bridge 契约 v1

仅内置受控页面可用 `window.LiteShopTerminal`。普通浏览器自动使用 XMLHttpRequest。

| 方法 | 当前行为 |
| --- | --- |
| getTerminalInfo() | 同步 JSON：platform、api、androidVersion、model、bridgeVersion |
| reportReady(payload) | 页面启动报告，最大 512 字符，更新原生状态栏 |
| request(id, method, path, body) | 异步 GET/POST，只允许本地 API 白名单；网络线程执行，有限队列与超时 |
| window.LiteShopReceive(id, status, jsonText) | 原生通过 evaluateJavascript 返回请求结果 |
| requestBackup() | 预留；返回 NOT_IMPLEMENTED，不伪报成功 |
| requestPrint(payload) | 预留；返回 NOT_IMPLEMENTED，不伪报成功 |

页面从合成同源地址 `http://liteshop.invalid` 载入，所有静态资源由 WebViewClient 从 APK assets 返回，不进行 DNS 请求。其余资源和外部导航被拦截。关闭 file/content 访问、多窗口和任意页面跳转；密钥仅由原生 HTTP 请求附加。API 不通过 shouldInterceptRequest 转发，因为 API 19 无法从该回调读取 POST body。

待确认交易持久保存在 WebView localStorage。切换门店地址前检查待确认交易，避免把原交易发给另一门店。密钥可在原地址更新以恢复认证。

## 验收清单与未完成项

- [ ] Android 4.4/API 19 真机安装、横屏及键鼠/扫码枪验收。
- [ ] 在至少一个较新 Android 版本重复核心交易流程。
- [ ] 断外网仍能通过局域网查询、充值、消费；关闭本地服务后明确失败并可重试。
- [ ] 模拟响应丢失、重载页面/重启 APK，验证原 request_id 只记一笔。
- [ ] 非白名单 URL、外部跳转和 file/content 资源无法载入。
- [ ] 低内存、终端重启和长时间使用验证。

本次开发环境未提供 Java/Gradle/Android SDK，不能以代码静态检查替代 APK 编译或 Android 4.4 真机验收。CI 构建结果以 GitHub 工作流为准。

后续仍需：原生单机内核、开机自启、设备所有者/Kiosk 锁定、备份恢复、打印驱动、扫码设备专项适配及可靠云同步。当前横屏 WebView 外壳不宣称完整 Kiosk 管理能力。
