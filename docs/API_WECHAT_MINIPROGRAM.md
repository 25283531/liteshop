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
