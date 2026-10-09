package com.pbisllc.pos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen PBIS POS (or KDS / customer display) with the print bridge built in.
 * The web app prints through http://127.0.0.1:17788 exactly like on a PC with the
 * desktop bridge — set the printer connection to "PBIS Print Bridge — this device".
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 41;
    private WebView web;
    private Prefs prefs;
    private ValueCallback<Uri[]> fileCallback;

    @SuppressLint({ "SetJavaScriptEnabled", "AddJavascriptInterface" })
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        BridgeService.start(this);
        askPermissions();
        new Printers(this).requestAllUsbPermissions();

        if (prefs.startUrl().isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class).putExtra("first_run", true));
            finish();
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " PBISAndroid/" + BridgeService.VERSION);
        s.setAllowFileAccess(false);
        WebView.setWebContentsDebuggingEnabled(true);
        web.addJavascriptInterface(new Bridge(), "PBISAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (scheme.equals("http") || scheme.equals("https")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true; // tel:, mailto:, intent:, starpassprnt:, rawbt: …
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); } catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });
        if (state != null) web.restoreState(state); else web.loadUrl(prefs.startUrl());
        immersive();
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!need.isEmpty() && Build.VERSION.SDK_INT >= 23) requestPermissions(need.toArray(new String[0]), 7);
    }

    private void immersive() {
        if (!prefs.kiosk()) return;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) immersive(); }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        new Printers(this).requestAllUsbPermissions(); // a printer was plugged in
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); if (web != null) web.saveState(out); }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    /** window.PBISAndroid inside the web app. */
    final class Bridge {
        @JavascriptInterface public String info() {
            try {
                JSONObject o = new JSONObject();
                o.put("app", "pbis-android");
                o.put("version", BridgeService.VERSION);
                o.put("bridge", "http://127.0.0.1:17788");
                o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
                o.put("builtin", prefs.builtin());
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void openSettings() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        }
        @JavascriptInterface public void reload() { runOnUiThread(() -> web.reload()); }
    }
}
