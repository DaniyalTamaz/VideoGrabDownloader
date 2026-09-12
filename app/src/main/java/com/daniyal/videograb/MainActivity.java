package com.daniyal.videograb;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
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
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private WebView webView;
    private EditText address;
    private ProgressBar progress;
    private Button mediaButton;
    private TextView pageTitle;
    private final Set<MediaCandidate> candidates = new LinkedHashSet<>();

    private static class SavedVideo {
        final long id;
        final String name;
        final long size;
        final long modified;
        final String mime;
        SavedVideo(long id, String name, long size, long modified, String mime) {
            this.id = id; this.name = name; this.size = size; this.modified = modified; this.mime = mime;
        }
    }

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
            @Override public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.trim().isEmpty()) pageTitle.setText(title);
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

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 247, 250));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(12), dp(10), dp(12), dp(8));
        header.setBackgroundColor(Color.rgb(24, 28, 36));

        TextView brand = new TextView(this);
        brand.setText("VideoGrab");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(20);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(brand);

        pageTitle = new TextView(this);
        pageTitle.setText("Browser");
        pageTitle.setTextColor(Color.rgb(180, 188, 200));
        pageTitle.setTextSize(12);
        pageTitle.setSingleLine(true);
        header.addView(pageTitle);
        root.addView(header);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackgroundColor(Color.WHITE);

        Button back = navButton("‹");
        Button forward = navButton("›");
        Button reload = navButton("↻");
        address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("Search or enter website");
        address.setTextSize(14);
        address.setBackgroundColor(Color.rgb(238, 241, 245));
        address.setPadding(dp(12), 0, dp(12), 0);
        address.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button go = navButton("Go");

        nav.addView(back); nav.addView(forward); nav.addView(reload); nav.addView(address); nav.addView(go);
        root.addView(nav);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(8), dp(6), dp(8), dp(8));
        bottom.setBackgroundColor(Color.WHITE);

        mediaButton = actionButton("Detected 0");
        Button downloads = actionButton("Downloads");
        bottom.addView(mediaButton, new LinearLayout.LayoutParams(0, dp(54), 1f));
        bottom.addView(downloads, new LinearLayout.LayoutParams(0, dp(54), 1f));
        root.addView(bottom);
        setContentView(root);

        back.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        forward.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        reload.setOnClickListener(v -> webView.reload());
        go.setOnClickListener(v -> loadUrl(address.getText().toString()));
        address.setOnEditorActionListener((v, actionId, event) -> { loadUrl(address.getText().toString()); return true; });
        mediaButton.setOnClickListener(v -> showMedia());
        downloads.setOnClickListener(v -> showDownloads());
    }

    private Button navButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)));
        return b;
    }

    private Button actionButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
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
            if (u.contains(" ") || !u.contains(".")) u = "https://www.google.com/search?q=" + Uri.encode(u);
            else u = "https://" + u;
        }
        candidates.clear();
        updateMediaButton();
        address.setText(u);
        webView.loadUrl(u);
    }

    private void detect(String url) { addCandidate(MediaCandidate.fromUrl(url)); }

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

    private void updateMediaButton() { mediaButton.setText("Detected " + candidates.size()); }

    private void injectDetector() {
        String js = "(function(){" +
                "if(window.__vgInstalled)return;window.__vgInstalled=true;window.__vgSeen={};" +
                "function send(u){try{if(u&&/^https?:/i.test(u)&&!window.__vgSeen[u]){window.__vgSeen[u]=1;VideoGrabBridge.found(u);}}catch(e){}}" +
                "function media(u){try{if(u&&/^https?:/i.test(u)&&!window.__vgSeen['m:'+u]){window.__vgSeen['m:'+u]=1;VideoGrabBridge.media(u);}}catch(e){}}" +
                "function scan(){document.querySelectorAll('video,audio,source').forEach(function(x){media(x.src);media(x.currentSrc);});try{performance.getEntriesByType('resource').forEach(function(e){send(e.name);});}catch(e){}}" +
                "scan();setInterval(scan,1500);" +
                "var o=window.fetch;if(o)window.fetch=function(){try{send(arguments[0]&&arguments[0].url?arguments[0].url:arguments[0]);}catch(e){}return o.apply(this,arguments);};" +
                "var xo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){send(u);return xo.apply(this,arguments);};" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private boolean isYouTubePage() {
        String u = webView.getUrl();
        if (u == null) return false;
        String x = u.toLowerCase();
        return x.contains("youtube.com") || x.contains("youtu.be");
    }

    private void showMedia() {
        List<MediaCandidate> list;
        synchronized (candidates) { list = new ArrayList<>(candidates); }
        if (list.isEmpty()) {
            String msg = isYouTubePage()
                    ? "YouTube normally plays signed, segmented MediaSource streams instead of exposing a normal MP4/HLS file to the page. VideoGrab does not bypass YouTube signatures, DRM, or access protections."
                    : "Start the video on this page and wait a moment. VideoGrab can detect ordinary MP4/WebM files and unencrypted HLS streams, but some sites use protected or blob-based streaming.";
            new AlertDialog.Builder(this).setTitle("No downloadable video found").setMessage(msg).setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) labels[i] = list.get(i).displayName();
        new AlertDialog.Builder(this)
                .setTitle("Detected media")
                .setItems(labels, (d, which) -> download(list.get(which)))
                .setNegativeButton("Close", null).show();
    }

    private void download(MediaCandidate c) {
        String ua = webView.getSettings().getUserAgentString();
        String cookie = CookieManager.getInstance().getCookie(c.url);
        String referer = webView.getUrl();
        if (c.kind == MediaCandidate.Kind.DIRECT) {
            DirectDownloader.enqueue(this, c.url, ua, cookie, referer);
            toast("Download started • open Downloads tab to view it");
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
                    .setTitle("DASH stream detected")
                    .setMessage("This page uses separate DASH audio/video tracks. This version does not merge those tracks into one file.")
                    .setPositiveButton("OK", null).show();
        }
    }

    private List<SavedVideo> querySavedVideos() {
        List<SavedVideo> out = new ArrayList<>();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED,
                MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads.RELATIVE_PATH
        };
        String selection = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
        String[] args = new String[]{"Download/VideoGrab%"};
        try (Cursor c = getContentResolver().query(collection, projection, selection, args, MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
            if (c == null) return out;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
            int sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE);
            int dateCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED);
            int mimeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE);
            while (c.moveToNext()) {
                out.add(new SavedVideo(c.getLong(idCol), c.getString(nameCol), c.getLong(sizeCol), c.getLong(dateCol), c.getString(mimeCol)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void showDownloads() {
        List<SavedVideo> items = querySavedVideos();
        if (items.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Downloads")
                    .setMessage("No completed VideoGrab files are visible yet. Active downloads may still be running in Android's download service.")
                    .setPositiveButton("Refresh", (d, w) -> showDownloads())
                    .setNegativeButton("Close", null).show();
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            SavedVideo v = items.get(i);
            String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(v.modified * 1000L));
            labels[i] = v.name + "\n" + readableSize(v.size) + " • " + when;
        }
        new AlertDialog.Builder(this)
                .setTitle("Downloads • " + items.size())
                .setItems(labels, (d, which) -> openSavedVideo(items.get(which)))
                .setPositiveButton("Refresh", (d, w) -> showDownloads())
                .setNegativeButton("Close", null).show();
    }

    private void openSavedVideo(SavedVideo v) {
        Uri uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v.id);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, v.mime == null ? "video/*" : v.mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); } catch (Exception e) { toast("No app found to open this file"); }
    }

    private String readableSize(long n) {
        if (n < 1024) return n + " B";
        double kb = n / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb);
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
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
