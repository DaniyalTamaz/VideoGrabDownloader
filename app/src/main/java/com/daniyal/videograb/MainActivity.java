package com.daniyal.videograb;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private WebView webView;
    private EditText address;
    private ProgressBar progress;
    private Button mediaButton;
    private final Set<MediaCandidate> candidates = new LinkedHashSet<>();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        askNotificationPermission();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new JsBridge(), "VideoGrabBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                detect(request.getUrl().toString());
                return super.shouldInterceptRequest(view, request);
            }

            @Override public void onLoadResource(WebView view, String url) {
                detect(url);
                super.onLoadResource(view, url);
            }

            @Override public void onPageFinished(WebView view, String url) {
                address.setText(url);
                injectDetector();
                super.onPageFinished(view, url);
            }
        });

        handleIncomingIntent(getIntent());
        if (webView.getUrl() == null) loadUrl("https://www.google.com");
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        Button back = smallButton("‹");
        Button forward = smallButton("›");
        Button reload = smallButton("↻");
        address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("Website address");
        address.setTextSize(14);
        address.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button go = smallButton("Go");

        bar.addView(back); bar.addView(forward); bar.addView(reload); bar.addView(address); bar.addView(go);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(8), dp(4), dp(8), dp(8));
        mediaButton = new Button(this);
        mediaButton.setText("Videos (0)");
        Button history = new Button(this);
        history.setText("Downloads");
        bottom.addView(mediaButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        bottom.addView(history, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(bottom);
        setContentView(root);

        back.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        forward.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        reload.setOnClickListener(v -> webView.reload());
        go.setOnClickListener(v -> loadUrl(address.getText().toString()));
        address.setOnEditorActionListener((v, actionId, event) -> { loadUrl(address.getText().toString()); return true; });
        mediaButton.setOnClickListener(v -> showMedia());
        history.setOnClickListener(v -> showHistory());
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        return b;
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String url = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) url = extractUrl(text.toString());
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            url = intent.getData().toString();
        }
        if (url != null) loadUrl(url);
    }

    private String extractUrl(String text) {
        for (String token : text.split("\\s+")) {
            if (token.startsWith("https://") || token.startsWith("http://")) return token;
        }
        return null;
    }

    private void loadUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) return;
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            if (u.contains(" ") || !u.contains(".")) {
                u = "https://www.google.com/search?q=" + Uri.encode(u);
            } else {
                u = "https://" + u;
            }
        }
        candidates.clear();
        updateMediaButton();
        address.setText(u);
        webView.loadUrl(u);
    }

    private void detect(String url) {
        MediaCandidate c = MediaCandidate.fromUrl(url);
        addCandidate(c);
    }

    private void detectMediaElement(String url) {
        if (url == null) return;
        String u = url.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return;
        MediaCandidate c = MediaCandidate.fromUrl(u);
        if (c == null) c = new MediaCandidate(u, MediaCandidate.Kind.DIRECT);
        addCandidate(c);
    }

    private void addCandidate(MediaCandidate c) {
        if (c == null) return;
        synchronized (candidates) {
            if (candidates.add(c)) runOnUiThread(this::updateMediaButton);
        }
    }

    private void updateMediaButton() {
        mediaButton.setText("Videos (" + candidates.size() + ")");
    }

    private void injectDetector() {
        String js = "(function(){" +
                "if(window.__vgInstalled)return;window.__vgInstalled=true;window.__vgSeen={};" +
                "function send(u){try{if(u&&/^https?:/i.test(u)&&!window.__vgSeen[u]){window.__vgSeen[u]=1;VideoGrabBridge.found(u);}}catch(e){}}" +
                "function media(u){try{if(u&&/^https?:/i.test(u)&&!window.__vgSeen['m:'+u]){window.__vgSeen['m:'+u]=1;VideoGrabBridge.media(u);}}catch(e){}}" +
                "function scan(){" +
                "document.querySelectorAll('video,audio,source').forEach(function(x){media(x.src);media(x.currentSrc);});" +
                "try{performance.getEntriesByType('resource').forEach(function(e){send(e.name);});}catch(e){}" +
                "}" +
                "scan();setInterval(scan,1500);" +
                "var o=window.fetch;if(o)window.fetch=function(){try{send(arguments[0]&&arguments[0].url?arguments[0].url:arguments[0]);}catch(e){}return o.apply(this,arguments);};" +
                "var xo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){send(u);return xo.apply(this,arguments);};" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private void showMedia() {
        List<MediaCandidate> list;
        synchronized (candidates) { list = new ArrayList<>(candidates); }
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No video detected yet")
                    .setMessage("Start the video on this page, then wait a moment. For Chrome, use Share → VideoGrab so the page opens here and its media requests can be detected.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) labels[i] = list.get(i).displayName();
        new AlertDialog.Builder(this)
                .setTitle("Detected videos")
                .setItems(labels, (d, which) -> download(list.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void download(MediaCandidate c) {
        String ua = webView.getSettings().getUserAgentString();
        String cookie = CookieManager.getInstance().getCookie(c.url);
        String referer = webView.getUrl();
        if (c.kind == MediaCandidate.Kind.DIRECT) {
            DirectDownloader.enqueue(this, c.url, ua, cookie, referer);
            toast("Download started");
        } else if (c.kind == MediaCandidate.Kind.HLS) {
            Intent i = new Intent(this, HlsDownloadService.class);
            i.putExtra(HlsDownloadService.EXTRA_URL, c.url);
            i.putExtra(HlsDownloadService.EXTRA_COOKIE, cookie);
            i.putExtra(HlsDownloadService.EXTRA_UA, ua);
            i.putExtra(HlsDownloadService.EXTRA_REFERER, referer);
            startForegroundService(i);
            toast("HLS download started");
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("DASH detected")
                    .setMessage("This page uses an MPD/DASH stream. VideoGrab v1 detects it but does not merge separate DASH audio/video tracks into a normal file yet.")
                    .setPositiveButton("OK", null).show();
        }
    }

    private void showHistory() {
        List<String> items = DownloadHistoryStore.list(this);
        if (items.isEmpty()) { items = new ArrayList<>(); items.add("No VideoGrab downloads yet"); }
        String[] a = items.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Download history")
                .setItems(a, null)
                .setPositiveButton("Open Downloads", (d, w) -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(Uri.parse("content://com.android.providers.downloads.documents/root/downloads"), "resource/folder");
                        startActivity(i);
                    } catch (Exception e) {
                        toast("Open your phone's Files app → Downloads");
                    }
                })
                .setNegativeButton("Close", null).show();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 50);
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    public class JsBridge {
        @JavascriptInterface public void found(String url) { detect(url); }
        @JavascriptInterface public void media(String url) { detectMediaElement(url); }
    }
}
