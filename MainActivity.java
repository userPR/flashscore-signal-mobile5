package com.flashsignal.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 44);
        }

        webView = new WebView(this);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setClickable(true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            private boolean serviceStarted = false;

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!serviceStarted && url != null && url.startsWith("file:///android_asset/dashboard/")) {
                    serviceStarted = true;
                    // Let the local dashboard become responsive first; starting three WebViews
                    // at the same instant caused ANR on some Samsung/Android devices.
                    view.postDelayed(MainActivity.this::startMonitorService, 1200L);
                }
            }
        });
        webView.addJavascriptInterface(new MobileBridge(), "MobileBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/dashboard/index.html");
    }

    private void startMonitorService() {
        Intent i = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void sendCommand(String action, boolean hasValue, boolean value) {
        Intent i = new Intent(this, MonitorService.class);
        i.setAction(action);
        if (hasValue) i.putExtra(MonitorService.EXTRA_VALUE, value);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private final class MobileBridge {
        @JavascriptInterface
        public String getState() {
            return StateStore.getState(MainActivity.this);
        }

        @JavascriptInterface
        public boolean setAllMatchesMode(boolean enabled) {
            sendCommand(MonitorService.ACTION_SET_ALL, true, enabled);
            return true;
        }

        @JavascriptInterface
        public boolean setTrackingEnabled(boolean enabled) {
            sendCommand(MonitorService.ACTION_SET_ENABLED, true, enabled);
            return true;
        }

        @JavascriptInterface
        public boolean scanNow() {
            sendCommand(MonitorService.ACTION_SCAN_NOW, false, false);
            return true;
        }

        @JavascriptInterface
        public void openFlashscore() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.flashscore.com/football/"));
                startActivity(i);
            });
        }
    }
}
