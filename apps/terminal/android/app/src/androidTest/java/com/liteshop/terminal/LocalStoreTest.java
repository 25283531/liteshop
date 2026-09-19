package com.liteshop.terminal;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class LocalStoreTest {
    private Context context;
    private String database;
    private LocalStore store;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        database = "test-ledger-" + UUID.randomUUID() + ".db";
        store = new LocalStore(context, database);
    }
    @After public void tearDown() {
        if (store != null) { store.close(); }
        context.deleteDatabase(database); // Only the unique database created by this test.
    }
    private JSONObject object(Object... pairs) throws Exception {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) { result.put((String) pairs[i], pairs[i + 1]); }
        return result;
    }
    private JSONObject ok(String method, String path, JSONObject payload) throws Exception {
        LocalStore.Response response = store.handle(method, path, payload == null ? "" : payload.toString());
        assertEquals(response.body, 200, response.code);
        JSONObject envelope = new JSONObject(response.body);
        assertTrue(response.body, envelope.getBoolean("ok"));
        return envelope;
    }
    private JSONObject get(String path) throws Exception { return ok("GET", path, null).getJSONObject("data"); }
    private JSONObject post(String action, JSONObject payload) throws Exception { return ok("POST", "commands/" + action, payload).getJSONObject("data"); }
    private void rejects(String action, JSONObject payload, String code) throws Exception {
        LocalStore.Response response = store.handle("POST", "commands/" + action, payload.toString());
        JSONObject envelope = new JSONObject(response.body);
        assertFalse(response.body, envelope.getBoolean("ok"));
        assertEquals(code, envelope.getJSONObject("error").getString("code"));
        assertTrue(envelope.getJSONObject("error").getBoolean("definitive"));
    }
    private String member() throws Exception {
        return post("create-member", object("request_id", UUID.randomUUID().toString(), "name", "测试会员", "phone", "13800000000")).getString("id");
    }
    private String card(String member, String mode) throws Exception {
        JSONArray types = ok("GET", "card-types", null).getJSONArray("data");
        String type = null;
        for (int i = 0; i < types.length(); i++) {
            if (types.getJSONObject(i).getString("mode").equals(mode)) { type = types.getJSONObject(i).getString("id"); }
        }
        assertNotNull(type);
        return post("open-card", object("request_id", UUID.randomUUID().toString(), "member_id", member, "card_type_id", type)).getString("id");
    }
    private JSONObject tx(String card, String kind, String field, long amount) throws Exception {
        return post("transact", object("request_id", UUID.randomUUID().toString(), "card_id", card, "kind", kind, field, amount));
    }
    private long count(String table) {
        Cursor cursor = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table, null);
        try { cursor.moveToFirst(); return cursor.getLong(0); } finally { cursor.close(); }
    }

    @Test public void offlineWorkflowPersistsAndRetriesOnlyOnce() throws Exception {
        JSONObject status = get("status");
        String shop = status.getJSONObject("shop").getString("id");
        assertEquals("ANDROID_SQLITE", status.getString("storage"));
        assertEquals("NOT_CONFIGURED", status.getString("cloud_sync"));
        assertEquals(3, status.getInt("pending_events"));
        String member = member(), card = card(member, "STORED");
        JSONObject recharge = object("request_id", "lost-response", "card_id", card, "kind", "RECHARGE", "amount", 10025);
        JSONObject first = post("transact", recharge);
        assertEquals(10025, first.getLong("balance_after"));
        store.close();
        store = new LocalStore(context, database);
        assertEquals(first.getString("id"), post("transact", recharge).getString("id"));
        // Reordered keys are still the same command.
        assertEquals(first.getString("id"), post("transact", object("amount", 10025, "kind", "RECHARGE", "card_id", card, "request_id", "lost-response")).getString("id"));
        assertEquals(1, count("ledger_transaction"));
        assertEquals(3, count("command_receipt"));
        assertEquals(6, count("sync_event"));
        assertEquals(shop, get("status").getJSONObject("shop").getString("id"));
        recharge.put("amount", 10026);
        rejects("transact", recharge, "IDEMPOTENCY_CONFLICT");

        JSONObject consumption = tx(card, "CONSUME", "amount", 2005);
        assertEquals(8020, consumption.getLong("balance_after"));
        JSONObject refund = object("request_id", "refund-one", "card_id", card, "kind", "REFUND",
            "amount", 505, "source_id", consumption.getString("id"));
        assertEquals(8525, post("transact", refund).getLong("balance_after"));
        long receipts = count("command_receipt"), events = count("sync_event");
        rejects("transact", refund.put("request_id", "too-much").put("amount", 1501), "REFUND_EXCEEDED");
        rejects("transact", object("request_id", "no-money", "card_id", card, "kind", "CONSUME", "amount", 9000), "INSUFFICIENT_BALANCE");
        assertEquals(receipts, count("command_receipt"));
        assertEquals(events, count("sync_event"));
        assertEquals(8525, get("members/" + member).getJSONArray("cards").getJSONObject(0).getLong("balance"));
        post("points", object("request_id", "points", "member_id", member, "points", 120, "remark", "奖励"));
        rejects("points", object("request_id", "points-too-much", "member_id", member, "points", -121, "remark", "兑换"), "INSUFFICIENT_POINTS");
        store.close();
        store = new LocalStore(context, database);
        JSONObject detail = get("members/" + member);
        assertEquals(120, detail.getJSONObject("points").getLong("balance"));
        assertEquals(3, detail.getJSONArray("transactions").length());
        assertEquals(1, detail.getJSONArray("points_transactions").length());
        assertEquals(1, ok("GET", "members?q=13800000000&offset=0", null).getJSONArray("data").length());
        assertEquals(count("operation_log"), count("sync_event"));
    }

    @Test public void countCardsAndStateGuards() throws Exception {
        String member = member(), card = card(member, "COUNT");
        assertEquals(10, tx(card, "CREDIT_TIMES", "times", 10).getLong("times_after"));
        JSONObject debit = tx(card, "DEDUCT_TIMES", "times", 3);
        assertEquals(7, debit.getLong("times_after"));
        assertEquals(9, post("transact", object("request_id", "visits-refund", "card_id", card, "kind", "REFUND",
            "times", 2, "source_id", debit.getString("id"))).getLong("times_after"));
        rejects("transact", object("request_id", "bad-mode", "card_id", card, "kind", "RECHARGE", "amount", 100), "CARD_MODE_MISMATCH");
        rejects("transact", object("request_id", "fraction", "card_id", card, "kind", "CREDIT_TIMES", "times", 1.5), "INVALID_INPUT");
        rejects("transact", object("request_id", "string", "card_id", card, "kind", "CREDIT_TIMES", "times", "1"), "INVALID_INPUT");
        JSONObject current = get("members/" + member).getJSONArray("cards").getJSONObject(0);
        post("card-status", object("request_id", "lost", "card_id", card, "status", "LOST", "version", current.getLong("version")));
        rejects("transact", object("request_id", "lost-debit", "card_id", card, "kind", "DEDUCT_TIMES", "times", 1), "CARD_INACTIVE");
        rejects("card-status", object("request_id", "stale", "card_id", card, "status", "ACTIVE", "version", current.getLong("version")), "VERSION_CONFLICT");
        post("card-status", object("request_id", "recover", "card_id", card, "status", "ACTIVE", "version", current.getLong("version") + 1));
        post("update-member", object("request_id", "disable", "member_id", member, "version", 1, "status", 0));
        rejects("transact", object("request_id", "disabled-member", "card_id", card, "kind", "DEDUCT_TIMES", "times", 1), "MEMBER_INACTIVE");
        rejects("update-member", object("request_id", "stale-member", "member_id", member, "version", 1, "name", "新名字"), "VERSION_CONFLICT");
        rejects("create-member", object("request_id", "unexpected", "name", "test", "sql", "ignored"), "INVALID_INPUT");
    }

    @Test public void outboxAndReceiptFailuresRollbackTheEntireWrite() throws Exception {
        String member = member(), card = card(member, "STORED");
        tx(card, "RECHARGE", "amount", 100);
        long events = count("sync_event"), receipts = count("command_receipt"), logs = count("operation_log");
        SQLiteDatabase db = store.getWritableDatabase();
        for (String table : new String[] {"sync_event", "command_receipt"}) {
            db.execSQL("CREATE TRIGGER fail_write BEFORE INSERT ON " + table + " BEGIN SELECT RAISE(ABORT,'injected failure'); END;");
            rejects("transact", object("request_id", "rollback-" + table, "card_id", card, "kind", "CONSUME", "amount", 20), "CONSTRAINT_VIOLATION");
            db.execSQL("DROP TRIGGER fail_write");
            assertEquals(1, count("ledger_transaction"));
            assertEquals(events, count("sync_event"));
            assertEquals(receipts, count("command_receipt"));
            assertEquals(logs, count("operation_log"));
            assertEquals(100, get("members/" + member).getJSONArray("cards").getJSONObject(0).getLong("balance"));
        }
        Cursor integrity = db.rawQuery("PRAGMA integrity_check", null);
        try { assertTrue(integrity.moveToFirst()); assertEquals("ok", integrity.getString(0)); } finally { integrity.close(); }
        Cursor foreignKeys = db.rawQuery("PRAGMA foreign_key_check", null);
        try { assertFalse(foreignKeys.moveToFirst()); } finally { foreignKeys.close(); }
    }
}
