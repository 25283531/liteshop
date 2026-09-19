"""Small standard-library Cloud API for Terminal outbox sync and mini-program reads."""
import argparse
import hashlib
import hmac
import json
import os
import sqlite3
import time
import threading
from urllib.parse import urlsplit
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


SCHEMA = """
CREATE TABLE IF NOT EXISTS cloud_event (
  event_id TEXT PRIMARY KEY, shop_id TEXT NOT NULL, device_id TEXT NOT NULL,
  sequence INTEGER, schema_version INTEGER NOT NULL, event_type TEXT NOT NULL,
  entity_id TEXT NOT NULL, payload TEXT NOT NULL, fingerprint TEXT NOT NULL,
  created_at INTEGER NOT NULL, received_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS event_sequence ON cloud_event(shop_id,device_id,sequence);
CREATE TABLE IF NOT EXISTS entity_owner (
  entity_id TEXT PRIMARY KEY, shop_id TEXT NOT NULL, device_id TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS member_projection (
  member_id TEXT PRIMARY KEY, shop_id TEXT NOT NULL, payload TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS card_projection (
  card_id TEXT PRIMARY KEY, member_id TEXT NOT NULL, shop_id TEXT NOT NULL,
  payload TEXT NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS points_projection (
  member_id TEXT PRIMARY KEY, shop_id TEXT NOT NULL, payload TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
"""


def stamp():
    return int(time.time() * 1000)


def fingerprint(value):
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


class CloudStore:
    def __init__(self, path):
        self.lock = threading.RLock()
        self.conn = sqlite3.connect(path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA)
        self.conn.commit()

    def close(self):
        with self.lock:
            self.conn.close()

    def ingest(self, events):
        if not isinstance(events, list) or len(events) > 500:
            raise ValueError("events must be an array with at most 500 items")
        accepted = []
        with self.lock, self.conn:
            for event in events:
                if not isinstance(event, dict):
                    raise ValueError("event must be an object")
                required = {"event_id", "shop_id", "device_id", "event_type", "entity_id", "payload", "created_at"}
                if not required <= set(event):
                    raise ValueError("event is missing required fields")
                body = event["payload"]
                if not isinstance(body, dict):
                    raise ValueError("event payload must be an object")
                for field in ("event_id", "shop_id", "device_id", "event_type", "entity_id"):
                    if not isinstance(event[field], str) or not 1 <= len(event[field]) <= 128:
                        raise ValueError("invalid " + field)
                for field, minimum in (("sequence", 1), ("created_at", 0)):
                    if type(event.get(field)) is not int or not minimum <= event[field] <= 2**63 - 1:
                        raise ValueError("invalid " + field)
                if type(event.get("schema_version", 1)) is not int or event.get("schema_version", 1) != 1:
                    raise ValueError("unsupported schema_version")
                if event["event_type"] not in {"SHOP_INITIALIZED", "CARD_TYPE_CREATED", "MEMBER_CREATED", "MEMBER_UPDATED", "CARD_OPENED", "CARD_STATUS_CHANGED", "CARD_TRANSACTION", "POINTS_CHANGED"}:
                    raise ValueError("unsupported event_type")
                event_id = str(event["event_id"])
                digest = fingerprint(event)
                previous = self.conn.execute("SELECT fingerprint FROM cloud_event WHERE event_id=?", (event_id,)).fetchone()
                if previous:
                    if previous[0] != digest:
                        raise ValueError("event_id already used for different content")
                    accepted.append({"event_id": event_id, "status": "ALREADY_ACCEPTED"})
                    continue
                last = self.conn.execute("SELECT COALESCE(MAX(sequence),0) FROM cloud_event WHERE shop_id=? AND device_id=?",
                                         (event["shop_id"], event["device_id"])).fetchone()[0]
                if event["sequence"] != last + 1:
                    raise ValueError("sequence must follow last accepted event: " + str(last))
                for field in ("shop_id", "device_id"):
                    if field in body and body[field] != event[field]:
                        raise ValueError("payload " + field + " does not match event")
                # Each local entity has one authoritative writer in this skeleton.
                self._own(event["entity_id"], event)
                for field in ("member_id", "card_id"):
                    if field in body:
                        self._own(body[field], event)
                self.conn.execute("INSERT INTO cloud_event VALUES (?,?,?,?,?,?,?,?,?,?,?)", (
                    event_id, str(event["shop_id"]), str(event["device_id"]), event.get("sequence"),
                    int(event.get("schema_version", 1)), str(event["event_type"]), str(event["entity_id"]),
                    json.dumps(body, ensure_ascii=False, sort_keys=True), digest, int(event["created_at"]), stamp()))
                self._project(event)
                accepted.append({"event_id": event_id, "status": "ACCEPTED"})
        return accepted

    def _own(self, entity_id, event):
        if not isinstance(entity_id, str) or not 1 <= len(entity_id) <= 128:
            raise ValueError("invalid entity reference")
        owner = self.conn.execute("SELECT shop_id,device_id FROM entity_owner WHERE entity_id=?", (entity_id,)).fetchone()
        if owner and tuple(owner) != (event["shop_id"], event["device_id"]):
            raise ValueError("entity belongs to another terminal")
        self.conn.execute("INSERT OR IGNORE INTO entity_owner VALUES (?,?,?)", (entity_id, event["shop_id"], event["device_id"]))

    def _project(self, event):
        payload = event["payload"]
        kind = event["event_type"]
        if kind in ("MEMBER_CREATED", "MEMBER_UPDATED"):
            self.conn.execute("INSERT INTO member_projection VALUES (?,?,?,?) ON CONFLICT(member_id) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at",
                              (event["entity_id"], event["shop_id"], json.dumps(payload, ensure_ascii=False), stamp()))
        elif kind == "CARD_OPENED" or kind == "CARD_STATUS_CHANGED":
            self.conn.execute("INSERT INTO card_projection VALUES (?,?,?,?,?) ON CONFLICT(card_id) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at",
                              (event["entity_id"], payload.get("member_id", ""), event["shop_id"], json.dumps(payload, ensure_ascii=False), stamp()))
        elif kind == "POINTS_CHANGED":
            if "member_id" not in payload or type(payload.get("balance_after")) is not int:
                raise ValueError("points event requires member_id and balance_after")
            payload = {"member_id": payload["member_id"], "balance": payload["balance_after"], "updated_at": event["created_at"]}
            self.conn.execute("INSERT INTO points_projection VALUES (?,?,?,?) ON CONFLICT(member_id) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at",
                              (payload.get("member_id", event["entity_id"]), event["shop_id"], json.dumps(payload, ensure_ascii=False), stamp()))
        elif kind == "CARD_TRANSACTION":
            card_id = payload.get("card_id")
            row = self.conn.execute("SELECT payload FROM card_projection WHERE card_id=?", (card_id,)).fetchone()
            if not row:
                raise ValueError("card must be opened before transactions")
            card = json.loads(row[0])
            if any(type(payload.get(field)) is not int for field in ("balance_after", "times_after")):
                raise ValueError("transaction requires integer balances")
            card.update(balance=payload["balance_after"], remaining_times=payload["times_after"],
                        version=card.get("version", 1) + 1, updated_at=event["created_at"])
            self.conn.execute("UPDATE card_projection SET payload=?,updated_at=? WHERE card_id=?",
                              (json.dumps(card, ensure_ascii=False), stamp(), card_id))

    def member(self, member_id):
        with self.lock:
            return self._member(member_id)

    def _member(self, member_id):
        member = self.conn.execute("SELECT payload,updated_at FROM member_projection WHERE member_id=?", (member_id,)).fetchone()
        if not member:
            return None
        card_rows = self.conn.execute("SELECT payload,updated_at FROM card_projection WHERE member_id=?", (member_id,)).fetchall()
        cards = [json.loads(row[0]) for row in card_rows]
        points = self.conn.execute("SELECT payload,updated_at FROM points_projection WHERE member_id=?", (member_id,)).fetchone()
        data = json.loads(member[0])
        data["cards"] = cards
        data["points"] = json.loads(points[0]) if points else {"member_id": member_id, "balance": 0}
        data["last_synced_at"] = max([member[1]] + [row[1] for row in card_rows] + ([points[1]] if points else []))
        return data


