package com.liteshop.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
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
import android.os.Handler;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
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
    private WebView screensaver;
    private boolean screensaverShown;
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
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(8, 4, 8, 4);
        bar.setBackgroundColor(android.graphics.Color.rgb(245, 247, 248));
        bar.setMinimumHeight(56);
        status = new TextView(this);
        status.setText("LiteShop · 正在启动本地账本");
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setTextSize(14);
        status.setTextColor(android.graphics.Color.rgb(35, 70, 76));
        status.setPadding(8, 4, 10, 4);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button data = new Button(this);
        data.setText("数据");
        compactButton(data);
        data.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this).setTitle("机顶盒本地数据")
                    .setMessage("会员、余额、积分及流水保存在本机应用私有目录：\n"
                        + getDatabasePath("liteshop.db").getAbsolutePath()
                        + "\n\n断网可营业。云端绑定和数据汇总由 liteshop.250886.xyz 管理；云端投影不能替代本机备份。卸载应用或清除应用数据会删除本地账本。")
                    .setPositiveButton("知道了", null).show();
            }
        });
        bar.addView(data);
        Button serial = new Button(this);
        serial.setText("序列号");
        compactButton(serial);
        serial.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTerminalSerial(); }
        });
        bar.addView(serial);
        Button broadcast = new Button(this);
        broadcast.setText("群发");
        compactButton(broadcast);
        broadcast.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showBroadcastInfo(); }
        });
        bar.addView(broadcast);
        Button members = new Button(this);
        members.setText("会员");
        compactButton(members);
        members.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { focusMembers(); }
        });
        bar.addView(members);
        Button settingsButton = new Button(this);
        settingsButton.setText("设置");
        compactButton(settingsButton);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { focusSettings(); }
        });
        bar.addView(settingsButton);
        Button reload = new Button(this);
        reload.setText("重载");
        compactButton(reload);
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadHome(); }
        });
        bar.addView(reload);
        web = new WebView(this);
        layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        // Keep the native controls at the bottom so the member workspace remains the visual focus.
        layout.addView(bar, new LinearLayout.LayoutParams(-1, -2));
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
        final String url = saver.optString("media_url", "").trim();
        if (!(url.startsWith("https://") || url.startsWith("http://"))) { return; }
        screensaver = new WebView(this);
        screensaver.setBackgroundColor(android.graphics.Color.BLACK);
        WebSettings s = screensaver.getSettings(); s.setJavaScriptEnabled(false); s.setDomStorageEnabled(false); s.setMediaPlaybackRequiresUserGesture(false);
        String type = saver.optString("media_type", "auto");
        boolean video = "video".equals(type) || ("auto".equals(type) && (url.toLowerCase().endsWith(".mp4") || url.toLowerCase().endsWith(".webm")));
        String safe = JSONObject.quote(url);
        String html = video ? "<html><body style='margin:0;background:#000;overflow:hidden'><video autoplay loop muted playsinline style='width:100%;height:100%;object-fit:contain' src=" + safe + "></video></body></html>"
            : "<html><body style='margin:0;background:#000;overflow:hidden'><img style='width:100%;height:100%;object-fit:contain' src=" + safe + "></body></html>";
        screensaver.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null);
        root.addView(screensaver, new FrameLayout.LayoutParams(-1, -1));
        screensaverShown = true;
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
        if (screensaver != null) { root.removeView(screensaver); screensaver.destroy(); screensaver = null; }
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
        String serial = "未生成";
        try { serial = new JSONObject(store.handle("GET", "settings", null).body).getJSONObject("data").optString("serial_no", serial); }
        catch (Exception ignored) { }
        new AlertDialog.Builder(this).setTitle("登录/注册云端")
            .setMessage("请访问 liteshop.250886.xyz 进行注册/登录，并在云端绑定当前设备。\n\n当前设备的序列号：" + serial)
            .setPositiveButton("知道了", null).show();
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
                    .put("storage", "ANDROID_SQLITE").put("serialNo", terminalSerial()).put("bridgeVersion", 3).toString();
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
