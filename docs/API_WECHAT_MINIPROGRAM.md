# 微信小程序 API 预留契约

LiteShop 的会员端使用微信小程序。小程序不是本地终端的直接数据库客户端，所有请求经云端 API 处理；门店交易仍以本地账本产生的事件为准。

## 原则

- 小程序可以查询会员资料、卡包、余额、积分、消费记录和预约状态。
- 小程序可以提交预约请求；预约由云端生成事件并在同步后落到门店。
- 小程序不能直接修改余额、积分或次数。充值、消费、退款和扣次必须由门店终端生成交易流水。
- 所有接口使用 `/api/v1/miniapp/` 前缀，响应包含 `request_id`，便于追踪和幂等。
- 金额单位为整数分，时间使用 Unix 毫秒；响应 JSON 使用 UTF-8。

## 鉴权预留

微信小程序通过微信登录 code 换取服务端 session。服务端将 `openid` 映射到 LiteShop `member.id`，不把 openid 当作会员主键。

```http
POST /api/v1/miniapp/auth/wechat-login
Content-Type: application/json

{"code":"wx-login-code"}
```

```json
{"request_id":"req_...","data":{"access_token":"...","member_id":"member_...","expires_at":1790000000000}}
```

## 只读会员接口

```http
GET /api/v1/miniapp/me
GET /api/v1/miniapp/me/cards
GET /api/v1/miniapp/me/transactions?cursor=...
GET /api/v1/miniapp/me/points?cursor=...
GET /api/v1/miniapp/me/appointments?cursor=...
```

示例响应：

```json
{"request_id":"req_...","data":{"member_id":"m_1","name":"张三","balance":28600,"points":1280,"cards":[]}}
```

## 预约接口

```http
GET  /api/v1/miniapp/services
GET  /api/v1/miniapp/services/{service_id}/slots?date=2026-09-19
POST /api/v1/miniapp/appointments
GET  /api/v1/miniapp/appointments/{appointment_id}
POST /api/v1/miniapp/appointments/{appointment_id}/cancel
```

预约请求示例：

```json
{"service_id":"service_1","resource_id":"resource_1","start_at":1790000000000,"end_at":1790003600000,"remark":""}
```

## 统一错误格式

```json
{"request_id":"req_...","error":{"code":"UNAUTHORIZED","message":"登录已过期"}}
```

预留实现必须将余额、积分和卡次数设置为只读字段，并在云端拒绝任何来自小程序的财务写入请求。

## 机顶盒与云端接口边界（0.3.0）

机顶盒是门店唯一营业主机，原生 Bridge 的写操作直接提交到本机 SQLite；电脑上的 `/api/v1/*` HTTP 服务仅用于开发测试，不是小程序服务端。

固定链路为「机顶盒本地账本 → 云端同步投影 → 微信小程序」。小程序不能直连机顶盒或绕过云端读写 SQLite，不能调用门店记账命令。云端的会员余额、积分和卡次数只由机顶盒账本事件更新。

### 同步 API（已实现上传骨架）

- `POST /api/v1/terminal/sync/events`：设备身份验证后批量提交本地 outbox；包含 shop_id、device_id、event_id、sequence、schema_version、event_type、entity_id、payload、created_at。数据库中 sync_event.id 映射为 event_id。
- 响应逐事件确认，云端按设备及事件编号幂等接收；同编号不同内容拒绝，已确认事件才可在机顶盒标为 SYNCED。
- 预留（未实现）`GET /api/v1/terminal/sync/changes?cursor=...`：按游标下载预约等非财务事件；不下发余额覆盖命令。
- 目标：终端与小程序使用独立鉴权域并校验门店/会员归属。当前骨架仅分开两枚内部共享令牌；正式设备和会员会话授权待实现，不把终端写权限发给小程序。
- 查询返回 last_synced_at 及同步状态。机顶盒离线时，小程序显示最后同步的数据与时间，不承诺实时余额。
- 预约请求在机顶盒确认前保持待确认，不因云端受理即宣称门店已接受。

当前已提供 Docker Cloud API、终端上传器及内部只读 GET /api/v1/miniapp/me，详见 [云端部署](CLOUD_DEPLOYMENT.md)。其响应为 `{ok:true,data:...,last_synced_at:...}`；终端上传使用独立共享令牌，读取通过内部令牌和 X-LiteShop-Member 指定会员，仅适用于受控联调，不是微信身份认证。不得将内部令牌发给小程序。微信登录、会员绑定、细粒度授权、其余只读子路径、预约、游标下载均未实现；上文其余接口和 request_id 响应格式仍为目标契约。
