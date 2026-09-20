import concurrent.futures
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from uuid import uuid4

from apps.web.server import LocalServer
from liteshop.storage.repository import SQLiteRepository


class WebTests(unittest.TestCase):
    token = "test-local-terminal-token-12345678"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "shop.sqlite"
        self.start()

    def start(self):
        self.server = LocalServer(("127.0.0.1", 0), self.db, self.token, "测试门店")
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def tearDown(self):
        self.stop()
        self.tmp.cleanup()

    def request(self, method, path, data=None, auth=True, extra=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=10)
        headers = {"Authorization": "Bearer " + self.token} if auth else {}
        body = None
        if data is not None:
            body = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        headers.update(extra or {})
        try:
            conn.request(method, path, body, headers)
            response = conn.getresponse()
            raw = response.read()
            return response.status, json.loads(raw) if "application/json" in response.getheader("Content-Type", "") else raw
        finally:
            conn.close()

    def get(self, path):
        status, response = self.request("GET", "/api/v1/" + path)
        self.assertEqual(status, 200, response)
        return response["data"]

    def command(self, action, **payload):
        payload.setdefault("request_id", str(uuid4()))
        status, response = self.request("POST", "/api/v1/commands/" + action, payload)
        self.assertEqual(status, 200, response)
        return response["data"]

    def member_card(self, mode="STORED"):
        member = self.command("create-member", name="张三", phone="13800000000")
        card_type = next(x for x in self.get("card-types") if x["mode"] == mode)
        card = self.command("open-card", member_id=member["id"], card_type_id=card_type["id"])
        return member, card

    def test_stored_workflow_idempotency_and_restart(self):
        member, card = self.member_card()
        payload = dict(request_id="repeat-recharge", card_id=card["id"], kind="RECHARGE", amount=10000)
        tx = self.command("transact", **payload)
        self.assertEqual(self.command("transact", **payload), tx)
        spend = self.command("transact", card_id=card["id"], kind="CONSUME", amount=2500)
        self.command("transact", card_id=card["id"], kind="REFUND", amount=500, source_id=spend["id"])
        self.command("points", member_id=member["id"], points=25, remark="消费奖励")
        self.stop()
        self.start()
        detail = self.get("members/" + member["id"])
        self.assertEqual(detail["cards"][0]["balance"], 8000)
        self.assertEqual(len(detail["transactions"]), 3)
        self.assertEqual(detail["points"]["balance"], 25)
        self.assertEqual(len(detail["points_transactions"]), 1)
        self.assertEqual(self.command("transact", **payload)["id"], tx["id"])
        self.assertEqual(self.get("status")["member_count"], 1)
        repo = SQLiteRepository(self.db)
        try:
            self.assertTrue(repo.audit()["ok"])
        finally:
            repo.close()

    def test_count_card_partial_refund_and_rollback(self):
        member, card = self.member_card("COUNT")
        self.command("transact", card_id=card["id"], kind="CREDIT_TIMES", times=10)
        tx = self.command("transact", card_id=card["id"], kind="DEDUCT_TIMES", times=3)
        self.command("transact", card_id=card["id"], kind="REFUND", times=2, source_id=tx["id"])
        before = self.get("status")["pending_events"]
        status, result = self.request("POST", "/api/v1/commands/transact", dict(
            request_id="over-refund", card_id=card["id"], kind="REFUND", times=2, source_id=tx["id"]))
        self.assertEqual(status, 400)
        self.assertEqual(result["error"]["code"], "REFUND_EXCEEDED")
        self.assertEqual(self.get("status")["pending_events"], before)
        self.assertEqual(self.get("members/" + member["id"])["cards"][0]["remaining_times"], 9)

    def test_auth_origin_static_and_miniapp_boundary(self):
        self.assertEqual(self.request("GET", "/api/v1/members", auth=False)[0], 401)
        self.assertEqual(self.request("GET", "/api/v1/status", extra={"Origin": "https://evil.example"})[0], 403)
        self.assertEqual(self.request("POST", "/api/v1/commands/create-member", dict(request_id="x", name="x"), auth=False)[0], 401)
        self.assertEqual(self.request("GET", "/../../LICENSE")[0], 404)
        self.assertEqual(self.request("GET", "/api/miniapp/v1/me")[0], 404)
        status, html = self.request("GET", "/", auth=False)
        self.assertEqual(status, 200)
        self.assertIn(b"/app.js", html)
        self.assertNotIn(self.token.encode(), html)
        self.assertEqual(self.request("GET", "/app.css", auth=False)[0], 200)

    def test_bad_input_and_conflicting_key(self):
        for payload in ([], {}, {"request_id": "bad", "name": 123}, {"request_id": [], "name": "name"}):
            self.assertEqual(self.request("POST", "/api/v1/commands/create-member", payload)[0], 400)
        member, card = self.member_card()
        for amount in (1.5, True, "100"):
            status, _ = self.request("POST", "/api/v1/commands/transact",
                dict(request_id="bad", card_id=card["id"], kind="RECHARGE", amount=amount))
            self.assertEqual(status, 400)
        self.command("transact", request_id="same", card_id=card["id"], kind="RECHARGE", amount=100)
        self.assertEqual(self.request("POST", "/api/v1/commands/transact",
            dict(request_id="same", card_id=card["id"], kind="RECHARGE", amount=200))[0], 409)
        self.assertEqual(self.request("GET", "/api/v1/members?offset=-1")[0], 400)
        self.assertEqual(self.request("GET", "/api/v1/members/not-found")[0], 404)

    def test_parallel_retries_write_once(self):
        member, card = self.member_card()
        payload = dict(request_id="parallel", card_id=card["id"], kind="RECHARGE", amount=500)
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            results = list(pool.map(lambda _: self.request("POST", "/api/v1/commands/transact", payload), range(5)))
        self.assertTrue(all(status == 200 for status, _ in results))
        self.assertEqual(len({r["data"]["id"] for _, r in results}), 1)
        detail = self.get("members/" + member["id"])
        self.assertEqual(len(detail["transactions"]), 1)
        self.assertEqual(detail["cards"][0]["balance"], 500)

    def test_member_and_card_status_version(self):
        member, card = self.member_card()
        self.command("update-member", member_id=member["id"], version=1, name="李四")
        self.assertEqual(self.request("POST", "/api/v1/commands/update-member",
            dict(request_id="stale", member_id=member["id"], version=1, name="张三"))[0], 409)
        self.command("card-status", card_id=card["id"], status="LOST", version=1)
        code, result = self.request("POST", "/api/v1/commands/transact",
            dict(request_id="lost", card_id=card["id"], kind="RECHARGE", amount=100))
        self.assertEqual(code, 400)
        self.assertEqual(result["error"]["code"], "CARD_INACTIVE")
        self.assertEqual(self.get("members?q=%E6%9D%8E%E5%9B%9B")[0]["name"], "李四")

    def test_settings_endpoint_is_available(self):
        settings = self.get("settings")
        self.assertEqual(settings["name"], "测试门店")
        self.assertFalse(settings["has_local_password"])
        updated = self.command("update-settings", name="新门店", settings={"card_types": [{"name": "积分会员", "mode": "STORED"}]}, local_password="1234")
        self.assertEqual(updated["name"], "新门店")
        self.assertTrue(self.get("settings")["has_local_password"])
        categories = {item["category_code"]: item for item in self.get("card-types")}
        self.assertIn("COUNT", categories)
        self.assertIn("RECHARGE_GIFT", categories)
        self.assertEqual(categories["POINTS"]["name"], "积分会员")
        self.assertEqual(categories["RECHARGE_GIFT"]["status"], 0)


if __name__ == "__main__":
    unittest.main()
