# Cloudflare Workers + D1 部署

Workers 版本与 Python Cloud API 保持以下路径和事件协议：

- `GET /healthz`
- `POST /api/v1/terminal/sync/events`
- `GET /api/v1/miniapp/me`
- `GET /api/v1/cloud/shops`
- `GET /api/v1/cloud/members?shop_id=...`

Workers 使用 D1 保存事件和投影，终端同步请求必须带 Bearer 令牌以及时间戳、nonce、HMAC-SHA256 签名。不要把终端令牌或小程序令牌写入前端代码。

## 首次部署

```bash
cd apps/cloud/workers
npm install
npx wrangler login
npx wrangler d1 create liteshop-cloud
# 将命令输出的 database_id 写入 wrangler.toml（由 wrangler.toml.example 复制）
cp wrangler.toml.example wrangler.toml
npx wrangler d1 execute liteshop-cloud --remote --file=schema.sql
npx wrangler secret put LITESHOP_TERMINAL_TOKEN
npx wrangler secret put LITESHOP_MINIAPP_TOKEN
npx wrangler deploy
```

也可以先执行 `npx wrangler dev --local`，然后用终端事件测试接口。生产环境应绑定 `liteshop.250886.xyz`，并在 DNS 中按 Cloudflare 提示添加 Worker 路由或自定义域名。

D1 的事件投影是云端查询副本，机顶盒 SQLite 仍是交易事实源。正式微信登录、用户会话和按店铺授权需要在小程序认证接入后继续完善。
