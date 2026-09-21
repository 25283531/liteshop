package com.liteshop.terminal;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Device-private, offline ledger. Every write, receipt and outbox event commits together. */
public final class LocalStore extends SQLiteOpenHelper {
    private static final long MAX_BALANCE = 9000000000000L;
    private final Context context;
    private volatile String cloudState = "NOT_CONFIGURED";
    private static final char[] SERIAL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    void setCloudState(String value) { cloudState = value; }

    public static final class Response {
        public final int code;
        public final String body;
        Response(int code, JSONObject body) { this.code = code; this.body = body.toString(); }
    }

    private static final class Rejected extends RuntimeException {
        final String code;
        Rejected(String code, String message) { super(message); this.code = code; }
    }

    public LocalStore(Context context) { this(context, "liteshop.db"); }
    // Package-private database name allows isolated instrumentation tests.
    LocalStore(Context context, String name) {
        super(context, name, null, 3);
        this.context = context.getApplicationContext();
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
        db.execSQL("PRAGMA synchronous=FULL");
    }

    @Override public void onCreate(SQLiteDatabase db) {
        // This is the same schema used by the Python reference implementation.
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("001_initial.sql"), "UTF-8"));
            try {
                String line;
                StringBuilder statement = new StringBuilder();
                boolean trigger = false;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().startsWith("CREATE TRIGGER")) { trigger = true; }
                    statement.append(line).append('\n');
                    if ((!trigger && line.trim().endsWith(";")) || (trigger && line.trim().equals("END;"))) {
                        db.execSQL(statement.toString());
                        statement.setLength(0); trigger = false;
                    }
                }
                if (statement.toString().trim().length() != 0) { throw new SQLiteException("Incomplete schema"); }
            } finally { reader.close(); }
            db.execSQL("ALTER TABLE member ADD COLUMN inviter_name TEXT");
            db.execSQL("ALTER TABLE member ADD COLUMN inviter_member_id TEXT");
            db.execSQL("ALTER TABLE shop ADD COLUMN settings_json TEXT NOT NULL DEFAULT '{}'");
            db.execSQL("ALTER TABLE shop ADD COLUMN local_password_hash TEXT");
            db.execSQL("ALTER TABLE card_type ADD COLUMN gift_percent INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE card_type ADD COLUMN category_code TEXT NOT NULL DEFAULT 'CUSTOM'");
            db.execSQL("ALTER TABLE card_type ADD COLUMN discount_percent INTEGER NOT NULL DEFAULT 100");
            db.execSQL("ALTER TABLE card_type ADD COLUMN points_rate INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE card_type ADD COLUMN config_json TEXT NOT NULL DEFAULT '{}'");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_member_inviter ON member(inviter_member_id)");
            db.execSQL("ALTER TABLE device ADD COLUMN serial_no TEXT");
            long stamp = System.currentTimeMillis();
            String shop = uid(), device = uid(), owner = uid();
            insert(db, "shop", object("id", shop, "name", "我的门店", "created_at", stamp));
            insert(db, "device", object("id", device, "shop_id", shop, "name", "安卓机顶盒", "serial_no", newSerial(), "created_at", stamp));
            insert(db, "staff", object("id", owner, "shop_id", shop, "name", "店主", "role", "OWNER", "created_at", stamp));
            insert(db, "sync_state", object("device_id", device, "updated_at", stamp));
            JSONObject ctx = object("shop_id", shop, "device_id", device, "operator_id", owner);
            emit(db, ctx, "SHOP_INITIALIZED", shop, require(db, "shop", shop));
            for (String mode : new String[] {"STORED", "COUNT"}) {
                String id = uid();
                insert(db, "card_type", object("id", id, "shop_id", shop, "category_code", mode, "name",
                    mode.equals("STORED") ? "储值会员" : "计次会员", "mode", mode, "status", 1, "created_at", stamp));
                emit(db, ctx, "CARD_TYPE_CREATED", id, require(db, "card_type", id));
            }
            String[] presetCodes = new String[] {"POINTS", "RECHARGE_GIFT", "DISCOUNT"};
            String[] presetNames = new String[] {"积分会员", "充值赠费", "折扣会员"};
            String[] descriptions = new String[] {"消费后累计积分", "充值按比例赠送余额", "消费按折扣比例结算"};
            for (int i = 0; i < presetCodes.length; i++) {
                String id = uid();
                insert(db, "card_type", object("id", id, "shop_id", shop, "category_code", presetCodes[i], "name", presetNames[i], "mode", "STORED", "status", 0,
                    "gift_percent", presetCodes[i].equals("RECHARGE_GIFT") ? 10 : 0, "discount_percent", presetCodes[i].equals("DISCOUNT") ? 95 : 100,
                    "points_rate", presetCodes[i].equals("POINTS") ? 1 : 0, "config_json", object("description", descriptions[i]).toString(), "created_at", stamp));
                emit(db, ctx, "CARD_TYPE_CREATED", id, require(db, "card_type", id));
            }
        } catch (Exception e) { throw new SQLiteException("Cannot initialize local ledger", e); }
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE member ADD COLUMN inviter_name TEXT");
            db.execSQL("ALTER TABLE member ADD COLUMN inviter_member_id TEXT");
            db.execSQL("ALTER TABLE shop ADD COLUMN settings_json TEXT NOT NULL DEFAULT '{}'");
            db.execSQL("ALTER TABLE shop ADD COLUMN local_password_hash TEXT");
            db.execSQL("ALTER TABLE card_type ADD COLUMN gift_percent INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE card_type ADD COLUMN category_code TEXT NOT NULL DEFAULT 'CUSTOM'");
            db.execSQL("ALTER TABLE card_type ADD COLUMN discount_percent INTEGER NOT NULL DEFAULT 100");
            db.execSQL("ALTER TABLE card_type ADD COLUMN points_rate INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE card_type ADD COLUMN config_json TEXT NOT NULL DEFAULT '{}'");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_member_inviter ON member(inviter_member_id)");
            Cursor shops = db.rawQuery("SELECT id FROM shop LIMIT 1", null);
            try {
                if (shops.moveToFirst()) {
                    String shopId = shops.getString(0); long stamp = System.currentTimeMillis();
                    String[] codes = new String[] {"POINTS", "RECHARGE_GIFT", "DISCOUNT"};
                    String[] names = new String[] {"积分会员", "充值赠费", "折扣会员"};
                    for (int i = 0; i < codes.length; i++) {
                        Cursor existing = db.rawQuery("SELECT id FROM card_type WHERE shop_id=? AND category_code=?", new String[] {shopId, codes[i]});
                        boolean found = existing.moveToFirst(); existing.close();
                        if (!found) { try { insert(db, "card_type", object("id", uid(), "shop_id", shopId, "category_code", codes[i], "name", names[i], "mode", "STORED", "status", 0, "created_at", stamp)); } catch (Exception error) { throw new SQLiteException("Cannot seed card type", error); } }
                    }
                }
            } finally { shops.close(); }
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE device ADD COLUMN serial_no TEXT");
            Cursor devices = db.rawQuery("SELECT id FROM device WHERE serial_no IS NULL", null);
            try { while (devices.moveToNext()) { ContentValues values = new ContentValues(); values.put("serial_no", newSerial()); db.update("device", values, "id=?", new String[]{devices.getString(0)}); } }
            finally { devices.close(); }
        }
    }

    private static String newSerial() {
        SecureRandom random = new SecureRandom(); StringBuilder value = new StringBuilder(16);
        for (int i = 0; i < 16; i++) { value.append(SERIAL_ALPHABET[random.nextInt(SERIAL_ALPHABET.length)]); }
        return value.toString();
    }

    public synchronized Response handle(String method, String path, String body) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            Object result;
            db.beginTransaction();
            try {
                if ("GET".equals(method)) { result = read(db, path); }
                else if ("POST".equals(method) && path.startsWith("commands/")) {
                    if (body == null || body.length() > 16384) { reject("INVALID_INPUT", "请求内容过长"); }
                    result = command(db, path.substring(9), new JSONObject(body));
                } else { throw new Rejected("NOT_FOUND", "接口不存在"); }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            return new Response(200, object("ok", true, "data", result));
        } catch (Rejected e) {
            return error(e.code.equals("NOT_FOUND") ? 404 :
                (e.code.equals("VERSION_CONFLICT") || e.code.equals("IDEMPOTENCY_CONFLICT") ? 409 : 400),
                e.code, e.getMessage(), true);
        } catch (SQLiteConstraintException e) {
            return error(400, "CONSTRAINT_VIOLATION", "数据约束拒绝了操作，未保存", true);
        } catch (JSONException e) {
            return error(400, "INVALID_INPUT", "请求参数格式错误", true);
        } catch (Exception e) {
            // Commit or disk failure can have an uncertain outcome. Keep the original request ID.
            return error(503, "DATABASE_UNAVAILABLE", "设备本地数据库不可用，请重试原请求", false);
        }
    }

    /** Returns a bounded outbox batch for the cloud sync worker. */
    public synchronized JSONArray pendingEvents(int limit) throws JSONException {
        JSONArray result = rows(getReadableDatabase(), "SELECT sequence,id,shop_id,device_id,event_type,entity_id,payload,schema_version,created_at FROM sync_event WHERE status IN ('PENDING','FAILED') ORDER BY sequence LIMIT ?", String.valueOf(limit));
        Cursor serialCursor = getReadableDatabase().rawQuery("SELECT serial_no FROM device LIMIT 1", null);
        String serial = "";
        try { if (serialCursor.moveToFirst() && !serialCursor.isNull(0)) { serial = serialCursor.getString(0); } }
        finally { serialCursor.close(); }
        for (int i = 0; i < result.length(); i++) { result.getJSONObject(i).put("serial_no", serial); }
        return result;
    }

    public synchronized void markEventsSynced(JSONArray ids) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < ids.length(); i++) {
                db.execSQL("UPDATE sync_event SET status='SYNCED',uploaded_at=? WHERE id=?", new Object[] {System.currentTimeMillis(), ids.getString(i)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private Object read(SQLiteDatabase db, String path) throws Exception {
        if (path.equals("status")) {
            JSONObject ctx = context(db);
            return object("shop", require(db, "shop", ctx.getString("shop_id")),
                "device", require(db, "device", ctx.getString("device_id")),
                "member_count", scalar(db, "SELECT COUNT(*) AS value FROM member WHERE deleted_at IS NULL"),
                "pending_events", scalar(db, "SELECT COUNT(*) AS value FROM sync_event WHERE status != 'SYNCED'"),
                "cloud_sync", cloudState, "storage", "ANDROID_SQLITE");
        }
        if (path.equals("card-types")) { return rows(db, "SELECT * FROM card_type ORDER BY mode"); }
        if (path.equals("settings")) {
            JSONObject shop = require(db, "shop", context(db).getString("shop_id"));
            JSONObject device = require(db, "device", context(db).getString("device_id"));
            JSONObject settings = new JSONObject();
            if (!shop.isNull("settings_json")) {
                try { settings = new JSONObject(shop.getString("settings_json")); } catch (JSONException ignored) { }
            }
            return object("id", shop.getString("id"), "name", shop.getString("name"), "device_id", device.getString("id"), "has_local_password", !shop.isNull("local_password_hash"), "serial_no", device.getString("serial_no"), "settings", settings);
        }
        if (path.equals("members") || path.startsWith("members?")) {
            String query = "", offset = "0";
            if (path.startsWith("members?")) {
                for (String pair : path.substring(8).split("&")) {
                    String[] part = pair.split("=", 2);
                    if (part.length != 2) { reject("INVALID_INPUT", "查询参数错误"); }
                    String value;
                    try { value = URLDecoder.decode(part[1], "UTF-8"); }
                    catch (IllegalArgumentException e) { throw new Rejected("INVALID_INPUT", "查询编码错误"); }
                    if (part[0].equals("q")) { query = value; }
                    else if (part[0].equals("offset")) { offset = value; }
                    else { reject("INVALID_INPUT", "查询参数错误"); }
                }
            }
            if (query.length() > 200 || !offset.matches("[0-9]{1,10}") || Long.parseLong(offset) > 2000000000L) {
                reject("INVALID_INPUT", "查询参数超出范围");
            }
            // API 19 ships SQLite 3.7.11, before instr() was introduced.
            String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            return rows(db, "SELECT m.*, (SELECT COUNT(*) FROM member i WHERE i.inviter_member_id=m.id AND i.deleted_at IS NULL) AS invited_count FROM member m WHERE m.deleted_at IS NULL AND (m.name LIKE ? ESCAPE '\\' OR "
                + "COALESCE(phone,'') LIKE ? ESCAPE '\\' OR member_no LIKE ? ESCAPE '\\') ORDER BY created_at,id LIMIT 50 OFFSET ?",
                pattern, pattern, pattern, offset);
        }
        if (path.startsWith("members/")) {
            String id = path.substring(8);
            JSONObject member = require(db, "member", id);
            member.put("invited_count", scalar(db, "SELECT COUNT(*) AS value FROM member WHERE inviter_member_id=? AND deleted_at IS NULL", id));
            return object("member", member,
                "cards", rows(db, "SELECT c.*,t.name AS type_name,t.mode FROM member_card c JOIN card_type t "
                    + "ON c.card_type_id=t.id WHERE member_id=? ORDER BY c.created_at,c.id", id),
                "points", require(db, "points_account", id),
                "transactions", rows(db, "SELECT * FROM ledger_transaction WHERE member_id=? ORDER BY rowid DESC LIMIT 50", id),
                "points_transactions", rows(db, "SELECT * FROM points_transaction WHERE member_id=? ORDER BY rowid DESC LIMIT 50", id));
        }
        throw new Rejected("NOT_FOUND", "接口不存在；微信小程序通过云端访问");
    }

    private JSONObject command(SQLiteDatabase db, String action, JSONObject input) throws Exception {
        validateFields(action, input);
        String request = string(input, "request_id", 128, true);
        JSONObject payload = new JSONObject(input.toString());
        payload.remove("request_id");
        String fingerprint = fingerprint(object("action", action, "payload", payload));
        JSONArray receipts = rows(db, "SELECT * FROM command_receipt WHERE request_id=?", request);
        if (receipts.length() != 0) {
            JSONObject receipt = receipts.getJSONObject(0);
            if (!receipt.getString("fingerprint").equals(fingerprint)) {
                reject("IDEMPOTENCY_CONFLICT", "请求编号已用于其他操作");
            }
            return new JSONObject(receipt.getString("result"));
        }
        JSONObject ctx = context(db), result;
        long stamp = System.currentTimeMillis();
        if (action.equals("update-settings")) {
            JSONObject shop = require(db, "shop", ctx.getString("shop_id"));
            ContentValues values = new ContentValues();
            if (input.has("name")) { values.put("name", string(input, "name", 200, true)); }
            if (input.has("settings") && !input.isNull("settings")) { values.put("settings_json", input.getJSONObject("settings").toString()); }
            if (input.has("local_password") && !input.isNull("local_password")) {
                String password = input.getString("local_password");
                if (password.length() == 0) { values.putNull("local_password_hash"); }
                else {
                    if (password.length() < 4 || password.length() > 128) { reject("INVALID_INPUT", "本地密码长度必须为 4-128 位"); }
                    values.put("local_password_hash", hashPassword(password));
                }
            }
            // null means the settings form left the existing password unchanged;
            // an empty string explicitly clears it.
            if (values.size() == 0) { reject("INVALID_INPUT", "没有要更新的设置"); }
            db.update("shop", values, "id=?", new String[] {ctx.getString("shop_id")});
            result = require(db, "shop", ctx.getString("shop_id"));
            emit(db, ctx, "SHOP_SETTINGS_UPDATED", ctx.getString("shop_id"), result);
        } else if (action.equals("create-member")) {
            String id = uid();
            insert(db, "member", object("id", id, "shop_id", ctx.getString("shop_id"),
                "member_no", "M" + id.replace("-", ""), "name", string(input, "name", 200, true),
                "phone", phone(input), "inviter_name", optionalNullableText(input, "inviter_name", 200), "inviter_member_id", optionalNullableText(input, "inviter_member_id", 128), "remark", optionalText(input, "remark", 1000), "created_at", stamp, "updated_at", stamp));
            insert(db, "points_account", object("member_id", id, "updated_at", stamp));
            result = require(db, "member", id);
            emit(db, ctx, "MEMBER_CREATED", id, result);
        } else if (action.equals("update-member")) {
            String id = string(input, "member_id", 128, true);
            JSONObject member = require(db, "member", id);
            long version = integer(input, "version", 1, 2000000000L);
            if (version != member.getLong("version")) { reject("VERSION_CONFLICT", "会员资料已变化，请刷新"); }
            if (!member.isNull("deleted_at")) { reject("MEMBER_INACTIVE", "会员已归档"); }
            JSONObject updates = new JSONObject();
            if (input.has("name")) { updates.put("name", string(input, "name", 200, true)); }
            if (input.has("phone")) { updates.put("phone", phone(input)); }
            if (input.has("remark")) { updates.put("remark", string(input, "remark", 1000, false)); }
            if (input.has("inviter_name")) { updates.put("inviter_name", optionalNullableText(input, "inviter_name", 200)); }
            if (input.has("inviter_member_id")) { updates.put("inviter_member_id", optionalNullableText(input, "inviter_member_id", 128)); }
            if (input.has("status")) { verifyLocalPassword(db, input.optString("password", null)); updates.put("status", integer(input, "status", 0, 1)); }
            if (updates.length() == 0) { reject("INVALID_INPUT", "没有要更新的资料"); }
            updates.put("version", version + 1).put("updated_at", stamp);
            update(db, "member", id, updates);
            result = require(db, "member", id);
            emit(db, ctx, "MEMBER_UPDATED", id, result);
        } else if (action.equals("open-card")) {
            String member = string(input, "member_id", 128, true), type = string(input, "card_type_id", 128, true);
            activeMember(db, member);
            if (require(db, "card_type", type).getInt("status") != 1) { reject("CARD_TYPE_INACTIVE", "卡类型已停用"); }
            Object expiry = input.isNull("expire_at") ? JSONObject.NULL : integer(input, "expire_at", stamp + 1, MAX_BALANCE);
            String id = uid();
            insert(db, "member_card", object("id", id, "member_id", member, "card_type_id", type,
                "card_no", "C" + id.replace("-", ""), "expire_at", expiry, "created_at", stamp, "updated_at", stamp));
            result = require(db, "member_card", id);
            emit(db, ctx, "CARD_OPENED", id, result);
        } else if (action.equals("card-status")) {
            String id = string(input, "card_id", 128, true), status = string(input, "status", 16, true);
            JSONObject card = require(db, "member_card", id);
            long version = integer(input, "version", 1, 2000000000L);
            if (version != card.getLong("version")) { reject("VERSION_CONFLICT", "卡资料已变化，请刷新"); }
            if (!oneOf(status, "ACTIVE", "LOST", "DISABLED")) { reject("INVALID_INPUT", "卡状态错误"); }
            update(db, "member_card", id, object("status", status, "version", version + 1, "updated_at", stamp));
            result = require(db, "member_card", id);
            emit(db, ctx, "CARD_STATUS_CHANGED", id, result);
        } else if (action.equals("transact")) {
            result = transact(db, ctx, input, stamp);
        } else if (action.equals("points")) {
            String id = string(input, "member_id", 128, true);
            activeMember(db, id);
            verifyLocalPassword(db, input.optString("password", null));
            long points = integer(input, "points", -2000000000L, 2000000000L);
            if (points == 0) { reject("INVALID_INPUT", "积分变动不能为零"); }
            String note = string(input, "remark", 1000, true);
            long before = require(db, "points_account", id).getLong("balance"), after = before + points;
            if (after < 0) { reject("INSUFFICIENT_POINTS", "积分不足"); }
            if (after > MAX_BALANCE) { reject("INVALID_INPUT", "积分超出上限"); }
            result = object("id", uid(), "member_id", id, "points", points, "balance_before", before,
                "balance_after", after, "operator_id", ctx.getString("operator_id"), "device_id", ctx.getString("device_id"),
                "remark", note, "created_at", stamp);
            insert(db, "points_transaction", result);
            update(db, "points_account", id, object("balance", after, "updated_at", stamp));
            emit(db, ctx, "POINTS_CHANGED", result.getString("id"), result);
        } else { throw new Rejected("NOT_FOUND", "操作不存在"); }
        insert(db, "command_receipt", object("request_id", request, "fingerprint", fingerprint, "result", result.toString(), "created_at", stamp));
        return result;
    }

    private JSONObject transact(SQLiteDatabase db, JSONObject ctx, JSONObject input, long stamp) throws Exception {
        String id = string(input, "card_id", 128, true), kind = string(input, "kind", 32, true);
        long amount = input.has("amount") ? integer(input, "amount", -2000000000L, 2000000000L) : 0;
        long times = input.has("times") ? integer(input, "times", -2000000000L, 2000000000L) : 0;
        String note = optionalText(input, "remark", 1000);
        if (kind.equals("ADJUST") && note.length() == 0) { reject("INVALID_INPUT", "调整必须填写原因"); }
        JSONObject card = require(db, "member_card", id);
        activeMember(db, card.getString("member_id"));
        if (!card.getString("status").equals("ACTIVE") || (!card.isNull("expire_at") && card.getLong("expire_at") <= stamp)) {
            reject("CARD_INACTIVE", "卡已停用、挂失或过期");
        }
        boolean stored = require(db, "card_type", card.getString("card_type_id")).getString("mode").equals("STORED");
        if ((stored && times != 0) || (!stored && amount != 0)) { reject("CARD_MODE_MISMATCH", "金额和次数与卡类型不匹配"); }
        long value = stored ? amount : times;
        boolean valid = stored ? oneOf(kind, "RECHARGE", "CONSUME", "GIFT", "ADJUST", "REFUND")
            : oneOf(kind, "CREDIT_TIMES", "DEDUCT_TIMES", "ADJUST", "REFUND");
        if (!valid || value == 0 || (!kind.equals("ADJUST") && value < 0)) { reject("INVALID_INPUT", "交易类型或数量错误"); }
        Object sourceId = JSONObject.NULL;
            if (kind.equals("REFUND")) {
            String source = string(input, "source_id", 128, true);
            sourceId = source;
            JSONObject original = require(db, "ledger_transaction", source);
            if (!original.getString("card_id").equals(id) || !oneOf(original.getString("kind"), "CONSUME", "DEDUCT_TIMES")) {
                reject("INVALID_REFUND", "退款必须关联同一卡的消费流水");
            }
            JSONObject refunded = rows(db, "SELECT COALESCE(SUM(amount),0) AS amount, COALESCE(SUM(times),0) AS times "
                + "FROM ledger_transaction WHERE source_id=? AND kind='REFUND'", source).getJSONObject(0);
            if (amount > -original.getLong("amount") - refunded.getLong("amount")
                || times > -original.getLong("times") - refunded.getLong("times")) { reject("REFUND_EXCEEDED", "超过剩余可退数量"); }
        } else if (!input.isNull("source_id")) { reject("INVALID_INPUT", "只有退款可以关联原流水"); }
        if (kind.equals("RECHARGE") || kind.equals("CREDIT_TIMES")) { verifyLocalPassword(db, input.optString("password", null)); }
        if (oneOf(kind, "CONSUME", "DEDUCT_TIMES")) { amount = -amount; times = -times; }
        long before = card.getLong("balance"), visits = card.getLong("remaining_times");
        long after = before + amount, remaining = visits + times;
        if (after < 0 || remaining < 0) { reject("INSUFFICIENT_BALANCE", "余额或次数不足"); }
        if (after > MAX_BALANCE || remaining > MAX_BALANCE) { reject("INVALID_INPUT", "余额或次数超出上限"); }
        JSONObject tx = object("id", uid(), "shop_id", ctx.getString("shop_id"), "operator_id", ctx.getString("operator_id"),
            "device_id", ctx.getString("device_id"), "member_id", card.getString("member_id"), "card_id", id, "kind", kind,
            "amount", amount, "times", times, "balance_before", before, "balance_after", after,
            "times_before", visits, "times_after", remaining, "source_id", sourceId, "remark", note, "created_at", stamp);
        insert(db, "ledger_transaction", tx);
        update(db, "member_card", id, object("balance", after, "remaining_times", remaining,
            "version", card.getLong("version") + 1, "updated_at", stamp));
        emit(db, ctx, "CARD_TRANSACTION", tx.getString("id"), tx);
        return tx;
    }

    private static void validateFields(String action, JSONObject input) {
        String fields;
        if (action.equals("update-settings")) { fields = "name settings local_password"; }
        else if (action.equals("create-member")) { fields = "name phone inviter_name inviter_member_id remark"; }
        else if (action.equals("update-member")) { fields = "member_id version name phone inviter_name inviter_member_id remark status password"; }
        else if (action.equals("open-card")) { fields = "member_id card_type_id expire_at"; }
        else if (action.equals("card-status")) { fields = "card_id version status"; }
        else if (action.equals("transact")) { fields = "card_id kind amount times source_id remark password"; }
        else if (action.equals("points")) { fields = "member_id points remark password"; }
        else { throw new Rejected("NOT_FOUND", "操作不存在"); }
        fields = " request_id " + fields + " ";
        Iterator<String> keys = input.keys();
        while (keys.hasNext()) { if (!fields.contains(" " + keys.next() + " ")) { reject("INVALID_INPUT", "包含未知参数"); } }
    }

    private static JSONObject context(SQLiteDatabase db) throws JSONException {
        return rows(db, "SELECT s.id AS shop_id,d.id AS device_id,f.id AS operator_id FROM shop s "
            + "JOIN device d ON d.shop_id=s.id JOIN staff f ON f.shop_id=s.id AND f.role='OWNER' LIMIT 1").getJSONObject(0);
    }

    private static void activeMember(SQLiteDatabase db, String id) throws JSONException {
        JSONObject member = require(db, "member", id);
        if (member.getInt("status") != 1 || !member.isNull("deleted_at")) { reject("MEMBER_INACTIVE", "会员已停用"); }
    }

    private static void verifyLocalPassword(SQLiteDatabase db, String password) throws JSONException {
        JSONObject shop = rows(db, "SELECT local_password_hash FROM shop LIMIT 1").getJSONObject(0);
        String stored = shop.isNull("local_password_hash") ? null : shop.getString("local_password_hash");
        if (stored == null || stored.length() == 0) { return; }
        try {
            String[] parts = stored.split("\\$", -1);
            if (parts.length != 4) { reject("PASSWORD_INVALID", "本地设置密码错误"); }
            int rounds = Integer.parseInt(parts[1]);
            byte[] salt = hex(parts[2]), expected = hex(parts[3]);
            byte[] actual = pbkdf2(password == null ? "" : password, salt, rounds);
            if (!MessageDigest.isEqual(actual, expected)) { reject("PASSWORD_INVALID", "本地设置密码错误"); }
        } catch (Rejected e) { throw e; }
        catch (Exception e) { reject("PASSWORD_INVALID", "本地设置密码错误"); }
    }

    public synchronized boolean checkLocalPassword(String password) {
        try {
            verifyLocalPassword(getReadableDatabase(), password);
            return true;
        } catch (Exception e) { return false; }
    }

    private static byte[] hex(String value) {
        if ((value.length() & 1) != 0) { throw new IllegalArgumentException(); }
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) { out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16); }
        return out;
    }

    private static byte[] pbkdf2(String password, byte[] salt, int rounds) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(password.getBytes("UTF-8"), "HmacSHA256"));
        byte[] first = new byte[salt.length + 4]; System.arraycopy(salt, 0, first, 0, salt.length); first[salt.length + 3] = 1;
        byte[] u = mac.doFinal(first), out = u.clone();
        for (int i = 1; i < rounds; i++) {
            u = mac.doFinal(u);
            for (int j = 0; j < out.length; j++) { out[j] ^= u[j]; }
        }
        return out;
    }

    private static String hashPassword(String password) throws Exception {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        byte[] digest = pbkdf2(password, salt, 120000);
        return "pbkdf2$120000$" + hex(salt) + "$" + hex(digest);
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) { out.append(String.format("%02x", b & 0xff)); }
        return out.toString();
    }

    private static void emit(SQLiteDatabase db, JSONObject ctx, String action, String id, JSONObject payload) throws JSONException {
        long stamp = System.currentTimeMillis();
        String event = uid();
        insert(db, "sync_event", object("id", event, "shop_id", ctx.getString("shop_id"), "device_id", ctx.getString("device_id"),
            "event_type", action, "entity_id", id, "payload", payload.toString(), "created_at", stamp));
        insert(db, "operation_log", object("id", uid(), "operator_id", ctx.getString("operator_id"), "device_id", ctx.getString("device_id"),
            "action", action, "entity_id", id, "detail", object("event_id", event).toString(), "created_at", stamp));
    }

    private static JSONArray rows(SQLiteDatabase db, String sql, String... args) throws JSONException {
        Cursor cursor = db.rawQuery(sql, args);
        try {
            JSONArray result = new JSONArray();
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    Object value = cursor.isNull(i) ? JSONObject.NULL :
                        (cursor.getType(i) == Cursor.FIELD_TYPE_INTEGER ? (Object) cursor.getLong(i) : cursor.getString(i));
                    row.put(cursor.getColumnName(i), value);
                }
                result.put(row);
            }
            return result;
        } finally { cursor.close(); }
    }

    private static long scalar(SQLiteDatabase db, String sql, String... args) throws JSONException { return rows(db, sql, args).getJSONObject(0).getLong("value"); }
    private static JSONObject require(SQLiteDatabase db, String table, String id) throws JSONException {
        // Table identifiers here are internal constants, never request values.
        JSONArray result = rows(db, "SELECT * FROM " + table + " WHERE " + (table.equals("points_account") ? "member_id" : "id") + "=?", id);
        if (result.length() == 0) { reject("NOT_FOUND", "记录不存在"); }
        return result.getJSONObject(0);
    }
    private static ContentValues values(JSONObject row) throws JSONException {
        ContentValues values = new ContentValues();
        Iterator<String> keys = row.keys();
        while (keys.hasNext()) {
            String key = keys.next(); Object value = row.get(key);
            if (value == JSONObject.NULL) { values.putNull(key); }
            else if (value instanceof Number) { values.put(key, ((Number) value).longValue()); }
            else { values.put(key, (String) value); }
        }
        return values;
    }
    private static void insert(SQLiteDatabase db, String table, JSONObject row) throws JSONException { db.insertOrThrow(table, null, values(row)); }
    private static void update(SQLiteDatabase db, String table, String id, JSONObject row) throws JSONException {
        db.update(table, values(row), (table.equals("points_account") ? "member_id" : "id") + "=?", new String[] {id});
    }
    private static JSONObject object(Object... pairs) throws JSONException {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) { result.put((String) pairs[i], pairs[i + 1]); }
        return result;
    }
    private static String string(JSONObject input, String key, int max, boolean required) {
        Object value = input.opt(key);
        if (!(value instanceof String) || ((String) value).length() > max || (required && ((String) value).trim().length() == 0)) {
            reject("INVALID_INPUT", "参数错误：" + key);
        }
        return ((String) value).trim();
    }
    private static String optionalText(JSONObject input, String key, int max) { return input.has(key) ? string(input, key, max, false) : ""; }
    private static Object optionalNullableText(JSONObject input, String key, int max) { return !input.has(key) || input.isNull(key) ? JSONObject.NULL : string(input, key, max, false); }
    private static Object phone(JSONObject input) { return input.isNull("phone") ? JSONObject.NULL : string(input, "phone", 32, true); }
    private static long integer(JSONObject input, String key, long min, long max) {
        Object value = input.opt(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) { reject("INVALID_INPUT", "必须是整数：" + key); }
        long n = ((Number) value).longValue();
        if (n < min || n > max) { reject("INVALID_INPUT", "数量超出范围：" + key); }
        return n;
    }
    private static boolean oneOf(String value, String... choices) { for (String choice : choices) { if (choice.equals(value)) { return true; } } return false; }
    private static void reject(String code, String message) { throw new Rejected(code, message); }
    private static String uid() { return UUID.randomUUID().toString(); }
    private static String canonical(Object value) throws JSONException {
        if (!(value instanceof JSONObject)) { String encoded = new JSONArray().put(value).toString(); return encoded.substring(1, encoded.length() - 1); }
        JSONObject object = (JSONObject) value;
        List<String> keys = new ArrayList<String>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) { keys.add(iterator.next()); }
        Collections.sort(keys);
        StringBuilder out = new StringBuilder("{");
        for (String key : keys) {
            if (out.length() > 1) { out.append(','); }
            out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));
        }
        return out.append('}').toString();
    }
    private static String fingerprint(JSONObject value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) { hex.append(String.format(java.util.Locale.US, "%02x", b & 255)); }
        return hex.toString();
    }
    private static Response error(int code, String name, String message, boolean definitive) {
        try { return new Response(code, object("ok", false, "error", object("code", name, "message", message, "definitive", definitive))); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