class Handler(BaseHTTPRequestHandler):
    server_version = "LiteShopCloud/0.3"
    def log_message(self, *_):
        pass

    def respond(self, code, body):
        encoded = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(encoded)

    def auth(self, scope):
        token = self.server.tokens.get(scope, "")
        actual = self.headers.get("Authorization", "")
        if not token or not hmac.compare_digest(actual.encode(), ("Bearer " + token).encode()):
            self.respond(401, {"ok": False, "error": {"code": "UNAUTHORIZED", "message": "鉴权失败"}})
            return False
        return True

    def body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 1024 * 1024:
            raise ValueError("invalid body size")
        value = json.loads(self.rfile.read(length).decode())
        if not isinstance(value, dict):
            raise ValueError("body must be an object")
        return value

    def do_GET(self):
        if self.path == "/healthz":
            self.respond(200, {"ok": True, "service": "cloud-api"})
            return
        if urlsplit(self.path).path == "/api/v1/miniapp/me":
            if not self.auth("miniapp"):
                return
            member_id = self.headers.get("X-LiteShop-Member")
            if not member_id:
                self.respond(400, {"ok": False, "error": {"code": "MEMBER_REQUIRED", "message": "需要绑定会员"}})
                return
            data = self.server.store.member(member_id)
            self.respond(200, {"ok": True, "data": data, "last_synced_at": data.get("last_synced_at") if data else None})
            return
        self.respond(404, {"ok": False, "error": {"code": "NOT_FOUND", "message": "接口不存在"}})

    def do_POST(self):
        if self.path != "/api/v1/terminal/sync/events":
            self.respond(404, {"ok": False, "error": {"code": "NOT_FOUND", "message": "接口不存在"}})
            return
        if not self.auth("terminal"):
            return
        try:
            result = self.server.store.ingest(self.body().get("events"))
            self.respond(200, {"ok": True, "data": {"accepted": result}})
        except (ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
            self.respond(400, {"ok": False, "error": {"code": "INVALID_EVENT", "message": str(exc)}})
        except sqlite3.Error:
            self.respond(503, {"ok": False, "error": {"code": "DATABASE_UNAVAILABLE", "message": "retry the same events"}})


class CloudServer(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, db, terminal_token, miniapp_token):
        self.store = CloudStore(db)
        self.tokens = {"terminal": terminal_token, "miniapp": miniapp_token}
        super().__init__(address, Handler)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--db", default="data/cloud.sqlite")
    args = parser.parse_args()
    terminal_token = os.environ.get("LITESHOP_TERMINAL_TOKEN")
    miniapp_token = os.environ.get("LITESHOP_MINIAPP_TOKEN")
    if not terminal_token or not miniapp_token:
        parser.error("LITESHOP_TERMINAL_TOKEN and LITESHOP_MINIAPP_TOKEN are required")
    os.makedirs(os.path.dirname(os.path.abspath(args.db)), exist_ok=True)
    server = CloudServer((args.host, args.port), args.db, terminal_token, miniapp_token)
    print("LiteShop Cloud API: http://{}:{}".format(args.host, args.port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.store.close()
        server.server_close()


if __name__ == "__main__":
    main()
