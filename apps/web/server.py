"""Trusted local/LAN terminal. Run: python -m apps.web.server."""
import argparse
import hmac
import inspect
import json
import logging
import os
from pathlib import Path
import secrets
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit
from liteshop.core.service import BusinessError, ShopService
from liteshop.storage.repository import SQLiteRepository

PUBLIC = Path(__file__).with_name("public")
COMMANDS = {"create-member": "create_member", "update-member": "update_member",
            "open-card": "open_card", "card-status": "set_card_status",
            "transact": "transact", "points": "change_points",
            "update-settings": "update_settings", "delete-member": "delete_member"}
INTEGER_FIELDS = {"version", "amount", "times", "points", "expire_at", "status"}
NULLABLE = {"phone", "expire_at", "source_id", "inviter_name", "inviter_member_id", "name", "settings", "local_password"}


class LocalServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, db_path, token, shop_name="我的门店"):
        if not isinstance(token, str) or len(token) < 24 or not token.isascii():
            raise ValueError("Token must contain at least 24 ASCII characters")
        self.db_path, self.token = Path(db_path), token
        repo = SQLiteRepository(self.db_path)
        try:
            ShopService(repo).initialize(shop_name)
        finally:
            repo.close()
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    server_version = "LiteShop/0.2"

    def log_message(self, format, *args):
        pass  # Do not log credentials or member search strings.

    def respond(self, status, body, mime="application/json; charset=utf-8"):
        if not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")
        self.end_headers()
        self.wfile.write(body)

    def error(self, status, code, message, definitive=False):
        self.respond(status, {"ok": False, "error": {"code": code, "message": message, "definitive": definitive}})

    def authorized(self):
        expected = ("Bearer " + self.server.token).encode("ascii")
        if not hmac.compare_digest(self.headers.get("Authorization", "").encode("utf-8"), expected):
            self.error(401, "UNAUTHORIZED", "请输入正确的终端访问密钥")
            return False
        origin = self.headers.get("Origin")
        if origin and origin != "http://" + self.headers.get("Host", ""):
            self.error(403, "ORIGIN_REJECTED", "不接受跨站请求")
            return False
        return True

    def do_GET(self):
        parsed = urlsplit(self.path)
        files = {"/": ("index.html", "text/html"), "/index.html": ("index.html", "text/html"),
                 "/app.js": ("app.js", "application/javascript"), "/app.css": ("app.css", "text/css")}
        if parsed.path in files:
            filename, mime = files[parsed.path]
            self.respond(200, (PUBLIC / filename).read_bytes(), mime + "; charset=utf-8")
        elif not parsed.path.startswith("/api/v1/"):
            self.error(404, "NOT_FOUND", "接口不存在")
        elif self.authorized():
            self.api("GET", parsed)

    def do_POST(self):
        if self.authorized():
            self.api("POST", urlsplit(self.path))

    def api(self, method, parsed):
        repo = None
        try:
            repo = SQLiteRepository(self.server.db_path)
            service = ShopService(repo)
            path, query = parsed.path, parse_qs(parsed.query)
            if method == "GET" and path in ("/api/v1/card-types", "/api/v1/settings"):
                service.ensure_card_type_presets()
            if method == "GET" and path == "/api/v1/status":
                result = repo.terminal_status()
            elif method == "GET" and path == "/api/v1/card-types":
                result = repo.card_types()
            elif method == "GET" and path == "/api/v1/settings":
                result = repo.settings()
            elif method == "GET" and path == "/api/v1/members":
                search, offset = query.get("q", [""])[0], int(query.get("offset", ["0"])[0])
                if len(search) > 200 or not 0 <= offset <= 2_000_000_000:
                    raise ValueError("Invalid query")
                result = repo.members(search, limit=50, offset=offset)
            elif method == "GET" and path.startswith("/api/v1/members/"):
                identity = path[len("/api/v1/members/"):]
                result = service.member_detail(identity)
                result["points_transactions"] = repo.points_ledger(identity)
            elif method == "POST" and path.startswith("/api/v1/commands/"):
                action = COMMANDS.get(path[len("/api/v1/commands/"):])
                if not action:
                    self.error(404, "NOT_FOUND", "操作不存在")
                    return
                if self.headers.get_content_type() != "application/json":
                    self.error(415, "INVALID_CONTENT_TYPE", "请使用 application/json")
                    return
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 16384 or self.headers.get("Transfer-Encoding"):
                    self.error(413, "INVALID_BODY_SIZE", "请求体必须为 1 到 16384 字节")
                    return
                self.connection.settimeout(10)
                payload = json.loads(self.rfile.read(length).decode("utf-8"))
                if not isinstance(payload, dict):
                    raise ValueError("Expected object")
                for key, value in payload.items():
                    if key in NULLABLE and value is None:
                        continue
                    integer_field = key in INTEGER_FIELDS and not (key == "status" and action == "set_card_status")
                    if key == "settings":
                        if not isinstance(value, dict):
                            raise ValueError("Invalid settings")
                    elif type(value) is not (int if integer_field else str):
                        raise ValueError("Invalid field type")
                command = getattr(service, action)
                inspect.signature(command).bind(**payload)
                result = command(**payload)
            else:
                self.error(404, "NOT_FOUND", "接口不存在；微信小程序接口尚未启用")
                return
            self.respond(200, {"ok": True, "data": result})
        except BusinessError as exc:
            self.error(404 if exc.code == "NOT_FOUND" else 409 if exc.code in ("IDEMPOTENCY_CONFLICT", "VERSION_CONFLICT") else 400, exc.code, str(exc), definitive=True)
        except (ValueError, TypeError, UnicodeError):
            self.error(400, "INVALID_INPUT", "请求参数格式错误")
        except sqlite3.OperationalError:
            self.error(503, "DATABASE_UNAVAILABLE", "数据库忙或不可用；请使用原请求重试")
        except Exception:
            logging.exception("Local API failed")
            self.error(500, "INTERNAL_ERROR", "操作结果未知；请使用原请求重试")
        finally:
            if repo is not None:
                repo.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--db", default="data/liteshop.sqlite")
    parser.add_argument("--shop-name", default="我的门店")
    args = parser.parse_args()
    token = os.environ.get("LITESHOP_TOKEN")
    if args.host not in ("127.0.0.1", "localhost") and not token:
        parser.error("LAN mode requires LITESHOP_TOKEN (at least 24 ASCII characters)")
    token = token or secrets.token_urlsafe(32)
    server = LocalServer((args.host, args.port), args.db, token, args.shop_name)
    print("LiteShop: http://{}:{}".format(args.host, server.server_port), flush=True)
    if not os.environ.get("LITESHOP_TOKEN"):
        print("Terminal access token: " + token, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
