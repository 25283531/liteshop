import concurrent.futures
import copy
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from uuid import uuid4

from apps.cloud.server import CloudServer, CloudStore


class CloudTests(unittest.TestCase):
    token = "cloud-terminal-test-token-123456789"
    admin_token = "cloud-admin-test-token-123456789"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "cloud.sqlite"
        self.server = CloudServer(("127.0.0.1", 0), self.db, self.token,
                                  "cloud-internal-reader-test-token-123456", self.admin_token)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.shop, self.device, self.member = (str(uuid4()) for _ in range(3))

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join()
        self.server.store.close()
        self.tmp.cleanup()

    def event(self, sequence=1, kind="MEMBER_CREATED", entity=None, payload=None):
        return dict(event_id=str(uuid4()), shop_id=self.shop, device_id=self.device,
                    sequence=sequence, schema_version=1, event_type=kind,
                    entity_id=entity or self.member,
                    payload=payload if payload is not None else dict(id=self.member, name="测试会员"),
                    created_at=1700000000000 + sequence)

    def request(self, method, path, payload=None, auth=True, extra_headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=10)
        headers = {"Authorization": "Bearer " + self.token} if auth else {}
        headers.update(extra_headers or {})
        body = None
        if payload is not None:
            body = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        try:
            connection.request(method, path, body, headers)
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def upload(self, events):
        return self.request("POST", "/api/v1/terminal/sync/events", {"events": events})

    def test_health_auth_and_idempotent_delivery(self):
        self.assertEqual(self.request("GET", "/healthz", auth=False)[0], 200)
        self.assertEqual(self.request("POST", "/api/v1/terminal/sync/events", {"events": []}, auth=False)[0], 401)
        event = self.event()
        for expected in ("ACCEPTED", "ALREADY_ACCEPTED"):
            status, response = self.upload([event])
            self.assertEqual(status, 200, response)
            self.assertEqual(response["data"]["accepted"], [{"event_id": event["event_id"], "status": expected}])
        self.assertEqual(self.server.store.member(self.member)["name"], "测试会员")

    def test_admin_web_and_settings(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=10)
        try:
            connection.request("GET", "/")
            response = connection.getresponse()
            html = response.read().decode()
            self.assertEqual(response.status, 200)
            self.assertIn("LiteShop", html)
        finally:
            connection.close()

        status, _ = self.request("GET", "/api/v1/admin/summary", auth=False)
        self.assertEqual(status, 401)
        headers = {"Authorization": "Bearer " + self.admin_token}
        status, response = self.request("GET", "/api/v1/admin/summary", extra_headers=headers)
        self.assertEqual(status, 200, response)
        self.assertIn("counts", response["data"])
        status, response = self.request("PUT", "/api/v1/admin/settings", {"display_name": "总部云端"}, extra_headers=headers)
        self.assertEqual(status, 200, response)
        self.assertEqual(response["data"]["display_name"], "总部云端")

    def test_conflicting_batch_rolls_back(self):
        first = self.event()
        self.assertEqual(self.upload([first])[0], 200)
        conflict = copy.deepcopy(first)
        conflict["payload"]["name"] = "不能覆盖"
        next_member = str(uuid4())
        fresh = self.event(2, entity=next_member, payload={"id": next_member, "name": "需回滚"})
        self.assertNotEqual(self.upload([fresh, conflict])[0], 200)
        self.assertIsNone(self.server.store.member(next_member))
        self.assertEqual(self.server.store.member(self.member)["name"], "测试会员")
        status, result = self.upload([fresh])
        self.assertEqual(status, 200, result)
        self.assertEqual(result["data"]["accepted"][0]["status"], "ACCEPTED")

    def test_card_transaction_preserves_card_and_updates_balance(self):
        card_id = str(uuid4())
        card = dict(id=card_id, member_id=self.member, card_no="C0001", mode="STORED",
                    status="ACTIVE", balance=0, remaining_times=0, version=1)
        transaction = dict(id=str(uuid4()), card_id=card_id, member_id=self.member,
                           kind="RECHARGE", amount=1200, times=0,
                           balance_after=1200, times_after=0)
        events = [self.event(), self.event(2, "CARD_OPENED", card_id, card),
                  self.event(3, "CARD_TRANSACTION", transaction["id"], transaction)]
        status, response = self.upload(events)
        self.assertEqual(status, 200, response)
        projected = self.server.store.member(self.member)["cards"][0]
        self.assertEqual(projected["id"], card_id)
        self.assertEqual(projected["status"], "ACTIVE")
        self.assertEqual(projected["balance"], 1200)
        self.assertEqual(projected["remaining_times"], 0)

    def test_parallel_retries_and_restart_preserve_delivery(self):
        event = self.event()
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            results = list(pool.map(lambda _: self.upload([event]), range(5)))
        self.assertTrue(all(status == 200 for status, _ in results), results)
        statuses = [response["data"]["accepted"][0]["status"] for _, response in results]
        self.assertEqual(statuses.count("ACCEPTED"), 1)
        store = CloudStore(self.db)
        try:
            self.assertEqual(store.ingest([event])[0]["status"], "ALREADY_ACCEPTED")
            self.assertEqual(store.member(self.member)["name"], "测试会员")
        finally:
            store.close()

    def test_sequence_gap_rolls_back_entire_batch(self):
        first = self.event()
        gap = self.event(3, "MEMBER_UPDATED", payload={"id": self.member, "name": "乱序"})
        self.assertEqual(self.upload([first, gap])[0], 400)
        self.assertIsNone(self.server.store.member(self.member))
        self.assertEqual(self.upload([first])[0], 200)
        second = self.event(2, "MEMBER_UPDATED", payload={"id": self.member, "name": "更新"})
        self.assertEqual(self.upload([second])[0], 200)
        self.assertEqual(self.upload([first])[0], 200)
        self.assertEqual(self.server.store.member(self.member)["name"], "更新")

    def test_cross_terminal_entity_write_is_rejected(self):
        self.assertEqual(self.upload([self.event()])[0], 200)
        for field in ("shop_id", "device_id"):
            with self.subTest(field=field):
                other = self.event(1, "MEMBER_UPDATED", payload={"id": self.member, "name": "越权覆盖"})
                other[field] = str(uuid4())
                self.assertEqual(self.upload([other])[0], 400)
        self.assertEqual(self.server.store.member(self.member)["name"], "测试会员")

    def test_malformed_json_shapes_and_schema_are_rejected(self):
        for payload in ([], {}, {"events": {}}, {"events": [None]}):
            with self.subTest(payload=payload):
                self.assertEqual(self.request("POST", "/api/v1/terminal/sync/events", payload)[0], 400)
        for field, value in (("sequence", True), ("sequence", 0), ("created_at", "1"),
                             ("schema_version", 2), ("payload", [])):
            with self.subTest(field=field, value=value):
                malformed = self.event()
                malformed[field] = value
                self.assertEqual(self.upload([malformed])[0], 400)
        self.assertIsNone(self.server.store.member(self.member))

    def test_internal_reader_auth_points_and_exact_route(self):
        self.assertEqual(self.request("GET", "/api/v1/miniapp/me", auth=False)[0], 401)
        self.assertEqual(self.request("GET", "/api/v1/miniapp/me")[0], 401)
        points = dict(id=str(uuid4()), member_id=self.member, points=100, balance_after=100)
        self.assertEqual(self.upload([self.event(), self.event(2, "POINTS_CHANGED", points["id"], points)])[0], 200)
        headers = {"Authorization": "Bearer cloud-internal-reader-test-token-123456", "X-LiteShop-Member": self.member}
        status, response = self.request("GET", "/api/v1/miniapp/me", extra_headers=headers)
        self.assertEqual(status, 200)
        self.assertEqual(response["data"]["points"]["balance"], 100)
        self.assertIsInstance(response["last_synced_at"], int)
        self.assertEqual(self.request("GET", "/api/v1/miniapp/me/cards", extra_headers=headers)[0], 404)
        self.assertEqual(self.request("POST", "/api/v1/terminal/sync/events", {"events": []}, extra_headers=headers)[0], 401)

    def test_two_independent_terminals_keep_separate_ledgers(self):
        a = self.event()
        b = self.event(entity=str(uuid4()), payload={"name": "B 门店"})
        b.update(shop_id=str(uuid4()), device_id=str(uuid4()))
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda e: self.upload([e]), [a, b]))
        self.assertTrue(all(status == 200 for status, _ in results), results)
        self.assertEqual(self.server.store.member(a["entity_id"])["name"], "测试会员")
        self.assertEqual(self.server.store.member(b["entity_id"])["name"], "B 门店")

    def test_user_registration_binding_and_shop_isolation(self):
        sent = []
        self.server.mailer.send_registration = lambda email, username: sent.append((email, username))
        status, response = self.request("POST", "/api/v1/auth/register", {"email": "owner@example.com", "username": "owner", "password": "password123"}, auth=False)
        self.assertEqual(status, 201, response)
        self.assertEqual(sent, [("owner@example.com", "owner")])
        status, response = self.request("POST", "/api/v1/auth/login", {"email": "owner@example.com", "password": "password123"}, auth=False)
        self.assertEqual(status, 200, response)
        user_token = response["data"]["token"]
        serial = "ABCDEFGHIJKLMNOP"
        event = self.event(payload={"shop_id": self.shop, "name": "一号店"})
        event["event_type"] = "SHOP_INITIALIZED"
        event["entity_id"] = self.shop
        event["serial_no"] = serial
        self.assertEqual(self.upload([event])[0], 200)
        member_event = self.event(2, payload={"id": self.member, "name": "张三", "member_no": "M001"})
        self.assertEqual(self.upload([member_event])[0], 200)
        auth = {"Authorization": "Bearer " + user_token}
        status, response = self.request("POST", "/api/v1/account/terminals", {"serial_no": serial}, auth=False, extra_headers=auth)
        self.assertEqual(status, 201, response)
        status, response = self.request("GET", "/api/v1/account/members", auth=False, extra_headers=auth)
        self.assertEqual(status, 200, response)
        self.assertEqual([m["member_id"] for m in response["data"]], [self.member])
        status, response = self.request("GET", "/api/v1/account/members?shop_id=other", auth=False, extra_headers=auth)
        self.assertEqual(status, 403, response)

    def test_admin_integration_settings_are_stored_and_secrets_redacted(self):
        headers = {"Authorization": "Bearer " + self.admin_token}
        status, response = self.request("PUT", "/api/v1/admin/settings", {
            "smtp_host": "smtp.example.com", "smtp_port": "465", "smtp_username": "mailer@example.com",
            "smtp_password": "secret", "smtp_from": "mailer@example.com", "smtp_ssl": "1",
            "miniapp_app_id": "wx-app", "miniapp_app_secret": "mini-secret", "wechat_app_id": "gh-app",
            "wechat_app_secret": "wechat-secret", "wechat_token": "verify-token"
        }, auth=False, extra_headers=headers)
        self.assertEqual(status, 200, response)
        self.assertNotIn("mini-secret", json.dumps(response))
        self.assertNotIn("verify-token", json.dumps(response))
        status, response = self.request("GET", "/api/v1/admin/summary", auth=False, extra_headers=headers)
        self.assertEqual(status, 200, response)
        self.assertTrue(response["data"]["settings"]["smtp_password_configured"])
        self.assertTrue(response["data"]["settings"]["wechat_token_configured"])
        self.assertNotIn("mini-secret", json.dumps(response))


if __name__ == "__main__":
    unittest.main()
