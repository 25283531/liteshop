"""Small standard-library Cloud API for Terminal outbox sync and mini-program reads."""
import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import smtplib
import sqlite3
import time
import threading
from email.message import EmailMessage
from urllib.parse import urlsplit, parse_qs
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


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
CREATE TABLE IF NOT EXISTS terminal_registry (
  device_id TEXT PRIMARY KEY, shop_id TEXT NOT NULL, name TEXT NOT NULL DEFAULT '',
  serial_no TEXT UNIQUE, status TEXT NOT NULL DEFAULT 'ACTIVE', last_seen INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS cloud_shop (
  shop_id TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS cloud_settings (
  key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS cloud_user (
  user_id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE', created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS user_session (
  token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES cloud_user(user_id), expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS user_terminal (
  user_id TEXT NOT NULL REFERENCES cloud_user(user_id), device_id TEXT NOT NULL REFERENCES terminal_registry(device_id),
  shop_id TEXT NOT NULL, bound_at INTEGER NOT NULL, PRIMARY KEY(user_id, device_id)
);
CREATE TABLE IF NOT EXISTS service_subscription (
  user_id TEXT NOT NULL REFERENCES cloud_user(user_id), shop_id TEXT NOT NULL, service_code TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'REQUESTED', requested_at INTEGER NOT NULL, PRIMARY KEY(user_id, shop_id, service_code)
);
CREATE TABLE IF NOT EXISTS message_campaign (
  campaign_id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES cloud_user(user_id), shop_id TEXT NOT NULL,
  channel TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'QUEUED', created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS wechat_follower (
  shop_id TEXT NOT NULL, openid TEXT NOT NULL, member_id TEXT,
  followed_at INTEGER NOT NULL, PRIMARY KEY(shop_id, openid)
);
"""

WEB_ROOT = Path(__file__).with_name("web")


def stamp():
    return int(time.time() * 1000)


def fingerprint(value):
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def password_hash(password, salt=None):
    salt = salt or secrets.token_bytes(16)
    iterations = 210000
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations)
    return "pbkdf2_sha256${}${}${}".format(iterations, base64.urlsafe_b64encode(salt).decode(), digest.hex())


def password_matches(password, encoded):
    try:
        algorithm, iterations, salt, digest = encoded.split("$", 3)
        if algorithm != "pbkdf2_sha256":
            return False
        actual = hashlib.pbkdf2_hmac("sha256", password.encode(), base64.urlsafe_b64decode(salt.encode()), int(iterations)).hex()
        return hmac.compare_digest(actual, digest)
    except (ValueError, TypeError):
        return False


class RegistrationMailer:
    def __init__(self, store):
        self.store = store

    def send_registration(self, email, username):
        settings = self.store.integration_settings()
        host = settings.get("smtp_host", "")
        port = int(settings.get("smtp_port", "587") or 587)
        smtp_username = settings.get("smtp_username", "")
        smtp_password = settings.get("smtp_password", "")
        sender = settings.get("smtp_from", smtp_username)
        ssl = settings.get("smtp_ssl", "0") == "1"
        if not host or not sender:
            raise RuntimeError("SMTP is not configured")
        message = EmailMessage()
        message["Subject"] = "LiteShop 注册成功"
        message["From"] = sender
        message["To"] = email
        message.set_content("您好，{}：\n\n您的 LiteShop 云端账号已注册成功。\n注册用户名：{}\n\n请妥善保管账号信息。".format(username, username))
        client = smtplib.SMTP_SSL(host, port, timeout=15) if ssl else smtplib.SMTP(host, port, timeout=15)
        try:
            if not ssl:
                client.starttls()
            if smtp_username:
                client.login(smtp_username, smtp_password)
            client.send_message(message)
        finally:
            client.quit()


class CloudStore:
    def __init__(self, path):
        self.lock = threading.RLock()
        self.conn = sqlite3.connect(path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA)
        try:
            self.conn.execute("ALTER TABLE terminal_registry ADD COLUMN serial_no TEXT")
        except sqlite3.OperationalError:
            pass
        self.conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_terminal_serial ON terminal_registry(serial_no) WHERE serial_no IS NOT NULL")
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
                if event["event_type"] not in {"SHOP_INITIALIZED", "SHOP_SETTINGS_UPDATED", "CARD_TYPE_CREATED", "MEMBER_CREATED", "MEMBER_UPDATED", "MEMBER_DELETED", "CARD_OPENED", "CARD_STATUS_CHANGED", "CARD_TRANSACTION", "POINTS_CHANGED"}:
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
                serial = event.get("serial_no")
                if serial is not None and (not isinstance(serial, str) or not re.fullmatch(r"[A-Z0-9]{16}", serial)):
                    raise ValueError("invalid serial_no")
                if serial is not None:
                    serial_owner = self.conn.execute("SELECT device_id FROM terminal_registry WHERE serial_no=?", (serial,)).fetchone()
                    if serial_owner and serial_owner[0] != event["device_id"]:
                        raise ValueError("serial_no belongs to another terminal")
                self.conn.execute("INSERT INTO terminal_registry(device_id,shop_id,serial_no,last_seen) VALUES (?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET shop_id=excluded.shop_id,serial_no=COALESCE(excluded.serial_no,terminal_registry.serial_no),last_seen=excluded.last_seen", (event["device_id"], event["shop_id"], serial, stamp()))
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
        if kind in ("SHOP_INITIALIZED", "SHOP_SETTINGS_UPDATED"):
            shop_id = payload.get("shop_id", event["shop_id"])
            name = payload.get("name", payload.get("shop_name", ""))
            if not isinstance(shop_id, str) or not isinstance(name, str):
                raise ValueError("shop event requires string shop_id and name")
            self.conn.execute("INSERT INTO cloud_shop(shop_id,name,updated_at) VALUES (?,?,?) ON CONFLICT(shop_id) DO UPDATE SET name=excluded.name,updated_at=excluded.updated_at", (shop_id, name, stamp()))
            return
        if kind in ("MEMBER_CREATED", "MEMBER_UPDATED", "MEMBER_DELETED"):
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

    def shops(self):
        with self.lock:
            rows = self.conn.execute("SELECT s.shop_id,s.name,COUNT(m.member_id) AS member_count,s.updated_at FROM cloud_shop s LEFT JOIN member_projection m ON m.shop_id=s.shop_id GROUP BY s.shop_id ORDER BY s.shop_id").fetchall()
            return [dict(r) for r in rows]

    def admin_summary(self):
        with self.lock:
            counts = {
                "events": self.conn.execute("SELECT COUNT(*) FROM cloud_event").fetchone()[0],
                "members": self.conn.execute("SELECT COUNT(*) FROM member_projection").fetchone()[0],
                "cards": self.conn.execute("SELECT COUNT(*) FROM card_projection").fetchone()[0],
                "terminals": self.conn.execute("SELECT COUNT(*) FROM terminal_registry").fetchone()[0],
                "shops": self.conn.execute("SELECT COUNT(*) FROM cloud_shop").fetchone()[0],
            }
            terminals = [dict(r) for r in self.conn.execute("SELECT device_id,shop_id,name,serial_no,status,last_seen FROM terminal_registry ORDER BY last_seen DESC").fetchall()]
            settings = self.public_settings()
            return {"counts": counts, "shops": self.shops(), "terminals": terminals, "settings": settings}

    INTEGRATION_KEYS = {
        "smtp_host", "smtp_port", "smtp_username", "smtp_password", "smtp_from", "smtp_ssl",
        "miniapp_app_id", "miniapp_app_secret", "miniapp_message_template_id",
        "wechat_app_id", "wechat_app_secret", "wechat_token", "wechat_message_template_id",
    }

    def integration_settings(self):
        with self.lock:
            return {r[0]: r[1] for r in self.conn.execute("SELECT key,value FROM cloud_settings WHERE key IN ({})".format(",".join("?" * len(self.INTEGRATION_KEYS))), tuple(self.INTEGRATION_KEYS)).fetchall()}

    def public_settings(self):
        with self.lock:
            values = {r[0]: r[1] for r in self.conn.execute("SELECT key,value FROM cloud_settings ORDER BY key").fetchall()}
        result = {}
        for key, value in values.items():
            if key in {"smtp_password", "miniapp_app_secret", "wechat_app_secret", "wechat_token"}:
                result[key + "_configured"] = bool(value)
            else:
                result[key] = value
        return result

    def update_settings(self, values):
        if not isinstance(values, dict):
            raise ValueError("settings must be an object")
        allowed = {"display_name", "notice"} | self.INTEGRATION_KEYS
        if any(key not in allowed for key in values):
            raise ValueError("unsupported setting")
        with self.lock, self.conn:
            for key, value in values.items():
                if not isinstance(value, str) or len(value) > 500:
                    raise ValueError("setting values must be strings of at most 200 characters")
                if key == "smtp_port" and value and (not value.isdigit() or not 1 <= int(value) <= 65535):
                    raise ValueError("SMTP 端口无效")
                if key == "smtp_ssl" and value not in {"0", "1"}:
                    raise ValueError("SMTP SSL 只能为 0 或 1")
                self.conn.execute("INSERT INTO cloud_settings(key,value,updated_at) VALUES (?,?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at", (key, value, stamp()))
            return self.public_settings()

    def register_user(self, email, username, password):
        email = email.strip().lower() if isinstance(email, str) else ""
        username = username.strip() if isinstance(username, str) else ""
        if not re.fullmatch(r"[^@\s]{1,100}@[^@\s]{1,100}\.[^@\s]{2,100}", email):
            raise ValueError("请输入有效邮箱")
        if not re.fullmatch(r"[\w\u4e00-\u9fff.-]{2,64}", username):
            raise ValueError("用户名需为 2-64 位字母、数字、中文、下划线、点或短横线")
        if not isinstance(password, str) or len(password) < 8 or len(password) > 128:
            raise ValueError("密码长度需为 8-128 位")
        user_id = secrets.token_hex(16)
        with self.lock, self.conn:
            try:
                self.conn.execute("INSERT INTO cloud_user VALUES (?,?,?,?,?,?)", (user_id, email, username, password_hash(password), "ACTIVE", stamp()))
            except sqlite3.IntegrityError:
                raise ValueError("邮箱或用户名已经注册")
        return {"user_id": user_id, "email": email, "username": username}

    def login_user(self, email, password):
        with self.lock, self.conn:
            row = self.conn.execute("SELECT user_id,email,username,password_hash,status FROM cloud_user WHERE email=?", ((email or "").strip().lower(),)).fetchone()
            if not row or row[4] != "ACTIVE" or not password_matches(password or "", row[3]):
                raise ValueError("邮箱或密码错误")
            raw = secrets.token_urlsafe(32)
            self.conn.execute("INSERT INTO user_session VALUES (?,?,?)", (hashlib.sha256(raw.encode()).hexdigest(), row[0], stamp() + 30 * 24 * 3600 * 1000))
            return {"token": raw, "user_id": row[0], "email": row[1], "username": row[2]}

    def cancel_registration(self, user_id):
        with self.lock, self.conn:
            self.conn.execute("DELETE FROM cloud_user WHERE user_id=?", (user_id,))

    def session_user(self, raw_token):
        if not isinstance(raw_token, str) or not raw_token:
            return None
        with self.lock:
            row = self.conn.execute("SELECT u.user_id,u.email,u.username FROM user_session s JOIN cloud_user u ON u.user_id=s.user_id WHERE s.token_hash=? AND s.expires_at>? AND u.status='ACTIVE'", (hashlib.sha256(raw_token.encode()).hexdigest(), stamp())).fetchone()
            return dict(row) if row else None

    def bind_terminal(self, user_id, serial):
        if not isinstance(serial, str) or not re.fullmatch(r"[A-Z0-9]{16}", serial.strip().upper()):
            raise ValueError("请输入终端显示的 16 位序列号")
        serial = serial.strip().upper()
        with self.lock, self.conn:
            row = self.conn.execute("SELECT device_id,shop_id FROM terminal_registry WHERE serial_no=?", (serial,)).fetchone()
            if not row:
                raise ValueError("未找到该序列号对应的在线终端，请先让终端同步一次")
            self.conn.execute("INSERT OR IGNORE INTO user_terminal VALUES (?,?,?,?)", (user_id, row[0], row[1], stamp()))
            return {"device_id": row[0], "shop_id": row[1], "serial_no": serial}

    def terminal_account(self, serial):
        if not isinstance(serial, str) or not re.fullmatch(r"[A-Z0-9]{16}", serial.strip().upper()):
            return None
        serial = serial.strip().upper()
        with self.lock:
            row = self.conn.execute("SELECT u.username FROM user_terminal ut JOIN terminal_registry t ON t.device_id=ut.device_id JOIN cloud_user u ON u.user_id=ut.user_id WHERE t.serial_no=? ORDER BY ut.bound_at DESC LIMIT 1", (serial,)).fetchone()
            return row[0] if row else None

    def user_summary(self, user_id):
        with self.lock:
            shops = [dict(r) for r in self.conn.execute("SELECT s.shop_id,s.name,COUNT(DISTINCT m.member_id) AS member_count,COUNT(DISTINCT c.card_id) AS card_count,COALESCE((SELECT COUNT(*) FROM cloud_event e WHERE e.shop_id=s.shop_id AND e.event_type='CARD_TRANSACTION'),0) AS transaction_count FROM user_terminal ut JOIN cloud_shop s ON s.shop_id=ut.shop_id LEFT JOIN member_projection m ON m.shop_id=ut.shop_id LEFT JOIN card_projection c ON c.shop_id=ut.shop_id WHERE ut.user_id=? GROUP BY s.shop_id", (user_id,)).fetchall()]
            terminals = [dict(r) for r in self.conn.execute("SELECT t.device_id,t.serial_no,t.shop_id,t.name,t.status,t.last_seen FROM user_terminal ut JOIN terminal_registry t ON t.device_id=ut.device_id WHERE ut.user_id=?", (user_id,)).fetchall()]
            services = [dict(r) for r in self.conn.execute("SELECT shop_id,service_code,status,requested_at FROM service_subscription WHERE user_id=? ORDER BY requested_at DESC", (user_id,)).fetchall()]
            return {"shops": shops, "terminals": terminals, "services": services}

    def user_members(self, user_id, shop_id=None):
        with self.lock:
            if shop_id:
                allowed = self.conn.execute("SELECT 1 FROM user_terminal WHERE user_id=? AND shop_id=? LIMIT 1", (user_id, shop_id)).fetchone()
                if not allowed:
                    raise ValueError("无权访问该店铺")
                rows = self.conn.execute("SELECT member_id,shop_id,payload FROM member_projection WHERE shop_id=?", (shop_id,)).fetchall()
            else:
                rows = self.conn.execute("SELECT member_id,shop_id,payload FROM member_projection WHERE shop_id IN (SELECT shop_id FROM user_terminal WHERE user_id=?)", (user_id,)).fetchall()
            return [dict(member_id=r[0], shop_id=r[1], **json.loads(r[2])) for r in rows]

    def request_service(self, user_id, shop_id, service_code):
        with self.lock, self.conn:
            allowed = self.conn.execute("SELECT 1 FROM user_terminal WHERE user_id=? AND shop_id=? LIMIT 1", (user_id, shop_id)).fetchone()
            if not allowed or service_code not in {"MINIAPP_NOTICE", "MINIAPP_QUERY", "MINIAPP_APPOINTMENT", "WECHAT_NOTICE", "WECHAT_BROADCAST"}:
                raise ValueError("无权开通该服务或服务代码无效")
            self.conn.execute("INSERT INTO service_subscription VALUES (?,?,?,?,?) ON CONFLICT(user_id,shop_id,service_code) DO UPDATE SET status='REQUESTED',requested_at=excluded.requested_at", (user_id, shop_id, service_code, "REQUESTED", stamp()))
            return {"shop_id": shop_id, "service_code": service_code, "status": "REQUESTED"}

    def create_campaign(self, user_id, shop_id, channel, content):
        if channel not in {"WECHAT_BROADCAST", "WECHAT_NOTICE"}:
            raise ValueError("仅支持公众号消息服务")
        if not isinstance(content, str) or not content.strip() or len(content) > 2000:
            raise ValueError("消息内容不能为空且不能超过 2000 字")
        with self.lock, self.conn:
            allowed = self.conn.execute("SELECT 1 FROM user_terminal WHERE user_id=? AND shop_id=?", (user_id, shop_id)).fetchone()
            active = self.conn.execute("SELECT 1 FROM service_subscription WHERE user_id=? AND shop_id=? AND service_code=? AND status IN ('REQUESTED','ACTIVE')", (user_id, shop_id, channel)).fetchone()
            if not allowed or not active:
                raise ValueError("请先绑定门店并申请对应公众号服务")
            campaign_id = secrets.token_hex(16)
            self.conn.execute("INSERT INTO message_campaign VALUES (?,?,?,?,?,?,?)", (campaign_id, user_id, shop_id, channel, content.strip(), "QUEUED", stamp()))
            follower_count = self.conn.execute("SELECT COUNT(*) FROM wechat_follower WHERE shop_id=?", (shop_id,)).fetchone()[0]
            return {"campaign_id": campaign_id, "shop_id": shop_id, "channel": channel, "status": "QUEUED", "target_count": follower_count}

    def members(self, shop_id=None):
        with self.lock:
            sql = "SELECT member_id,shop_id,payload FROM member_projection"
            args = ()
            if shop_id:
                sql += " WHERE shop_id=?"; args = (shop_id,)
            return [dict(member_id=r[0], shop_id=r[1], **json.loads(r[2])) for r in self.conn.execute(sql, args)]

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

    def admin_auth(self):
        return self.auth("admin")

    def user_auth(self):
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            self.respond(401, {"ok": False, "error": {"code": "UNAUTHORIZED", "message": "请先登录"}})
            return None
        user = self.server.store.session_user(header[7:].strip())
        if not user:
            self.respond(401, {"ok": False, "error": {"code": "UNAUTHORIZED", "message": "登录已失效"}})
            return None
        return user

    def body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 1024 * 1024:
            raise ValueError("invalid body size")
        value = json.loads(self.rfile.read(length).decode())
        if not isinstance(value, dict):
            raise ValueError("body must be an object")
        return value

    def do_GET(self):
        parsed = urlsplit(self.path)
        if parsed.path == "/":
            self.serve_static("index.html", "text/html; charset=utf-8")
            return
        if parsed.path in ("/admin", "/admin/"):
            self.serve_static("admin.html", "text/html; charset=utf-8")
            return
        if parsed.path == "/app.js":
            self.serve_static("app.js", "text/javascript; charset=utf-8")
            return
        if parsed.path == "/account":
            self.serve_static("account.html", "text/html; charset=utf-8")
            return
        if parsed.path.startswith("/account/"):
            asset = parsed.path.removeprefix("/account/")
            if asset in ("app.js", "app.css"):
                self.serve_static("account-" + asset, "text/javascript; charset=utf-8" if asset.endswith(".js") else "text/css; charset=utf-8")
                return
        if parsed.path.startswith("/admin/"):
            asset = parsed.path.removeprefix("/admin/")
            if asset in ("app.js", "app.css"):
                self.serve_static("admin-app.js" if asset == "app.js" else asset, "text/javascript; charset=utf-8" if asset.endswith(".js") else "text/css; charset=utf-8")
                return
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
        if parsed.path == "/api/v1/cloud/shops":
            if not self.auth("miniapp"):
                return
            self.respond(200, {"ok": True, "data": self.server.store.shops()})
            return
        if parsed.path == "/api/v1/cloud/members":
            if not self.auth("miniapp"):
                return
            shop_id = parse_qs(parsed.query).get("shop_id", [None])[0]
            self.respond(200, {"ok": True, "data": self.server.store.members(shop_id)})
            return
        if parsed.path == "/api/v1/admin/summary":
            if not self.admin_auth():
                return
            self.respond(200, {"ok": True, "data": self.server.store.admin_summary()})
            return
        if parsed.path == "/api/v1/account":
            user = self.user_auth()
            if user:
                self.respond(200, {"ok": True, "data": {"user": user, **self.server.store.user_summary(user["user_id"])}})
            return
        if parsed.path == "/api/v1/terminal/account":
            serial = parse_qs(parsed.query).get("serial_no", [""])[0]
            username = self.server.store.terminal_account(serial)
            self.respond(200, {"ok": True, "data": {"bound": bool(username), "username": username or ""}})
            return
        if parsed.path == "/api/v1/account/members":
            user = self.user_auth()
            if user:
                shop_id = parse_qs(parsed.query).get("shop_id", [None])[0]
                try:
                    self.respond(200, {"ok": True, "data": self.server.store.user_members(user["user_id"], shop_id)})
                except ValueError as exc:
                    self.respond(403, {"ok": False, "error": {"code": "FORBIDDEN", "message": str(exc)}})
            return
        self.respond(404, {"ok": False, "error": {"code": "NOT_FOUND", "message": "接口不存在"}})

    def do_POST(self):
        path = urlsplit(self.path).path
        if path == "/api/v1/admin/login":
            try:
                values = self.body()
                username = values.get("username") if isinstance(values.get("username"), str) else ""
                password = values.get("password") if isinstance(values.get("password"), str) else ""
                if not self.server.admin_username or not self.server.admin_password or not hmac.compare_digest(username, self.server.admin_username) or not hmac.compare_digest(password, self.server.admin_password):
                    raise ValueError("管理员账户名或密码错误")
                self.respond(200, {"ok": True, "data": {"requires_token": True}, "message": "账户验证成功，请继续输入管理令牌"})
            except ValueError as exc:
                self.respond(401, {"ok": False, "error": {"code": "INVALID_ADMIN_CREDENTIALS", "message": str(exc)}})
            return
        if path == "/api/v1/auth/register":
            try:
                values = self.body()
                result = self.server.store.register_user(values.get("email"), values.get("username"), values.get("password"))
                try:
                    self.server.mailer.send_registration(result["email"], result["username"])
                except Exception:
                    self.server.store.cancel_registration(result["user_id"])
                    raise
                self.respond(201, {"ok": True, "data": result, "message": "注册成功，注册用户名已发送到您的邮箱"})
            except ValueError as exc:
                self.respond(400, {"ok": False, "error": {"code": "INVALID_REGISTRATION", "message": str(exc)}})
            except (RuntimeError, OSError, smtplib.SMTPException) as exc:
                self.respond(503, {"ok": False, "error": {"code": "MAIL_DELIVERY_FAILED", "message": "注册邮件发送失败，账号未创建，请稍后重试或联系管理员"}})
            return
        if path == "/api/v1/auth/login":
            try:
                values = self.body()
                self.respond(200, {"ok": True, "data": self.server.store.login_user(values.get("email"), values.get("password"))})
            except ValueError as exc:
                self.respond(401, {"ok": False, "error": {"code": "INVALID_CREDENTIALS", "message": str(exc)}})
            return
        if path == "/api/v1/account/terminals":
            user = self.user_auth()
            if not user:
                return
            try:
                result = self.server.store.bind_terminal(user["user_id"], self.body().get("serial_no"))
                self.respond(201, {"ok": True, "data": result})
            except ValueError as exc:
                self.respond(400, {"ok": False, "error": {"code": "BIND_FAILED", "message": str(exc)}})
            return
        if path == "/api/v1/account/services":
            user = self.user_auth()
            if not user:
                return
            try:
                values = self.body()
                self.respond(201, {"ok": True, "data": self.server.store.request_service(user["user_id"], values.get("shop_id"), values.get("service_code"))})
            except ValueError as exc:
                self.respond(400, {"ok": False, "error": {"code": "SERVICE_REQUEST_FAILED", "message": str(exc)}})
            return
        if path == "/api/v1/account/message-campaigns":
            user = self.user_auth()
            if not user:
                return
            try:
                values = self.body()
                result = self.server.store.create_campaign(user["user_id"], values.get("shop_id"), values.get("channel"), values.get("content"))
                self.respond(201, {"ok": True, "data": result})
            except ValueError as exc:
                self.respond(400, {"ok": False, "error": {"code": "CAMPAIGN_FAILED", "message": str(exc)}})
            return
        if path != "/api/v1/terminal/sync/events":
            self.respond(404, {"ok": False, "error": {"code": "NOT_FOUND", "message": "接口不存在"}})
            return
        if not self.auth("terminal"):
            return
        try:
            raw = self.body()
            timestamp = self.headers.get("X-LiteShop-Timestamp")
            nonce = self.headers.get("X-LiteShop-Nonce")
            signature = self.headers.get("X-LiteShop-Signature")
            if timestamp and nonce and signature:
                if abs(int(time.time() * 1000) - int(timestamp)) > 300000:
                    raise ValueError("request timestamp expired")
                expected = hmac.new(self.server.tokens["terminal"].encode(), (timestamp + "\n" + nonce + "\n" + json.dumps(raw, ensure_ascii=False, separators=(",", ":"))).encode(), hashlib.sha256).hexdigest()
                if not hmac.compare_digest(expected, signature):
                    raise ValueError("invalid request signature")
            elif self.client_address[0] not in ("127.0.0.1", "::1"):
                raise ValueError("signed request required")
            result = self.server.store.ingest(raw.get("events"))
            self.respond(200, {"ok": True, "data": {"accepted": result}})
        except (ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
            self.respond(400, {"ok": False, "error": {"code": "INVALID_EVENT", "message": str(exc)}})
        except sqlite3.Error:
            self.respond(503, {"ok": False, "error": {"code": "DATABASE_UNAVAILABLE", "message": "retry the same events"}})

    def do_PUT(self):
        if urlsplit(self.path).path != "/api/v1/admin/settings":
            self.respond(404, {"ok": False, "error": {"code": "NOT_FOUND", "message": "接口不存在"}})
            return
        if not self.admin_auth():
            return
        try:
            self.respond(200, {"ok": True, "data": self.server.store.update_settings(self.body())})
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            self.respond(400, {"ok": False, "error": {"code": "INVALID_SETTINGS", "message": str(exc)}})

    def serve_static(self, name, content_type):
        path = (WEB_ROOT / name).resolve()
        if WEB_ROOT.resolve() not in path.parents or not path.is_file():
            self.respond(404, {"ok": False, "error": {"code": "NOT_FOUND", "message": "页面不存在"}})
            return
        encoded = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(encoded)


class CloudServer(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, db, terminal_token, miniapp_token, admin_token=None, admin_username=None, admin_password=None):
        self.store = CloudStore(db)
        self.mailer = RegistrationMailer(self.store)
        self.tokens = {"terminal": terminal_token, "miniapp": miniapp_token, "admin": admin_token or miniapp_token}
        self.admin_username = admin_username if admin_username is not None else os.environ.get("LITESHOP_ADMIN_USERNAME", "")
        self.admin_password = admin_password if admin_password is not None else os.environ.get("LITESHOP_ADMIN_PASSWORD", "")
        super().__init__(address, Handler)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--db", default="data/cloud.sqlite")
    args = parser.parse_args()
    terminal_token = os.environ.get("LITESHOP_TERMINAL_TOKEN")
    miniapp_token = os.environ.get("LITESHOP_MINIAPP_TOKEN")
    admin_token = os.environ.get("LITESHOP_ADMIN_TOKEN")
    admin_username = os.environ.get("LITESHOP_ADMIN_USERNAME")
    admin_password = os.environ.get("LITESHOP_ADMIN_PASSWORD")
    if not terminal_token or not miniapp_token or not admin_token:
        parser.error("LITESHOP_TERMINAL_TOKEN, LITESHOP_MINIAPP_TOKEN and LITESHOP_ADMIN_TOKEN are required")
    os.makedirs(os.path.dirname(os.path.abspath(args.db)), exist_ok=True)
    server = CloudServer((args.host, args.port), args.db, terminal_token, miniapp_token, admin_token, admin_username, admin_password)
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
