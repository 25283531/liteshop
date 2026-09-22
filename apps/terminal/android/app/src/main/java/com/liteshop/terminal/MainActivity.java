package com.liteshop.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.VideoView;
import android.widget.MediaController;
import android.os.Handler;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/** API 19 standalone terminal: APK assets -> native bridge -> device-private SQLite. */
public class MainActivity extends Activity {
    private static final String CLOUD_ENDPOINT = "https://liteshop.250886.xyz";
    private static final String ORIGIN = "http://liteshop.invalid";
    private WebView web;
    private TextView status;
    private LocalStore store;
    private SharedPreferences prefs;
    private volatile boolean destroyed;
    private volatile long readyAt;
    private FrameLayout root;
    private FrameLayout screensaver;
    private ImageView screensaverImage;
    private VideoView screensaverVideo;
    private org.json.JSONArray screensaverItems;
    private int screensaverIndex;
    private final Runnable screensaverAdvance = new Runnable() { @Override public void run() { showNextScreensaverItem(); } };
    private boolean screensaverShown;
    private static final int PICK_SCREENSAVER_MEDIA = 4107;
    private long lastActivityAt;
    private final Handler idleHandler = new Handler();
    private final Runnable idleCheck = new Runnable() { @Override public void run() { checkScreensaver(); idleHandler.postDelayed(this, 10000); } };
    private boolean localFinished, syncFinished;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
        1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(24)) {
        @Override protected void terminated() { workerFinished(false); }
    };
    private final ScheduledThreadPoolExecutor syncWorker = new ScheduledThreadPoolExecutor(1) {
        @Override protected void terminated() { workerFinished(true); }
    };
    private synchronized void workerFinished(boolean cloud) {
        if (cloud) { syncFinished = true; } else { localFinished = true; }
        if (localFinished && syncFinished && store != null) { store.close(); }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new LocalStore(this);
        prefs = getSharedPreferences("terminal", MODE_PRIVATE);
        root = new FrameLayout(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setText("正在启动本地账本");
        web = new WebView(this);
        layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new TerminalBridge(), "LiteShopTerminal");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.equals(ORIGIN + "/index.html") && !url.equals(ORIGIN + "/");
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                try {
                    String file, mime;
                    if (url.equals(ORIGIN + "/") || url.equals(ORIGIN + "/index.html")) {
                        file = "index.html"; mime = "text/html";
                    } else if (url.equals(ORIGIN + "/app.js")) {
                        file = "app.js"; mime = "application/javascript";
                    } else if (url.equals(ORIGIN + "/app.css")) {
                        file = "app.css"; mime = "text/css";
                    } else { return emptyResponse(); }
                    return new WebResourceResponse(mime, "UTF-8", getAssets().open(file));
                } catch (Exception e) { return emptyResponse(); }
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                status.setText("LiteShop · 正在载入本地页面");
            }
            @Override public void onReceivedError(WebView view, int code, String description, String failingUrl) {
                status.setText("页面载入失败，请点击重载页面");
            }
        });
        loadHome();
        lastActivityAt = System.currentTimeMillis();
        idleHandler.postDelayed(idleCheck, 10000);
        syncWorker.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { uploadCloud(); refreshCloudAccount(); }
        }, 0, 30, TimeUnit.SECONDS);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) { wakeScreensaver(); }
        lastActivityAt = System.currentTimeMillis();
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (screensaverShown) { wakeScreensaver(); return true; }
        if (keyCode == KeyEvent.KEYCODE_F10) { lastActivityAt = 0; checkScreensaver(); return true; }
        lastActivityAt = System.currentTimeMillis();
        return super.onKeyDown(keyCode, event);
    }

    private void checkScreensaver() {
        if (destroyed || screensaverShown) { return; }
        try {
            JSONObject settings = new JSONObject(store.handle("GET", "settings", null).body).getJSONObject("data").optJSONObject("settings");
            JSONObject saver = settings == null ? null : settings.optJSONObject("screensaver");
            if (saver != null && saver.optBoolean("enabled", false)) {
                long minutes = Math.max(1, Math.min(1440, saver.optLong("timeout_minutes", 10)));
                if (System.currentTimeMillis() - lastActivityAt >= minutes * 60000L) { showScreensaver(saver); }
            }
        } catch (Exception ignored) { }
    }

    private void showScreensaver(JSONObject saver) {
        screensaverItems = saver.optJSONArray("media_items");
        if (screensaverItems == null || screensaverItems.length() == 0) {
            String legacy = saver.optString("media_url", "").trim();
            if (legacy.length() > 0) { try { screensaverItems = new org.json.JSONArray().put(new JSONObject().put("uri", legacy).put("mime", saver.optString("media_type", "auto"))); } catch (Exception ignored) { screensaverItems = null; } }
        }
        if (screensaverItems == null || screensaverItems.length() == 0) { return; }
        screensaver = new FrameLayout(this); screensaver.setBackgroundColor(Color.BLACK);
        screensaverImage = new ImageView(this); screensaverImage.setBackgroundColor(Color.BLACK); screensaverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        screensaverVideo = new VideoView(this); screensaverVideo.setBackgroundColor(Color.BLACK);
        screensaverVideo.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() { @Override public void onCompletion(android.media.MediaPlayer mp) { showNextScreensaverItem(); } });
        screensaverVideo.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() { @Override public boolean onError(android.media.MediaPlayer mp, int what, int extra) { showNextScreensaverItem(); return true; } });
        root.addView(screensaver, new FrameLayout.LayoutParams(-1, -1));
        screensaverShown = true; screensaverIndex = 0;
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN, android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        showNextScreensaverItem();
    }

    private void showNextScreensaverItem() {
        if (!screensaverShown || screensaverItems == null || screensaverItems.length() == 0) { return; }
        idleHandler.removeCallbacks(screensaverAdvance);
        JSONObject item;
        try { item = screensaverItems.getJSONObject(screensaverIndex++ % screensaverItems.length()); }
        catch (Exception e) { return; }
        String uriText = item.optString("uri", "").trim();
        if (!(uriText.startsWith("content://") || uriText.startsWith("file://"))) { showNextScreensaverItem(); return; }
        Uri uri = Uri.parse(uriText); String mime = item.optString("mime", "").toLowerCase();
        boolean video = mime.startsWith("video/") || (!mime.startsWith("image/") && (uriText.toLowerCase().endsWith(".mp4") || uriText.toLowerCase().endsWith(".webm") || uriText.toLowerCase().endsWith(".3gp")));
        if (video) {
            screensaver.removeAllViews(); screensaver.addView(screensaverVideo, new FrameLayout.LayoutParams(-1, -1));
            try { screensaverVideo.setVideoURI(uri); screensaverVideo.start(); } catch (Exception e) { showNextScreensaverItem(); }
        } else {
            screensaver.removeAllViews(); screensaver.addView(screensaverImage, new FrameLayout.LayoutParams(-1, -1));
            try { screensaverImage.setImageURI(uri); idleHandler.postDelayed(screensaverAdvance, 10000); } catch (Exception e) { showNextScreensaverItem(); }
        }
    }

    private void wakeScreensaver() {
        if (!screensaverShown) { return; }
        try {
            JSONObject settings = new JSONObject(store.handle("GET", "settings", null).body).getJSONObject("data").optJSONObject("settings");
            JSONObject saver = settings == null ? null : settings.optJSONObject("screensaver");
            if (saver != null && saver.optBoolean("require_password", false)) { promptScreensaverPassword(); return; }
        } catch (Exception ignored) { }
        unlockScreensaver();
    }

    private void unlockScreensaver() {
        screensaverShown = false; lastActivityAt = System.currentTimeMillis();
        idleHandler.removeCallbacks(screensaverAdvance);
        if (screensaverVideo != null) { try { screensaverVideo.stopPlayback(); } catch (Exception ignored) {} }
        if (screensaver != null) { root.removeView(screensaver); screensaver = null; }
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private void promptScreensaverPassword() {
        final android.widget.EditText input = new android.widget.EditText(this); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); input.setSingleLine(true); input.setHint("本地设置密码");
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("解锁 LiteShop").setMessage("请输入本地设置密码继续营业").setView(input).setPositiveButton("解锁", null).setNegativeButton("稍后", null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() { @Override public void onShow(DialogInterface ignored) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (store.checkLocalPassword(input.getText().toString())) { dialog.dismiss(); unlockScreensaver(); } else { input.setError("密码错误"); } } }); } });
        dialog.show();
    }

    private void compactButton(Button button) {
        button.setTextSize(14);
        button.setMinHeight(44);
        button.setPadding(12, 0, 12, 0);
        button.setSingleLine(true);
    }

    private void focusMembers() {
        if (web != null) {
            web.evaluateJavascript("(function(){var e=document.getElementById('search');if(e){e.focus();e.scrollIntoView(true);}})();", null);
        }
    }

    private void focusSettings() {
        if (web != null) {
            web.evaluateJavascript("(function(){var e=document.getElementById('settings');if(e){e.click();}})();", null);
        }
    }

    private void showCloudLogin() {
        final LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(32, 0, 32, 0);
        final EditText identity = new EditText(this); identity.setSingleLine(true); identity.setHint("用户名或邮箱"); identity.setInputType(android.text.InputType.TYPE_CLASS_TEXT); form.addView(identity, new LinearLayout.LayoutParams(-1, -2));
        final EditText password = new EditText(this); password.setSingleLine(true); password.setHint("云端密码"); password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); form.addView(password, new LinearLayout.LayoutParams(-1, -2));
        final TextView message = new TextView(this); message.setTextColor(Color.rgb(180, 60, 40)); message.setPadding(0, 12, 0, 0); form.addView(message, new LinearLayout.LayoutParams(-1, -2));
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("登录云端并绑定设备").setMessage("请输入云端注册的用户名或邮箱和密码。登录成功后本机将自动绑定，网络异常时数据会保留在待同步队列。\n\n云端地址：liteshop.250886.xyz").setView(form).setPositiveButton("登录并绑定", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() { @Override public void onShow(DialogInterface ignored) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                final String user = identity.getText().toString().trim(), pass = password.getText().toString();
                if (user.length() == 0 || pass.length() == 0) { message.setText("请输入用户名/邮箱和密码"); return; }
                v.setEnabled(false); message.setText("正在登录并绑定…");
                syncWorker.execute(new Runnable() { @Override public void run() {
                    String error = null; JSONObject result = null; HttpURLConnection connection = null;
                    try {
                        JSONObject local = new JSONObject(store.handle("GET", "settings", null).body).getJSONObject("data");
                        JSONObject body = new JSONObject().put("identity", user).put("password", pass).put("serial_no", local.optString("serial_no", "")).put("device_id", local.optString("device_id", "")).put("shop_id", local.optString("id", ""));
                        byte[] bytes = body.toString().getBytes("UTF-8");
                        connection = (HttpURLConnection) new URL(CLOUD_ENDPOINT + "/api/v1/terminal/auth/login").openConnection(); connection.setConnectTimeout(8000); connection.setReadTimeout(12000); connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(bytes.length);
                        OutputStream output = connection.getOutputStream(); try { output.write(bytes); } finally { output.close(); }
                        int code = connection.getResponseCode(); String response = readConnection(connection); JSONObject envelope = new JSONObject(response);
                        if (code < 200 || code >= 300 || !envelope.optBoolean("ok", false)) { JSONObject e = envelope.optJSONObject("error"); error = e == null ? "云端登录失败" : e.optString("message", "云端登录失败"); }
                        else { result = envelope.getJSONObject("data"); }
                    } catch (Exception e) { error = "无法连接云端，请检查网络"; }
                    finally { if (connection != null) { connection.disconnect(); } }
                    final String failure = error; final JSONObject success = result;
                    runOnUiThread(new Runnable() { @Override public void run() { v.setEnabled(true); if (failure != null) { message.setText(failure); return; } String token = success == null ? "" : success.optString("terminal_token", ""); if (token.length() == 0) { message.setText("云端未返回终端令牌"); return; } prefs.edit().putString("cloud_token", token).putString("cloud_username", success.optString("username", user)).apply(); store.setCloudState("SYNCING"); message.setText("登录并绑定成功，正在同步本地数据…"); dialog.dismiss(); syncCloud(); } });
                }});
            } });
        }});
        dialog.show();
    }

    private void pickScreensaverMedia() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
        try { startActivityForResult(intent, PICK_SCREENSAVER_MEDIA); }
        catch (Exception ignored) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE); fallback.setType("*/*"); fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try { startActivityForResult(fallback, PICK_SCREENSAVER_MEDIA); } catch (Exception ignoredAgain) { }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_SCREENSAVER_MEDIA || resultCode != RESULT_OK || data == null) { return; }
        org.json.JSONArray selected = new org.json.JSONArray();
        try {
            if (data.getClipData() != null) { for (int i = 0; i < data.getClipData().getItemCount(); i++) { addPickedUri(selected, data.getClipData().getItemAt(i).getUri(), data.getFlags()); } }
            else if (data.getData() != null) { addPickedUri(selected, data.getData(), data.getFlags()); }
        } catch (Exception ignored) { }
        if (selected.length() == 0) { return; }
        if (web != null) {
            web.evaluateJavascript("window.LiteShopMediaPicked && window.LiteShopMediaPicked(" + selected.toString() + ");", null);
        }
    }

    private void addPickedUri(org.json.JSONArray selected, Uri uri, int flags) throws Exception {
        if (uri == null) { return; }
        String text = uri.toString(); if (!(text.startsWith("content://") || text.startsWith("file://"))) { return; }
        try { getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        String mime = getContentResolver().getType(uri); if (mime == null) { mime = ""; }
        if (mime.startsWith("image/") || mime.startsWith("video/") || mime.length() == 0) { selected.put(new JSONObject().put("uri", text).put("mime", mime)); }
    }

    private void refreshCloudAccount() {
        try {
            JSONObject local = new JSONObject(store.handle("GET", "settings", null).body).getJSONObject("data");
            String serial = local.optString("serial_no", "");
            if (serial.length() != 16) { return; }
            HttpURLConnection connection = (HttpURLConnection) new URL(CLOUD_ENDPOINT + "/api/v1/terminal/account?serial_no=" + serial).openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(8000); connection.setRequestMethod("GET");
            if (connection.getResponseCode() != 200) { return; }
            InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream();
            try { byte[] buffer = new byte[1024]; int count; while ((count = input.read(buffer)) != -1 && output.size() <= 65536) { output.write(buffer, 0, count); } } finally { input.close(); connection.disconnect(); }
            JSONObject result = new JSONObject(output.toString("UTF-8")).getJSONObject("data");
            prefs.edit().putString("cloud_username", result.optString("username", "")).apply();
        } catch (Exception ignored) { }
    }

    private String readConnection(HttpURLConnection connection) throws Exception {
        InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream();
        try { byte[] buffer = new byte[2048]; int count; while ((count = input.read(buffer)) != -1 && output.size() <= 262144) { output.write(buffer, 0, count); } }
        finally { input.close(); }
        return output.toString("UTF-8");
    }

    private void showTerminalSerial() {
        try {
            JSONObject settings = new JSONObject(store.handle("GET", "settings", null).body).getJSONObject("data");
            new AlertDialog.Builder(this).setTitle("终端序列号").setMessage("请将以下 16 位序列号填写到云端账户页面进行绑定：\n\n" + settings.optString("serial_no", "未生成"))
                .setPositiveButton("知道了", null).show();
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("终端序列号").setMessage("读取序列号失败，请重载应用").setPositiveButton("知道了", null).show();
        }
    }

    private void showBroadcastInfo() {
        String message = "请先访问 liteshop.250886.xyz 注册/登录并绑定当前设备。公众号消息任务按店铺隔离，请在云端账户页面创建群发任务，运营者审核并配置公众号后发送。";
        new AlertDialog.Builder(this).setTitle("公众号群发").setMessage(message).setPositiveButton("知道了", null).show();
    }

    private void syncCloud() {
        try { syncWorker.execute(new Runnable() { @Override public void run() { uploadCloud(); } }); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { /* Activity is closing. */ }
    }

    private void uploadCloud() {
        final String endpoint = CLOUD_ENDPOINT;
        final String token = prefs.getString("cloud_token", "");
        if (token.length() == 0) { store.setCloudState("NOT_CONFIGURED"); return; }
        store.setCloudState("SYNCING");
        final boolean ok = new CloudSync(store).upload(endpoint, token);
        boolean pending = true;
        try { pending = store.pendingEvents(1).length() != 0; } catch (Exception ignored) { }
        final boolean complete = ok && !pending;
        store.setCloudState(complete ? "SYNCED" : "PENDING");
        runOnUiThread(new Runnable() { @Override public void run() {
            if (!destroyed) { status.setText(complete ? "LiteShop · 本地账本 · 云端已同步" : "LiteShop · 本地账本 · 待同步"); }
        }});
    }

    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }
    private void loadHome() {
        readyAt = 0;
        web.loadUrl(ORIGIN + "/index.html");
        web.postDelayed(new Runnable() {
            @Override public void run() {
                if (!destroyed && readyAt == 0) { status.setText("页面尚未就绪，请重载页面或检查安装包"); }
            }
        }, 12000);
    }
    private boolean allowed(String method, String path) {
        if (path == null || path.length() > 2048 || path.indexOf('#') >= 0) { return false; }
        if ("GET".equals(method)) {
            return path.equals("status") || path.equals("card-types")
                || path.equals("settings")
                || path.matches("members(\\?q=[^#]*&offset=[0-9]+)?")
                || path.matches("members/[a-f0-9-]{36}");
        }
        return "POST".equals(method) && path.matches("commands/(create-member|update-member|update-settings|delete-member|open-card|card-status|transact|points)");
    }
    private void deliver(final String id, final int code, final String body) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!destroyed) {
                    web.evaluateJavascript("window.LiteShopReceive(" + JSONObject.quote(id) + ","
                        + code + "," + JSONObject.quote(body) + ");", null);
                }
            }
        });
    }
    public final class TerminalBridge {
        @JavascriptInterface public void showCloudLogin() { runOnUiThread(new Runnable() { @Override public void run() { MainActivity.this.showCloudLogin(); } }); }
        @JavascriptInterface public void pickScreensaverMedia() { runOnUiThread(new Runnable() { @Override public void run() { MainActivity.this.pickScreensaverMedia(); } }); }
        @JavascriptInterface public String getCloudAccountInfo() {
            try {
                String username = prefs.getString("cloud_username", "");
                return new JSONObject().put("bound", username.length() > 0).put("username", username).toString();
            } catch (Exception e) { return "{\"bound\":false,\"username\":\"\"}"; }
        }
        @JavascriptInterface public String getTerminalInfo() {
            try {
                return new JSONObject().put("platform", "android").put("api", Build.VERSION.SDK_INT)
                    .put("androidVersion", Build.VERSION.RELEASE).put("model", Build.MODEL)
                    .put("storage", "ANDROID_SQLITE").put("databasePath", getDatabasePath("liteshop.db").getAbsolutePath())
                    .put("serialNo", terminalSerial()).put("bridgeVersion", 3).toString();
            } catch (Exception e) { return "{}"; }
        }
        private String terminalSerial() {
            try { return new JSONObject(store.handle("GET", "settings", null).body).getJSONObject("data").optString("serial_no", ""); }
            catch (Exception ignored) { return ""; }
        }
        @JavascriptInterface public void reportReady(String payload) {
            if (payload == null || payload.length() > 512) { return; }
            readyAt = System.currentTimeMillis();
            runOnUiThread(new Runnable() {
                @Override public void run() { if (!destroyed) { status.setText("LiteShop · 机顶盒本地账本"); } }
            });
        }
        @JavascriptInterface public String requestBackup() {
            return "{\"ok\":false,\"error\":{\"code\":\"NOT_IMPLEMENTED\",\"message\":\"本机备份导出尚未实现\"}}";
        }
        @JavascriptInterface public String requestPrint(String payload) {
            return "{\"ok\":false,\"error\":{\"code\":\"NOT_IMPLEMENTED\",\"message\":\"USB 打印机驱动及打印队列待适配\"}}";
        }
        @JavascriptInterface public void request(final String id, final String method, final String path, final String body) {
            if (id == null || !id.matches("[0-9-]{1,80}")) { return; }
            if (!allowed(method, path) || body == null || body.length() > 16384) {
                deliver(id, 400, "{\"ok\":false,\"error\":{\"code\":\"INVALID_INPUT\",\"message\":\"桥接请求被拒绝\",\"definitive\":true}}");
                return;
            }
            try {
                worker.execute(new Runnable() {
                    @Override public void run() {
                        LocalStore.Response result = store.handle(method, path, body);
                        if ("POST".equals(method) && result.code == 200 && !prefs.getString("cloud_token", "").isEmpty()) {
                            store.setCloudState("PENDING");
                            syncCloud();
                        }
                        deliver(id, result.code, result.body);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                deliver(id, 503, "{\"ok\":false,\"error\":{\"code\":\"DATABASE_UNAVAILABLE\",\"message\":\"终端繁忙，请稍后重试原请求\",\"definitive\":false}}");
            }
        }
    }
    @Override public void onBackPressed() {
        new AlertDialog.Builder(this).setMessage("退出 LiteShop 终端？")
            .setNegativeButton("继续营业", null).setPositiveButton("退出", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { finish(); }
            }).show();
    }
    @Override public void onDestroy() {
        destroyed = true;
        syncWorker.shutdownNow();
        worker.shutdown(); // Finish accepted commands, then close SQLite; callbacks are ignored.
        web.removeJavascriptInterface("LiteShopTerminal");
        web.destroy();
        super.onDestroy();
    }
}
