package com.liteshop.terminal;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Optional outbox uploader. Empty configuration keeps the terminal fully offline. */
final class CloudSync {
    private final LocalStore store;
    CloudSync(LocalStore store) { this.store = store; }

    boolean upload(String endpoint, String token) {
        if (endpoint == null || token == null || endpoint.length() == 0 || token.length() == 0) { return false; }
        HttpURLConnection connection = null;
        try {
            JSONArray events = store.pendingEvents(100);
            if (events.length() == 0) { return true; }
            JSONArray payload = new JSONArray();
            JSONArray ids = new JSONArray();
            Set<String> expected = new HashSet<String>();
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                String id = event.getString("id");
                event.remove("id");
                event.put("event_id", id);
                event.put("payload", new JSONObject(event.getString("payload")));
                payload.put(event);
                ids.put(id);
                expected.add(id);
            }
            JSONObject body = new JSONObject().put("events", payload);
            connection = (HttpURLConnection) new URL(endpoint + "/api/v1/terminal/sync/events").openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.toString().getBytes("UTF-8");
            String timestamp = String.valueOf(System.currentTimeMillis());
            String nonce = UUID.randomUUID().toString();
            connection.setRequestProperty("X-LiteShop-Timestamp", timestamp);
            connection.setRequestProperty("X-LiteShop-Nonce", nonce);
            connection.setRequestProperty("X-LiteShop-Signature", sign(token, timestamp + "\n" + nonce + "\n" + body.toString()));
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream output = connection.getOutputStream();
            try { output.write(bytes); } finally { output.close(); }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) { return false; }
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (response.size() + count > 1024 * 1024) { return false; }
                    response.write(buffer, 0, count);
                }
            } finally { input.close(); }
            JSONObject result = new JSONObject(response.toString("UTF-8"));
            if (!result.getBoolean("ok")) { return false; }
            JSONArray accepted = result.getJSONObject("data").getJSONArray("accepted");
            for (int i = 0; i < accepted.length(); i++) {
                JSONObject ack = accepted.getJSONObject(i);
                String state = ack.getString("status");
                if (!(state.equals("ACCEPTED") || state.equals("ALREADY_ACCEPTED"))
                    || !expected.remove(ack.getString("event_id"))) { return false; }
            }
            if (!expected.isEmpty()) { return false; }
            store.markEventsSynced(ids);
            return true;
        } catch (Exception ignored) { return false; }
        finally { if (connection != null) { connection.disconnect(); } }
    }

    private static String sign(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(Charset.forName("UTF-8")), "HmacSHA256"));
        byte[] digest = mac.doFinal(value.getBytes(Charset.forName("UTF-8")));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) { hex.append(String.format(java.util.Locale.US, "%02x", b & 255)); }
        return hex.toString();
    }
}
