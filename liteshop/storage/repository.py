"""SQLite implementation; one local shop and a single writer database."""
from .database import Database

TABLES = {
    "shop", "device", "staff", "member", "card_type", "member_card",
    "ledger_transaction", "points_account", "points_transaction",
    "operation_log", "sync_event", "sync_state", "command_receipt",
}
MUTABLE = {"member", "member_card", "points_account"}

class SQLiteRepository:
    def __init__(self, path):
        self.db = Database(path)
        self.conn = self.db.connection

    def close(self):
        self.db.close()

    def atomic(self):
        return self.db.atomic()

    def _table(self, table):
        if table not in TABLES:
            raise ValueError("Unknown table")
        return table

    def one(self, table, identity):
        table = self._table(table)
        key = "member_id" if table == "points_account" else "id"
        row = self.conn.execute(f"SELECT * FROM {table} WHERE {key} = ?", (identity,)).fetchone()
        return dict(row) if row else None

    def _columns(self, table, values):
        allowed = {row[1] for row in self.conn.execute(f"PRAGMA table_info({table})")}
        if not values or not set(values) <= allowed:
            raise ValueError("Unknown columns")

    def insert(self, table, values):
        table = self._table(table)
        self._columns(table, values)
        columns = ",".join(values)
        marks = ",".join("?" for _ in values)
        self.conn.execute(f"INSERT INTO {table} ({columns}) VALUES ({marks})", tuple(values.values()))

    def update(self, table, identity, values):
        if table not in MUTABLE:
            raise ValueError("Table cannot be modified")
        self._columns(table, values)
        key = "member_id" if table == "points_account" else "id"
        changes = ",".join(f"{k} = ?" for k in values)
        self.conn.execute(f"UPDATE {table} SET {changes} WHERE {key} = ?", (*values.values(), identity))

    def context(self):
        row = self.conn.execute("SELECT s.id AS shop_id, d.id AS device_id, f.id AS operator_id FROM shop s JOIN device d ON d.shop_id=s.id JOIN staff f ON f.shop_id=s.id AND f.role='OWNER' LIMIT 1").fetchone()
        return dict(row) if row else None

    def receipt(self, request_id):
        row = self.conn.execute("SELECT * FROM command_receipt WHERE request_id = ?", (request_id,)).fetchone()
        return dict(row) if row else None

    def members(self, query="", limit=50, offset=0):
        return [dict(r) for r in self.conn.execute("SELECT * FROM member WHERE deleted_at IS NULL AND (instr(name, ?) > 0 OR instr(COALESCE(phone,''), ?) > 0 OR instr(member_no, ?) > 0) ORDER BY created_at, id LIMIT ? OFFSET ?", (query, query, query, limit, offset))]

    def cards(self, member_id):
        return [dict(r) for r in self.conn.execute("SELECT c.*, t.name AS type_name, t.mode FROM member_card c JOIN card_type t ON c.card_type_id=t.id WHERE member_id=? ORDER BY c.created_at,c.id", (member_id,))]

    def card_types(self):
        return [dict(r) for r in self.conn.execute("SELECT * FROM card_type ORDER BY mode")]

    def ledger(self, member_id, limit=50, offset=0):
        return [dict(r) for r in self.conn.execute("SELECT * FROM ledger_transaction WHERE member_id=? ORDER BY rowid DESC LIMIT ? OFFSET ?", (member_id, limit, offset))]

    def refunded(self, source_id):
        return dict(self.conn.execute("SELECT COALESCE(SUM(amount),0) AS amount, COALESCE(SUM(times),0) AS times FROM ledger_transaction WHERE source_id=? AND kind='REFUND'", (source_id,)).fetchone())

    def events(self, limit=100):
        import json
        rows = [dict(r) for r in self.conn.execute("SELECT * FROM sync_event WHERE status IN ('PENDING','FAILED') ORDER BY sequence LIMIT ?", (limit,))]
        for row in rows:
            row["payload"] = json.loads(row["payload"])
        return rows

    def audit(self):
        issues = []
        for card in self.conn.execute("SELECT * FROM member_card"):
            balance = times = 0
            for tx in self.conn.execute("SELECT * FROM ledger_transaction WHERE card_id=? ORDER BY rowid", (card["id"],)):
                if (tx["balance_before"], tx["times_before"]) != (balance, times):
                    issues.append({"id": tx["id"], "error": "ledger chain mismatch"})
                balance += tx["amount"]
                times += tx["times"]
            if (card["balance"], card["remaining_times"]) != (balance, times):
                issues.append({"id": card["id"], "error": "card balance mismatch"})
        for account in self.conn.execute("SELECT * FROM points_account"):
            balance = 0
            for tx in self.conn.execute("SELECT * FROM points_transaction WHERE member_id=? ORDER BY rowid", (account["member_id"],)):
                if tx["balance_before"] != balance:
                    issues.append({"id": tx["id"], "error": "points chain mismatch"})
                balance += tx["points"]
            if balance != account["balance"]:
                issues.append({"id": account["member_id"], "error": "points balance mismatch"})
        issues.extend({"error": "foreign key violation", "detail": list(r)} for r in self.conn.execute("PRAGMA foreign_key_check"))
        integrity = [r[0] for r in self.conn.execute("PRAGMA integrity_check")]
        if integrity != ["ok"]:
            issues.append({"error": "integrity check failed", "detail": integrity})
        return {"ok": not issues, "issues": issues}
