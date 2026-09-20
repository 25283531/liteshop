# Cloudflare Workers + D1 部署

Workers 版本与 Python Cloud API 保持以下路径和事件协议：

- `GET /healthz`
- `POST /api/v1/terminal/sync/events`
- `GET /api/v1/miniapp/me`
- `GET /api/v1/cloud/shops`
- `GET /api/v1/cloud/members?shop_id=...`

Workers 使用 D1 保存事件和投影，终端同步请求必须带 Bearer 令牌以及时间戳、nonce、HMAC-SHA256 签名。不要把终端令牌或小程序令牌写入前端代码。

## 一键部署（推荐）

为确保 Worker 使用门店预先创建的数据库，请严格按顺序操作。部署按钮不会替用户生成安全令牌：

1. 登录 [Cloudflare Dashboard](https://dash.cloudflare.com/)，在 **Workers & Pages → D1 SQL Database** 创建 D1 数据库，名称必须是 `liteshop-cloud`，并记下 `database_id`。
2. 点击按钮，导入本仓库。为兼容 Cloudflare 当前的仓库 URL 校验，按钮使用仓库根地址；根目录 Wrangler 配置会指向本目录的 Worker：

   <a href="https://deploy.workers.cloudflare.com/?url=https://github.com/25283531/liteshop"><img src="https://deploy.workers.cloudflare.com/button" alt="Deploy LiteShop Cloud API to Cloudflare Workers" /></a>

3. 在部署向导中将配置里的 D1 binding `DB` 绑定到刚创建的 `liteshop-cloud`，并设置 `LITESHOP_TERMINAL_TOKEN`、`LITESHOP_MINIAPP_TOKEN` 两个 secret。两个令牌必须是不同的随机值，不能写入 Git 仓库。
4. 部署脚本会自动执行 `migrations/0001_initial.sql` 初始化 D1 表结构，然后发布 Worker。部署完成后访问 Worker 的 `/healthz` 检查服务。

按钮入口使用标准仓库 URL，不再使用可能触发“存储库 URL 无效”的 `tree/main/apps/cloud/workers` 子目录 URL。如果 Cloudflare 界面无法完成部署，使用下面的 Wrangler 备用流程，结果相同。

## Wrangler 备用部署

```bash
cd apps/cloud/workers
npm install
npx wrangler login
npx wrangler d1 create liteshop-cloud
# 将命令输出的 database_id 写入 wrangler.jsonc（或使用 wrangler.toml.example 作为独立配置）
npx wrangler secret put LITESHOP_TERMINAL_TOKEN
npx wrangler secret put LITESHOP_MINIAPP_TOKEN
npm run deploy
```

仓库中的 `wrangler.jsonc` 使用全零 UUID 作为部署按钮可识别的占位值；在 Cloudflare 向导中必须把 binding `DB` 改为你预先创建的 `liteshop-cloud`，不要直接把全零 UUID 当作真实数据库。命令行部署时，请将实际 `database_id` 写入本地配置，并不要提交包含真实 ID 的配置文件。`npm run deploy` 会执行 `migrations/0001_initial.sql` 后发布 Worker；`schema.sql` 仅作为手工初始化备用文件。

也可以先执行 `npx wrangler dev --local`，然后用终端事件测试接口。生产环境应绑定 `liteshop.250886.xyz`，并在 DNS 中按 Cloudflare 提示添加 Worker 路由或自定义域名。

D1 的事件投影是云端查询副本，机顶盒 SQLite 仍是交易事实源。正式微信登录、用户会话和按店铺授权需要在小程序认证接入后继续完善。
