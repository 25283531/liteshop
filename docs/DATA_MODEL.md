# LiteShop 数据模型（Phase 1）

SQLite 建表脚本位于 `liteshop/storage/migrations/001_initial.sql`。

- `member_card.balance` 和 `remaining_times` 是查询缓存；`ledger_transaction` 是储值/次卡事实账本。
- `points_account.balance` 是积分查询缓存；`points_transaction` 是积分事实账本。
- `sync_event` 是本地 outbox；业务表和 outbox 必须在同一事务中提交。
- `command_receipt` 使用业务请求 ID 防止网络重试造成重复扣款。
- `operation_log`、交易流水和积分流水均为追加记录。
- 财务事件只能追加，云端同步不能覆盖余额。

金额和积分均为整数；金额单位为分。所有主键使用 UUID 字符串，便于多设备离线创建。
