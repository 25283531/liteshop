package com.liteshop.terminal;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CloudSyncTest {
    private static String line(BufferedInputStream input) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (value != '\r') { result.write(value); }
        }
        return result.toString("UTF-8");
    }
    // Real HTTP verifies wire field names and receipt validation, including retries.
    private boolean exchange(final LocalStore store, final boolean complete) throws Exception {
        final ServerSocket listener = new ServerSocket(0);
        listener.setSoTimeout(10000);
        FutureTask<Void> response = new FutureTask<Void>(new Callable<Void>() {
            @Override public Void call() throws Exception {
                Socket socket = listener.accept();
                try {
                    socket.setSoTimeout(10000);
                    BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                    assertEquals("POST /api/v1/terminal/sync/events HTTP/1.1", line(input));
                    String line;
                    int length = 0;
                    boolean authorized = false;
                    while (!(line = line(input)).isEmpty()) {
                        if (line.toLowerCase(java.util.Locale.US).startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                        if (line.equals("Authorization: Bearer test-terminal-token")) { authorized = true; }
                    }
                    assertTrue(authorized);
                    assertTrue(length > 0);
                    byte[] request = new byte[length];
                    int offset = 0;
                    while (offset < length) {
                        int count = input.read(request, offset, length - offset);
                        assertTrue(count > 0);
                        offset += count;
                    }
                    JSONObject body = new JSONObject(new String(request, "UTF-8"));
                    JSONArray events = body.getJSONArray("events");
                    JSONArray accepted = new JSONArray();
                    assertTrue(events.length() > 0);
                    for (int i = 0; i < events.length(); i++) {
                        JSONObject event = events.getJSONObject(i);
                        assertFalse(event.has("id"));
                        assertTrue(event.getJSONObject("payload").length() > 0);
                        assertEquals(i + 1, event.getInt("sequence"));
                        if (complete) {
                            accepted.put(new JSONObject().put("event_id", event.getString("event_id"))
                                .put("status", "ALREADY_ACCEPTED"));
                        }
                    }
                    byte[] bytes = new JSONObject().put("ok", true).put("data",
                        new JSONObject().put("accepted", accepted)).toString().getBytes("UTF-8");
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                    output.write(bytes);
                    output.flush();
                    return null;
                } finally { socket.close(); }
            }
        });
        Thread thread = new Thread(response);
        thread.start();
        try {
            boolean result = new CloudSync(store).upload("http://127.0.0.1:" + listener.getLocalPort(), "test-terminal-token");
            response.get(15, TimeUnit.SECONDS);
            return result;
        } finally { listener.close(); thread.join(15000); }
    }

    @Test public void missingReceiptsStayPendingAndRetryPersists() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String database = "test-sync-" + UUID.randomUUID() + ".db";
        LocalStore store = new LocalStore(context, database);
        try {
            assertEquals(3, store.pendingEvents(100).length());
            assertFalse(exchange(store, false));
            assertEquals(3, store.pendingEvents(100).length());
            assertTrue(exchange(store, true));
            assertEquals(0, store.pendingEvents(100).length());
            store.close();
            store = new LocalStore(context, database);
            assertEquals(0, store.pendingEvents(100).length());
        } finally { store.close(); context.deleteDatabase(database); }
    }
}
