import tempfile
import unittest
from pathlib import Path
from liteshop.storage.repository import SQLiteRepository
from liteshop.core.service import ShopService, BusinessError

class CoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = SQLiteRepository(Path(self.tmp.name) / "shop.sqlite")
        self.svc = ShopService(self.repo)
        self.svc.initialize("Demo Shop")
        self.svc.update_settings("req-password", local_password="1234")
        self.member = self.svc.create_member("req-member", "Zhang San", "13800000000")
        self.card_type = next(x for x in self.repo.card_types() if x["mode"] == "STORED")
        self.card = self.svc.open_card("req-card", self.member["id"], self.card_type["id"])

    def tearDown(self):
        self.repo.close()
        self.tmp.cleanup()

    def test_recharge_consume_refund_and_idempotency(self):
        recharge = self.svc.transact("req-recharge", self.card["id"], "RECHARGE", amount=10000, password="1234")
        self.assertEqual(recharge["balance_after"], 10000)
        self.assertEqual(self.svc.transact("req-recharge", self.card["id"], "RECHARGE", amount=10000, password="1234")["id"], recharge["id"])
        consume = self.svc.transact("req-consume", self.card["id"], "CONSUME", amount=3500)
        self.assertEqual(consume["balance_after"], 6500)
        refund = self.svc.transact("req-refund", self.card["id"], "REFUND", amount=1000, source_id=consume["id"])
        self.assertEqual(refund["balance_after"], 7500)
        with self.assertRaises(BusinessError) as ctx:
            self.svc.transact("req-over", self.card["id"], "CONSUME", amount=100000)
        self.assertEqual(ctx.exception.code, "INSUFFICIENT_BALANCE")
        self.assertTrue(self.repo.audit()["ok"])

    def test_points_and_atomic_rollback(self):
        points = self.svc.change_points("req-points", self.member["id"], 120, "消费奖励", "1234")
        self.assertEqual(points["balance_after"], 120)
        with self.assertRaises(BusinessError):
            self.svc.change_points("req-points-bad", self.member["id"], -121, "兑换")
        self.assertEqual(self.repo.one("points_account", self.member["id"])["balance"], 120)
        with self.assertRaises(BusinessError):
            self.svc.transact("req-bad", self.card["id"], "CONSUME", amount=10, times=1)
        self.assertEqual(len(self.repo.ledger(self.member["id"])), 0)

if __name__ == "__main__":
    unittest.main()
