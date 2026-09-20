package com.liteshop.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
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
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        status = new TextView(this);
        status.setText("LiteShop · 正在启动本地账本");
        status.setPadding(16, 8, 8, 8);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button data = new Button(this);
        data.setText("数据位置");
        data.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this).setTitle("机顶盒本地数据")
                    .setMessage("会员、余额、积分及流水保存在本机应用私有目录：\n"
                        + getDatabasePath("liteshop.db").getAbsolutePath()
                        + "\n\n断网可营业。可在云端同步中配置事件上传；云端投影不能替代本机备份。卸载应用或清除应用数据会删除本地账本。")
                    .setPositiveButton("知道了", null).show();
            }
        });
        bar.addView(data);
        Button cloud = new Button(this);
        cloud.setText("云端同步");
        cloud.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { configureCloud(); }
        });
        bar.addView(cloud);
        Button reload = new Button(this);
        reload.setText("重载页面");
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadHome(); }
        });
        bar.addView(reload);
        layout.addView(bar);
        web = new WebView(this);
        layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
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
        syncWorker.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { uploadCloud(); }
        }, 0, 30, TimeUnit.SECONDS);
    }

    private void configureCloud() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(24, 8, 24, 8);
        final android.widget.TextView endpoint = new android.widget.TextView(this);
        endpoint.setText("云端地址：" + CLOUD_ENDPOINT); fields.addView(endpoint);
        final android.widget.EditText token = new android.widget.EditText(this);
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setSingleLine(true); token.setHint("终端同步令牌"); token.setText(prefs.getString("cloud_token", "")); fields.addView(token);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("云端同步设置").setView(fields)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String key = token.getText().toString();
                        if (key.length() < 16) {
                            token.setError("请输入至少 16 位令牌"); return;
                        }
                        prefs.edit().putString("cloud_endpoint", CLOUD_ENDPOINT)
                            .putString("cloud_token", key).apply();
                        dialog.dismiss(); syncCloud();
                    }
                });
            }
        });
        dialog.show();
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
        @JavascriptInterface public String getTerminalInfo() {
            try {
                return new JSONObject().put("platform", "android").put("api", Build.VERSION.SDK_INT)
                    .put("androidVersion", Build.VERSION.RELEASE).put("model", Build.MODEL)
                    .put("storage", "ANDROID_SQLITE").put("bridgeVersion", 2).toString();
            } catch (Exception e) { return "{}"; }
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
