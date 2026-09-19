package com.liteshop.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** API 19 shell: bundled UI, native HTTP transport, no remote page in bridge WebView. */
public class MainActivity extends Activity {
    private static final String ORIGIN = "http://liteshop.invalid";
    private WebView web;
    private TextView status;
    private SharedPreferences prefs;
    private volatile String endpoint = "";
    private volatile String token = "";
    private volatile boolean destroyed = false;
    private volatile long readyAt = 0;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
        2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(24));

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("terminal", MODE_PRIVATE);
        endpoint = prefs.getString("endpoint", "");
        token = prefs.getString("token", "");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        status = new TextView(this);
        status.setText("LiteShop · 正在启动");
        status.setPadding(16, 8, 8, 8);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = new Button(this);
        settings.setText("连接设置");
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { configure(); }
        });
        bar.addView(settings);
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
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
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
                    } else {
                        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                    }
                    return new WebResourceResponse(mime, "UTF-8", getAssets().open(file));
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "UTF-8",
                        new ByteArrayInputStream("Resource unavailable. Use reload.".getBytes()));
                }
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                status.setText("LiteShop · 正在载入本地页面");
            }
            @Override public void onReceivedError(WebView view, int code, String description, String failingUrl) {
                status.setText("页面载入失败，请点击重载页面");
            }
        });
        loadHome();
        if (endpoint.length() == 0) { configure(); }
    }

    private void loadHome() {
        readyAt = 0;
        web.loadUrl(ORIGIN + "/index.html");
        web.postDelayed(new Runnable() {
            @Override public void run() {
                if (!destroyed && readyAt == 0) {
                    status.setText("页面尚未就绪，请重载页面或检查安装包");
                }
            }
        }, 12000);
    }

    private static boolean validEndpoint(String value) {
        try {
            URI uri = new URI(value);
            if (!"http".equals(uri.getScheme()) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getPath() != null && uri.getPath().length() != 0)
                || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) { return false; }
            String host = uri.getHost();
            if (host == null) { return false; }
            if ("localhost".equals(host)) { return true; }
            String[] octets = host.split("\\.");
            if (octets.length != 4) { return false; }
            int[] ip = new int[4];
            for (int i = 0; i < 4; i++) {
                if (!octets[i].matches("0|[1-9][0-9]{0,2}")) { return false; }
                ip[i] = Integer.parseInt(octets[i]);
                if (ip[i] > 255) { return false; }
            }
            return ip[0] == 10 || ip[0] == 127 || (ip[0] == 192 && ip[1] == 168)
                || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31);
        } catch (Exception e) { return false; }
    }

    private void configure() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(24, 8, 24, 8);
        final EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("门店服务，如 http://192.168.1.10:8765");
        address.setText(endpoint);
        fields.addView(address);
        final EditText secret = new EditText(this);
        secret.setSingleLine(true);
        secret.setHint("终端访问密钥（至少 24 位）");
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setText(token);
        fields.addView(secret);
        TextView note = new TextView(this);
        note.setText("请连接可信门店局域网。无外网可营业；本地服务中断时暂停交易。切换服务前请先处理页面中的待确认请求。");
        fields.addView(note);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("本地服务连接")
            .setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String next = address.getText().toString().trim();
                        String key = secret.getText().toString();
                        if (!validEndpoint(next)) { address.setError("仅支持 http://私有 IPv4:端口，不带路径"); return; }
                        if (!key.matches("[!-~]{24,256}")) { secret.setError("请输入 24 到 256 位可见 ASCII 字符"); return; }
                        // Do not send an unresolved transaction to a different shop.
                        final String chosenEndpoint = next;
                        final String chosenKey = key;
                        web.evaluateJavascript("(function(){return !!localStorage.getItem('liteshop.pending');}())",
                            new android.webkit.ValueCallback<String>() {
                                @Override public void onReceiveValue(String value) {
                                    if (!"false".equals(value) && !chosenEndpoint.equals(endpoint)) {
                                        address.setError("请先在原服务确认待处理交易"); return;
                                    }
                                    endpoint = chosenEndpoint; token = chosenKey;
                                    prefs.edit().putString("endpoint", endpoint).putString("token", token).apply();
                                    dialog.dismiss(); loadHome();
                                }
                            });
                    }
                });
            }
        });
        dialog.show();
    }

    private boolean allowed(String method, String path) {
        if (path == null || path.length() > 2048 || path.indexOf('#') >= 0) { return false; }
        if ("GET".equals(method)) {
            return path.equals("status") || path.equals("card-types")
                || path.matches("members(\\?q=[^#]*&offset=[0-9]+)?")
                || path.matches("members/[a-f0-9-]{36}");
        }
        return "POST".equals(method) && path.matches("commands/(create-member|update-member|open-card|card-status|transact|points)");
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

    private static String failure(String message) {
        try {
            return new JSONObject().put("ok", false).put("error",
                new JSONObject().put("code", "CONNECTION_FAILED").put("message", message)).toString();
        } catch (Exception e) { return "{}"; }
    }

    private void transport(String id, String method, String path, String body, String base, String key) {
        HttpURLConnection connection = null;
        try {
            if (!validEndpoint(base)) { throw new Exception("Unconfigured"); }
            connection = (HttpURLConnection) new URL(base + "/api/v1/" + path).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            if ("POST".equals(method)) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.getBytes("UTF-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                OutputStream output = connection.getOutputStream();
                try { output.write(bytes); } finally { output.close(); }
            }
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) { throw new Exception("Redirect blocked"); }
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) { throw new Exception("Empty response"); }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try {
                byte[] chunk = new byte[4096]; int n;
                while ((n = input.read(chunk)) != -1) {
                    if (buffer.size() + n > 1048576) { throw new Exception("Response too large"); }
                    buffer.write(chunk, 0, n);
                }
            } finally { input.close(); }
            deliver(id, code, buffer.toString("UTF-8"));
        } catch (Exception e) {
            deliver(id, 503, failure("无法连接本地服务，请检查连接设置和局域网；使用原请求重试"));
        } finally { if (connection != null) { connection.disconnect(); } }
    }

    public final class TerminalBridge {
        @JavascriptInterface public String getTerminalInfo() {
            try {
                return new JSONObject().put("platform", "android").put("api", Build.VERSION.SDK_INT)
                    .put("androidVersion", Build.VERSION.RELEASE).put("model", Build.MODEL)
                    .put("bridgeVersion", 1).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void reportReady(String payload) {
            if (payload == null || payload.length() > 512) { return; }
            readyAt = System.currentTimeMillis();
            runOnUiThread(new Runnable() {
                @Override public void run() { if (!destroyed) { status.setText("LiteShop · 本地页面已就绪"); } }
            });
        }
        @JavascriptInterface public String requestBackup() {
            return "{\"ok\":false,\"error\":{\"code\":\"NOT_IMPLEMENTED\",\"message\":\"本阶段尚未提供备份\"}}";
        }
        @JavascriptInterface public String requestPrint(String payload) {
            return "{\"ok\":false,\"error\":{\"code\":\"NOT_IMPLEMENTED\",\"message\":\"本阶段尚未提供打印\"}}";
        }
        @JavascriptInterface public void request(final String id, final String method, final String path, final String body) {
            if (id == null || !id.matches("[0-9-]{1,80}")) { return; }
            if (!allowed(method, path) || body == null || body.length() > 16384) {
                deliver(id, 400, "{\"ok\":false,\"error\":{\"code\":\"INVALID_INPUT\",\"message\":\"桥接请求被拒绝\"}}");
                return;
            }
            final String base = endpoint, key = token;
            try {
                worker.execute(new Runnable() {
                    @Override public void run() { transport(id, method, path, body, base, key); }
                });
            } catch (Exception e) { deliver(id, 503, failure("终端繁忙，请稍后重试原请求")); }
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
        worker.shutdownNow();
        web.removeJavascriptInterface("LiteShopTerminal");
        web.destroy();
        super.onDestroy();
    }
}
