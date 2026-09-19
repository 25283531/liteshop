"""Local use cases: append-only ledger, atomic outbox, request idempotency."""
from hashlib import sha256
import json
import sqlite3
import time
from uuid import uuid4
from .repository import Repository

class BusinessError(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def uid():
    return str(uuid4())


def now():
    return int(time.time() * 1000)


def encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def text(value, name, maximum=200, required=True):
    if not isinstance(value, str) or len(value) > maximum or (required and not value.strip()):
        raise BusinessError("INVALID_INPUT", "Invalid " + name)
    return value.strip()


def integer(value, name, minimum=0, maximum=2_000_000_000):
    if type(value) is not int or not minimum <= value <= maximum:
        raise BusinessError("INVALID_INPUT", "Invalid integer " + name)
    return value


class ShopService:
    def __init__(self, repository: Repository):
        self.repo = repository

    def initialize(self, shop_name, device_name="Local terminal", owner_name="Owner"):
        shop_name = text(shop_name, "shop name")
        with self.repo.atomic():
            existing = self.repo.context()
            if existing:
                return existing
            shop, device, owner, stamp = uid(), uid(), uid(), now()
            self.repo.insert("shop", dict(id=shop, name=shop_name, created_at=stamp))
            self.repo.insert("device", dict(id=device, shop_id=shop, name=text(device_name, "device name"), created_at=stamp))
            self.repo.insert("staff", dict(id=owner, shop_id=shop, name=text(owner_name, "owner name"), role="OWNER", created_at=stamp))
            self.repo.insert("sync_state", dict(device_id=device, updated_at=stamp))
            self.ctx = dict(shop_id=shop, device_id=device, operator_id=owner)
            self.emit("SHOP_INITIALIZED", shop, self.repo.one("shop", shop))
            for name, mode in (("Stored value", "STORED"), ("Visit card", "COUNT")):
                card_type = dict(id=uid(), shop_id=shop, name=name, mode=mode, created_at=stamp)
                self.repo.insert("card_type", card_type)
                self.emit("CARD_TYPE_CREATED", card_type["id"], card_type)
            return self.ctx

    def command(self, request_id, action, payload, execute):
        text(request_id, "request_id", 128)
        fingerprint = sha256(encode(dict(action=action, payload=payload)).encode()).hexdigest()
        try:
            with self.repo.atomic():
                previous = self.repo.receipt(request_id)
                if previous:
                    if previous["fingerprint"] != fingerprint:
                        raise BusinessError("IDEMPOTENCY_CONFLICT", "Request key already used for a different command")
                    return json.loads(previous["result"])
                self.ctx = self.repo.context()
                if not self.ctx:
                    raise BusinessError("NOT_INITIALIZED", "Initialize the shop first")
                result = execute()
                self.repo.insert("command_receipt", dict(request_id=request_id, fingerprint=fingerprint, result=encode(result), created_at=now()))
                return result
        except sqlite3.IntegrityError as exc:
            raise BusinessError("CONSTRAINT_VIOLATION", "Database constraint rejected the operation") from exc

    def emit(self, action, identity, payload):
        event = dict(id=uid(), shop_id=self.ctx["shop_id"], device_id=self.ctx["device_id"],
                     event_type=action, entity_id=identity, payload=encode(payload), created_at=now())
        self.repo.insert("sync_event", event)
        self.repo.insert("operation_log", dict(id=uid(), operator_id=self.ctx["operator_id"],
                         device_id=self.ctx["device_id"], action=action, entity_id=identity,
                         detail=encode({"event_id": event["id"]}), created_at=event["created_at"]))

    def require(self, table, identity):
        row = self.repo.one(table, identity)
        if row is None:
            raise BusinessError("NOT_FOUND", table + " does not exist")
        return row

    def active_member(self, identity):
        member = self.require("member", identity)
        if not member["status"] or member["deleted_at"] is not None:
            raise BusinessError("MEMBER_INACTIVE", "Member is inactive")
        return member

    def create_member(self, request_id, name, phone=None, remark=""):
        payload = dict(name=name, phone=phone, remark=remark)
        def execute():
            stamp, identity = now(), uid()
            row = dict(id=identity, shop_id=self.ctx["shop_id"], member_no="M" + identity.replace("-", ""),
                       name=text(name, "name"), phone=text(phone, "phone", 32) if phone is not None else None,
                       remark=text(remark, "remark", 1000, False), created_at=stamp, updated_at=stamp)
            self.repo.insert("member", row)
            self.repo.insert("points_account", dict(member_id=identity, updated_at=stamp))
            result = self.require("member", identity)
            self.emit("MEMBER_CREATED", identity, result)
            return result
        return self.command(request_id, "CREATE_MEMBER", payload, execute)

    def update_member(self, request_id, member_id, version, **changes):
        def execute():
            member = self.require("member", member_id)
            integer(version, "version", 1)
            if version != member["version"]:
                raise BusinessError("VERSION_CONFLICT", "Member was changed; reload first")
            if member["deleted_at"] is not None:
                raise BusinessError("MEMBER_INACTIVE", "Member is archived")
            if not changes or not set(changes) <= {"name", "phone", "remark", "status"}:
                raise BusinessError("INVALID_INPUT", "Unsupported member changes")
            updates = dict(changes)
            for key in ("name", "phone", "remark"):
                if key in updates:
                    if key == "phone" and updates[key] is None:
                        continue
                    updates[key] = text(updates[key], key, 1000 if key == "remark" else (32 if key == "phone" else 200), key != "remark")
            if "status" in updates:
                integer(updates["status"], "status", 0, 1)
            updates.update(version=version+1, updated_at=now())
            self.repo.update("member", member_id, updates)
            result = self.require("member", member_id)
            self.emit("MEMBER_UPDATED", member_id, result)
            return result
        return self.command(request_id, "UPDATE_MEMBER", dict(member_id=member_id, version=version, changes=changes), execute)

    def open_card(self, request_id, member_id, card_type_id, expire_at=None):
        def execute():
            self.active_member(member_id)
            card_type = self.require("card_type", card_type_id)
            if not card_type["status"]:
                raise BusinessError("CARD_TYPE_INACTIVE", "Card type is inactive")
            if expire_at is not None:
                integer(expire_at, "expire_at", now()+1, 9_000_000_000_000)
            identity, stamp = uid(), now()
            self.repo.insert("member_card", dict(id=identity, member_id=member_id, card_type_id=card_type_id,
                             card_no="C" + identity.replace("-", ""), expire_at=expire_at, created_at=stamp, updated_at=stamp))
            result = self.require("member_card", identity)
            self.emit("CARD_OPENED", identity, result)
            return result
        return self.command(request_id, "OPEN_CARD", dict(member_id=member_id, card_type_id=card_type_id, expire_at=expire_at), execute)

    def set_card_status(self, request_id, card_id, status, version):
        def execute():
            card = self.require("member_card", card_id)
            integer(version, "version", 1)
            if version != card["version"]:
                raise BusinessError("VERSION_CONFLICT", "Card was changed; reload first")
            if status not in ("ACTIVE", "LOST", "DISABLED"):
                raise BusinessError("INVALID_INPUT", "Unknown card status")
            self.repo.update("member_card", card_id, dict(status=status, version=version+1, updated_at=now()))
            result = self.require("member_card", card_id)
            self.emit("CARD_STATUS_CHANGED", card_id, result)
            return result
        return self.command(request_id, "CARD_STATUS", dict(card_id=card_id, status=status, version=version), execute)

    def transact(self, request_id, card_id, kind, amount=0, times=0, source_id=None, remark=""):
        payload = dict(card_id=card_id, kind=kind, amount=amount, times=times, source_id=source_id, remark=remark)
        def execute():
            integer(amount, "amount", -2_000_000_000)
            integer(times, "times", -2_000_000_000)
            note = text(remark, "remark", 1000, kind == "ADJUST")
            card = self.require("member_card", card_id)
            self.active_member(card["member_id"])
            card_type = self.require("card_type", card["card_type_id"])
            if card["status"] != "ACTIVE" or (card["expire_at"] is not None and card["expire_at"] <= now()):
                raise BusinessError("CARD_INACTIVE", "Card is inactive or expired")
            mode = card_type["mode"]
            if (mode == "STORED" and times != 0) or (mode == "COUNT" and amount != 0):
                raise BusinessError("CARD_MODE_MISMATCH", "Money and visits must use the matching card type")
            value = amount if mode == "STORED" else times
            allowed = {"RECHARGE", "CONSUME", "GIFT", "ADJUST", "REFUND"} if mode == "STORED" else {"CREDIT_TIMES", "DEDUCT_TIMES", "ADJUST", "REFUND"}
            if kind not in allowed or value == 0 or (kind != "ADJUST" and value < 0):
                raise BusinessError("INVALID_INPUT", "Invalid operation or quantity")
            if kind == "REFUND":
                source = self.require("ledger_transaction", source_id)
                if source["card_id"] != card_id or source["kind"] not in ("CONSUME", "DEDUCT_TIMES"):
                    raise BusinessError("INVALID_REFUND", "Refund must reference consumption on the same card")
                refunded = self.repo.refunded(source_id)
                if amount > -source["amount"] - refunded["amount"] or times > -source["times"] - refunded["times"]:
                    raise BusinessError("REFUND_EXCEEDED", "Refund exceeds the unrefunded quantity")
            elif source_id is not None:
                raise BusinessError("INVALID_INPUT", "Only refunds may reference a source transaction")
            delta_amount, delta_times = amount, times
            if kind in ("CONSUME", "DEDUCT_TIMES"):
                delta_amount, delta_times = -amount, -times
            balance, remaining = card["balance"] + delta_amount, card["remaining_times"] + delta_times
            if balance < 0 or remaining < 0:
                raise BusinessError("INSUFFICIENT_BALANCE", "Insufficient balance or visits")
            integer(balance, "resulting balance", 0, 9_000_000_000_000)
            integer(remaining, "resulting visits", 0, 9_000_000_000_000)
            transaction = dict(id=uid(), **self.ctx, member_id=card["member_id"], card_id=card_id, kind=kind,
                               amount=delta_amount, times=delta_times, balance_before=card["balance"], balance_after=balance,
                               times_before=card["remaining_times"], times_after=remaining, source_id=source_id, remark=note, created_at=now())
            self.repo.insert("ledger_transaction", transaction)
            self.repo.update("member_card", card_id, dict(balance=balance, remaining_times=remaining, version=card["version"]+1, updated_at=transaction["created_at"]))
            self.emit("CARD_TRANSACTION", transaction["id"], transaction)
            return transaction
        return self.command(request_id, "TRANSACT", payload, execute)

    def change_points(self, request_id, member_id, points, remark):
        def execute():
            self.active_member(member_id)
            integer(points, "points", -2_000_000_000)
            if points == 0:
                raise BusinessError("INVALID_INPUT", "Points change must not be zero")
            note = text(remark, "remark", 1000)
            account = self.require("points_account", member_id)
            balance = account["balance"] + points
            if balance < 0:
                raise BusinessError("INSUFFICIENT_POINTS", "Insufficient points")
            integer(balance, "resulting points", 0, 9_000_000_000_000)
            row = dict(id=uid(), member_id=member_id, points=points, balance_before=account["balance"], balance_after=balance,
                       operator_id=self.ctx["operator_id"], device_id=self.ctx["device_id"], remark=note, created_at=now())
            self.repo.insert("points_transaction", row)
            self.repo.update("points_account", member_id, dict(balance=balance, updated_at=row["created_at"]))
            self.emit("POINTS_CHANGED", row["id"], row)
            return row
        return self.command(request_id, "POINTS", dict(member_id=member_id, points=points, remark=remark), execute)

    def member_detail(self, member_id):
        return dict(member=self.require("member", member_id), cards=self.repo.cards(member_id),
                    points=self.require("points_account", member_id), transactions=self.repo.ledger(member_id))
