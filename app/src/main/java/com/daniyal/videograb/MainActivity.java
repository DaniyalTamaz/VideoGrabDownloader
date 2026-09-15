package com.daniyal.videograb;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.net.URI;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_SCREEN = "screen";

    private DrawerLayout drawer;
    private MaterialToolbar toolbar;
    private FrameLayout container;
    private BottomNavigationView bottomNav;
    private NavigationView drawerNav;
    private int currentScreen = R.id.nav_home;

    private View browserView;
    private WebView webView;
    private EditText address;
    private ProgressBar browserProgress;
    private TextView browserStatus;
    private ExtendedFloatingActionButton detectFab;
    private String pageTitle = "Browser";
    private final Map<String, MediaCandidate> candidates = new LinkedHashMap<>();

    private LinearLayout downloadList;
    private TextView downloadSummary;
    private String downloadFilter = "all";
    private LinearLayout libraryList;
    private TextView librarySummary;
    private String libraryFilter = "all";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable downloadRefresh = new Runnable() {
        @Override public void run() {
            if (currentScreen == R.id.nav_downloads) {
                renderDownloadList();
                handler.postDelayed(this, 1100);
            }
        }
    };

    private interface IndexAction { void run(int index); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        AppPrefs.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        drawer = findViewById(R.id.drawer_layout);
        toolbar = findViewById(R.id.toolbar);
        container = findViewById(R.id.screen_container);
        bottomNav = findViewById(R.id.bottom_nav);
        drawerNav = findViewById(R.id.drawer_nav);

        toolbar.setNavigationOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        bottomNav.setOnItemSelectedListener(item -> {
            showScreen(item.getItemId());
            return true;
        });
        drawerNav.setNavigationItemSelectedListener(item -> {
            drawer.closeDrawer(GravityCompat.START);
            bottomNav.setSelectedItemId(item.getItemId());
            return true;
        });

        askNotificationPermission();
        String requested = getIntent().getStringExtra(EXTRA_SCREEN);
        if ("downloads".equals(requested)) bottomNav.setSelectedItemId(R.id.nav_downloads);
        else bottomNav.setSelectedItemId(R.id.nav_home);
        handleIncomingIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (currentScreen == R.id.nav_home) renderHomeStats();
        if (currentScreen == R.id.nav_library) renderLibrary();
    }

    private void showScreen(int id) {
        currentScreen = id;
        handler.removeCallbacks(downloadRefresh);
        container.removeAllViews();
        drawerNav.setCheckedItem(id);
        if (id == R.id.nav_browser) showBrowser();
        else if (id == R.id.nav_downloads) showDownloads();
        else if (id == R.id.nav_library) showLibrary();
        else if (id == R.id.nav_settings) showSettings();
        else showHome();
    }

    private void showHome() {
        toolbar.setTitle("VideoGrab");
        toolbar.setSubtitle("Smart media downloader");
        View v = getLayoutInflater().inflate(R.layout.screen_home, container, false);
        container.addView(v);
        v.findViewById(R.id.home_browse).setOnClickListener(x -> bottomNav.setSelectedItemId(R.id.nav_browser));
        v.findViewById(R.id.home_paste).setOnClickListener(x -> pasteLink());
        renderHomeStats();
    }

    private void renderHomeStats() {
        if (currentScreen != R.id.nav_home || container.getChildCount() == 0) return;
        View root = container.getChildAt(0);
        TextView activeView = root.findViewById(R.id.home_active_count);
        TextView doneView = root.findViewById(R.id.home_done_count);
        TextView libraryView = root.findViewById(R.id.home_library_count);
        TextView smartView = root.findViewById(R.id.home_smart_status);
        LinearLayout recent = root.findViewById(R.id.home_recent_container);
        if (activeView == null) return;

        List<DownloadTask> tasks = DownloadTaskStore.list(this);
        int active = 0, done = 0;
        for (DownloadTask t : tasks) {
            if (isActive(t)) active++;
            if (DownloadTask.STATUS_COMPLETED.equals(t.status)) done++;
        }
        activeView.setText(String.valueOf(active));
        doneView.setText(String.valueOf(done));
        libraryView.setText(String.valueOf(LibraryRepository.list(this).size()));
        smartView.setText(AppPrefs.hideSmall(this)
                ? "On • likely ads and tiny clips are filtered from the main list"
                : "Off • all detected media is shown");

        recent.removeAllViews();
        if (tasks.isEmpty()) {
            recent.addView(emptyState("No download activity yet", "Open the browser, play a video, then tap Detect media."));
        } else {
            int max = Math.min(3, tasks.size());
            for (int i = 0; i < max; i++) recent.addView(recentTaskCard(tasks.get(i)));
            MaterialButton all = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            all.setText("Open download manager");
            all.setAllCaps(false);
            all.setOnClickListener(x -> bottomNav.setSelectedItemId(R.id.nav_downloads));
            recent.addView(all, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 48)));
        }
    }

    private View recentTaskCard(DownloadTask t) {
        MaterialCardView card = UiKit.card(this);
        LinearLayout box = verticalBox(14);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = UiKit.title(this, t.name, 14);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(statusBadge(t));
        box.addView(top);
        TextView meta = UiKit.muted(this, taskType(t) + " • " + t.shortStatus(), 12);
        meta.setPadding(0, UiKit.dp(this, 5), 0, 0);
        box.addView(meta);
        card.addView(box);
        card.setOnClickListener(v -> bottomNav.setSelectedItemId(R.id.nav_downloads));
        return card;
    }

    private void pasteLink() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData data = cm == null ? null : cm.getPrimaryClip();
        String value = data != null && data.getItemCount() > 0 ? String.valueOf(data.getItemAt(0).coerceToText(this)).trim() : "";
        if (value.startsWith("http://") || value.startsWith("https://")) {
            bottomNav.setSelectedItemId(R.id.nav_browser);
            loadUrl(value);
        } else {
            Snackbar.make(container, "Clipboard does not contain a web link", Snackbar.LENGTH_LONG).show();
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void ensureBrowser() {
        if (browserView != null) return;
        browserView = getLayoutInflater().inflate(R.layout.screen_browser, container, false);
        webView = browserView.findViewById(R.id.browser_webview);
        address = browserView.findViewById(R.id.browser_address);
        browserProgress = browserView.findViewById(R.id.browser_progress);
        browserStatus = browserView.findViewById(R.id.browser_status);
        detectFab = browserView.findViewById(R.id.detect_fab);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new JsBridge(), "VideoGrabBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                browserProgress.setProgress(newProgress);
                browserProgress.setVisibility(newProgress >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
            @Override public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.trim().isEmpty()) {
                    pageTitle = title.trim();
                    if (currentScreen == R.id.nav_browser) toolbar.setSubtitle(trim(pageTitle, 52));
                }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                synchronized (candidates) { candidates.clear(); }
                updateBrowserStatus();
                super.onPageStarted(view, url, favicon);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                detectNetwork(request.getUrl().toString());
                return super.shouldInterceptRequest(view, request);
            }
            @Override public void onLoadResource(WebView view, String url) {
                detectNetwork(url);
                super.onLoadResource(view, url);
            }
            @Override public void onPageFinished(WebView view, String url) {
                address.setText(url);
                injectDetector();
                super.onPageFinished(view, url);
            }
        });

        browserView.findViewById(R.id.browser_back).setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        browserView.findViewById(R.id.browser_forward).setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        browserView.findViewById(R.id.browser_reload).setOnClickListener(v -> webView.reload());
        browserView.findViewById(R.id.browser_go).setOnClickListener(v -> loadUrl(address.getText().toString()));
        address.setOnEditorActionListener((v, actionId, event) -> { loadUrl(address.getText().toString()); return true; });
        detectFab.setOnClickListener(v -> showMediaSheet(false));
        browserStatus.setOnClickListener(v -> showMediaSheet(false));
        if (webView.getUrl() == null) webView.loadUrl("https://www.google.com");
    }

    private void showBrowser() {
        ensureBrowser();
        if (browserView.getParent() instanceof ViewGroup) ((ViewGroup) browserView.getParent()).removeView(browserView);
        container.addView(browserView);
        toolbar.setTitle("Browser");
        toolbar.setSubtitle(pageTitle.equals("Browser") ? "Play a video, then detect media" : trim(pageTitle, 52));
        updateBrowserStatus();
    }

    private void loadUrl(String raw) {
        ensureBrowser();
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) return;
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            if (u.contains(" ") || !u.contains(".")) u = "https://www.google.com/search?q=" + Uri.encode(u);
            else u = "https://" + u;
        }
        synchronized (candidates) { candidates.clear(); }
        updateBrowserStatus();
        address.setText(u);
        webView.loadUrl(u);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String u = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                for (String token : text.toString().split("\\s+")) {
                    if (token.startsWith("https://") || token.startsWith("http://")) { u = token; break; }
                }
            }
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            u = intent.getData().toString();
        }
        if (u != null) {
            bottomNav.setSelectedItemId(R.id.nav_browser);
            loadUrl(u);
        }
    }

    private void detectNetwork(String u) { addCandidate(MediaCandidate.fromUrl(u)); }

    private void detectMediaElement(String u, int width, int height, double duration, boolean playing) {
        if (u == null || (!u.startsWith("http://") && !u.startsWith("https://"))) return;
        MediaCandidate c = MediaCandidate.fromUrl(u);
        if (c == null) c = new MediaCandidate(u, MediaCandidate.Kind.DIRECT);
        c.fromMediaElement = true;
        c.width = width;
        c.height = height;
        c.duration = duration;
        c.playing = playing;
        addCandidate(c);
    }

    private void addCandidate(MediaCandidate c) {
        if (c == null) return;
        synchronized (candidates) {
            String key = c.canonicalKey();
            MediaCandidate old = candidates.get(key);
            if (old == null) candidates.put(key, c);
            else old.mergeFrom(c);
        }
        runOnUiThread(this::updateBrowserStatus);
    }

    private void updateBrowserStatus() {
        if (browserStatus == null) return;
        int count;
        synchronized (candidates) { count = candidates.size(); }
        String filter = AppPrefs.hideSmall(this) ? "Smart filter on" : "Showing all media";
        browserStatus.setText(count + (count == 1 ? " media source" : " media sources") + " detected • " + filter);
    }

    private void injectDetector() {
        if (webView == null) return;
        String js = "(function(){if(window.__vgV5)return;window.__vgV5=1;window.__vgSeen={};" +
                "function net(u){try{if(u&&/^https?:/i.test(u)&&!window.__vgSeen['n:'+u]){window.__vgSeen['n:'+u]=1;VideoGrabBridge.found(u);}}catch(e){}}" +
                "function media(v,u){try{if(!u||!/^https?:/i.test(u))return;var w=v.videoWidth||v.clientWidth||0;var h=v.videoHeight||v.clientHeight||0;var d=isFinite(v.duration)?v.duration:0;var p=!!(!v.paused&&!v.ended&&v.readyState>2);VideoGrabBridge.media(u,w,h,d,p);}catch(e){}}" +
                "function scan(){document.querySelectorAll('video').forEach(function(v){media(v,v.currentSrc||v.src);v.querySelectorAll('source').forEach(function(s){media(v,s.src);});});document.querySelectorAll('audio').forEach(function(v){media(v,v.currentSrc||v.src);});try{performance.getEntriesByType('resource').forEach(function(e){net(e.name);});}catch(e){}}" +
                "scan();setInterval(scan,1200);var f=window.fetch;if(f)window.fetch=function(){try{net(arguments[0]&&arguments[0].url?arguments[0].url:arguments[0]);}catch(e){}return f.apply(this,arguments);};var o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){net(u);return o.apply(this,arguments);};})();";
        webView.evaluateJavascript(js, null);
    }

    private List<MediaCandidate> sortedCandidates(boolean showAll) {
        String host = pageHost();
        List<MediaCandidate> all;
        synchronized (candidates) { all = new ArrayList<>(candidates.values()); }
        all.sort((a, b) -> Integer.compare(b.score(host), a.score(host)));
        if (showAll || !AppPrefs.hideSmall(this) || all.size() <= 1) return all;
        List<MediaCandidate> clean = new ArrayList<>();
        for (MediaCandidate c : all) if (!isLikelyNoise(c) || c.playing) clean.add(c);
        if (clean.isEmpty() && !all.isEmpty()) clean.add(all.get(0));
        return clean;
    }

    private boolean isLikelyNoise(MediaCandidate c) {
        String x = c.url.toLowerCase(Locale.ROOT);
        if (x.contains("doubleclick") || x.contains("/ads/") || x.contains("/ad/") || x.contains("preroll") || x.contains("vast") || x.contains("promo")) return true;
        long area = (long) c.width * c.height;
        if (area > 0 && area <= 320L * 180L && !c.playing) return true;
        return c.duration > 0 && c.duration < 12 && !c.playing;
    }

    private void showMediaSheet(boolean showAll) {
        List<MediaCandidate> all;
        synchronized (candidates) { all = new ArrayList<>(candidates.values()); }
        List<MediaCandidate> visible = sortedCandidates(showAll);
        if (all.isEmpty()) {
            String u = webView == null || webView.getUrl() == null ? "" : webView.getUrl().toLowerCase(Locale.ROOT);
            String msg = (u.contains("youtube.com") || u.contains("youtu.be"))
                    ? "YouTube does not expose a normal downloadable stream to VideoGrab. VideoGrab does not bypass signed-stream, DRM, or access protections."
                    : "Start the main video and let it play for a few seconds, then tap Detect media again.";
            new MaterialAlertDialogBuilder(this).setTitle("No supported media found").setMessage(msg).setPositiveButton("OK", null).show();
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout root = verticalBox(20);
        root.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 10), UiKit.dp(this, 20), UiKit.dp(this, 20));
        root.addView(UiKit.title(this, "Detected media", 22));
        String line = visible.size() + " shown" + (visible.size() < all.size() ? " • " + (all.size() - visible.size()) + " filtered" : "") + " • tap a source to analyze";
        TextView hint = UiKit.muted(this, line, 12);
        hint.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 12));
        root.addView(hint);
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = verticalBox(0);
        scroll.addView(list);
        for (int i = 0; i < visible.size(); i++) {
            MediaCandidate c = visible.get(i);
            boolean recommended = i == 0;
            View card = candidateCard(c, recommended);
            card.setOnClickListener(v -> { dialog.dismiss(); inspectCandidate(c); });
            list.addView(card);
        }
        root.addView(scroll, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 430)));
        if (!showAll && visible.size() < all.size()) {
            MaterialButton show = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            show.setText("Show all " + all.size() + " detected sources");
            show.setAllCaps(false);
            show.setOnClickListener(v -> { dialog.dismiss(); showMediaSheet(true); });
            root.addView(show, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 50)));
        }
        dialog.setContentView(root);
        dialog.show();
    }

    private View candidateCard(MediaCandidate c, boolean recommended) {
        MaterialCardView card = UiKit.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 14), UiKit.dp(this, 14), UiKit.dp(this, 14));

        TextView kind = UiKit.badge(this, c.kind.name(), R.color.vg_primary);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(UiKit.dp(this, 64), UiKit.dp(this, 42));
        kp.setMargins(0, 0, UiKit.dp(this, 12), 0);
        row.addView(kind, kp);

        LinearLayout info = verticalBox(0);
        StringBuilder headline = new StringBuilder();
        if (recommended) headline.append("Likely main video");
        else headline.append(c.kind == MediaCandidate.Kind.DIRECT ? "Direct media" : c.kind.name() + " stream");
        TextView title = UiKit.title(this, headline.toString(), 14);
        info.addView(title);
        StringBuilder meta = new StringBuilder();
        if (c.width > 0 && c.height > 0) meta.append(c.width).append('×').append(c.height);
        String dur = UiKit.duration(c.duration);
        if (!dur.isEmpty()) { if (meta.length() > 0) meta.append(" • "); meta.append(dur); }
        if (c.playing) { if (meta.length() > 0) meta.append(" • "); meta.append("Playing now"); }
        String host = hostOf(c.url);
        if (!host.isEmpty()) { if (meta.length() > 0) meta.append("\n"); meta.append(host); }
        TextView sub = UiKit.muted(this, meta.length() == 0 ? hostOf(c.url) : meta.toString(), 12);
        sub.setPadding(0, UiKit.dp(this, 4), 0, 0);
        info.addView(sub);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        if (recommended) row.addView(UiKit.badge(this, "BEST", R.color.vg_success));
        card.addView(row);
        return card;
    }

    private void inspectCandidate(MediaCandidate c) {
        String ua = webView.getSettings().getUserAgentString();
        String cookie = CookieManager.getInstance().getCookie(c.url);
        String ref = webView.getUrl();
        if (c.kind == MediaCandidate.Kind.DIRECT) {
            showOutputSheet(c.url, DownloadTask.KIND_DIRECT, "Original", ua, cookie, ref);
        } else if (c.kind == MediaCandidate.Kind.HLS) {
            analyzeHls(c.url, ua, cookie, ref);
        } else {
            analyzeDash(c.url, ua, cookie, ref);
        }
    }

    private void analyzeHls(String url, String ua, String cookie, String ref) {
        AlertDialog loading = loadingDialog("Analyzing HLS", "Checking available qualities…");
        loading.show();
        new Thread(() -> {
            try {
                List<HlsInspector.Option> options = HlsInspector.inspect(url, cookie, ua, ref);
                runOnUiThread(() -> {
                    loading.dismiss();
                    List<String> labels = new ArrayList<>();
                    for (HlsInspector.Option o : options) labels.add(o.label);
                    showChoiceSheet("Choose video quality", "HLS stream", labels, i -> showOutputSheet(options.get(i).url, DownloadTask.KIND_HLS, options.get(i).label, ua, cookie, ref));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading.dismiss();
                    String msg = e.getMessage() == null ? "Could not read this HLS stream." : e.getMessage();
                    MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this).setTitle("HLS could not be opened").setMessage(msg);
                    if (msg.contains("404")) b.setPositiveButton("Rescan page", (d, w) -> rescanPage());
                    else b.setPositiveButton("OK", null);
                    b.show();
                });
            }
        }, "vg-hls-inspect").start();
    }

    private void analyzeDash(String url, String ua, String cookie, String ref) {
        AlertDialog loading = loadingDialog("Analyzing DASH", "Finding video and audio tracks…");
        loading.show();
        new Thread(() -> {
            try {
                List<DashInspector.Option> options = DashInspector.inspect(url, cookie, ua, ref);
                runOnUiThread(() -> {
                    loading.dismiss();
                    List<String> labels = new ArrayList<>();
                    for (DashInspector.Option o : options) labels.add(o.label);
                    showChoiceSheet("Choose DASH quality", "Video + best matching audio will be merged", labels, i -> showDashOutputSheet(url, options.get(i), ua, cookie, ref));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading.dismiss();
                    new MaterialAlertDialogBuilder(this).setTitle("DASH not supported").setMessage(e.getMessage() == null ? "Could not inspect this DASH manifest." : e.getMessage()).setPositiveButton("OK", null).show();
                });
            }
        }, "vg-dash-inspect").start();
    }

    private AlertDialog loadingDialog(String title, String message) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 10), UiKit.dp(this, 8), UiKit.dp(this, 10));
        ProgressBar p = new ProgressBar(this);
        box.addView(p, new LinearLayout.LayoutParams(UiKit.dp(this, 42), UiKit.dp(this, 42)));
        TextView t = UiKit.muted(this, message, 13);
        t.setPadding(UiKit.dp(this, 14), 0, 0, 0);
        box.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        return new MaterialAlertDialogBuilder(this).setTitle(title).setView(box).setCancelable(false).create();
    }

    private void showChoiceSheet(String title, String subtitle, List<String> labels, IndexAction action) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout root = verticalBox(20);
        root.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 10), UiKit.dp(this, 20), UiKit.dp(this, 24));
        root.addView(UiKit.title(this, title, 21));
        TextView sub = UiKit.muted(this, subtitle, 12);
        sub.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 12));
        root.addView(sub);
        for (int i = 0; i < labels.size(); i++) {
            final int index = i;
            MaterialCardView card = UiKit.card(this);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 14), UiKit.dp(this, 16), UiKit.dp(this, 14));
            TextView q = UiKit.title(this, labels.get(i), 15);
            row.addView(q, new LinearLayout.LayoutParams(0, -2, 1));
            TextView arrow = UiKit.muted(this, "›", 24);
            row.addView(arrow);
            card.addView(row);
            card.setOnClickListener(v -> { dialog.dismiss(); action.run(index); });
            root.addView(card);
        }
        dialog.setContentView(root);
        dialog.show();
    }

    private void showOutputSheet(String url, String kind, String quality, String ua, String cookie, String ref) {
        int defaultMp3 = AppPrefs.mp3(this);
        List<String> labels = new ArrayList<>();
        labels.add("Video • " + quality);
        labels.add("MP3 • 128 kbps" + (defaultMp3 == 128 ? " • Default" : ""));
        labels.add("MP3 • 192 kbps" + (defaultMp3 == 192 ? " • Default" : ""));
        labels.add("MP3 • 320 kbps" + (defaultMp3 == 320 ? " • Default" : ""));
        showChoiceSheet("Download as", "Saved to Downloads/VideoGrab", labels, index -> {
            DownloadTask t = new DownloadTask();
            t.url = url; t.kind = kind; t.quality = quality; t.userAgent = safe(ua); t.cookie = safe(cookie); t.referer = safe(ref); t.name = downloadTitle(url);
            if (index > 0) { t.mode = DownloadTask.MODE_MP3; t.mp3Bitrate = index == 1 ? 128 : index == 2 ? 192 : 320; }
            enqueue(t);
        });
    }

    private void showDashOutputSheet(String url, DashInspector.Option option, String ua, String cookie, String ref) {
        int defaultMp3 = AppPrefs.mp3(this);
        List<String> labels = new ArrayList<>();
        labels.add("Video • " + option.label);
        labels.add("MP3 • 128 kbps" + (defaultMp3 == 128 ? " • Default" : ""));
        labels.add("MP3 • 192 kbps" + (defaultMp3 == 192 ? " • Default" : ""));
        labels.add("MP3 • 320 kbps" + (defaultMp3 == 320 ? " • Default" : ""));
        showChoiceSheet("Download as", "DASH audio/video will be processed locally", labels, index -> {
            if (index > 0 && option.audioStream < 0) { Toast.makeText(this, "No audio track is available for MP3", Toast.LENGTH_LONG).show(); return; }
            DownloadTask t = new DownloadTask();
            t.url = url; t.kind = DownloadTask.KIND_DASH; t.quality = option.label; t.userAgent = safe(ua); t.cookie = safe(cookie); t.referer = safe(ref); t.name = downloadTitle(url); t.dashVideoStream = option.videoStream; t.dashAudioStream = option.audioStream;
            if (index > 0) { t.mode = DownloadTask.MODE_MP3; t.mp3Bitrate = index == 1 ? 128 : index == 2 ? 192 : 320; }
            enqueue(t);
        });
    }

    private void enqueue(DownloadTask t) {
        DownloadQueueService.enqueue(this, t);
        Snackbar.make(container, "Added to download queue", Snackbar.LENGTH_LONG).setAction("VIEW", v -> bottomNav.setSelectedItemId(R.id.nav_downloads)).show();
    }

    private void rescanPage() {
        synchronized (candidates) { candidates.clear(); }
        injectDetector();
        updateBrowserStatus();
        Snackbar.make(container, "Play the main video for a few seconds, then detect again", Snackbar.LENGTH_LONG).show();
    }

    private String downloadTitle(String url) {
        String t = pageTitle == null ? "" : pageTitle.trim();
        if (t.isEmpty() || "Browser".equals(t)) t = android.webkit.URLUtil.guessFileName(url, null, null);
        t = MediaPublisher.stripExtension(t);
        return MediaPublisher.sanitize(trim(t, 80));
    }

    private void showDownloads() {
        toolbar.setTitle("Downloads");
        toolbar.setSubtitle("Queue, progress, retries and completed jobs");
        View v = getLayoutInflater().inflate(R.layout.screen_downloads, container, false);
        container.addView(v);
        downloadList = v.findViewById(R.id.download_list);
        downloadSummary = v.findViewById(R.id.download_summary);
        v.findViewById(R.id.filter_all).setOnClickListener(x -> { downloadFilter = "all"; renderDownloadList(); });
        v.findViewById(R.id.filter_active).setOnClickListener(x -> { downloadFilter = "active"; renderDownloadList(); });
        v.findViewById(R.id.filter_finished).setOnClickListener(x -> { downloadFilter = "finished"; renderDownloadList(); });
        v.findViewById(R.id.filter_failed).setOnClickListener(x -> { downloadFilter = "failed"; renderDownloadList(); });
        renderDownloadList();
        handler.postDelayed(downloadRefresh, 1100);
    }

    private void renderDownloadList() {
        if (downloadList == null || currentScreen != R.id.nav_downloads) return;
        List<DownloadTask> tasks = DownloadTaskStore.list(this);
        int active = 0, done = 0, failed = 0;
        for (DownloadTask t : tasks) {
            if (isActive(t)) active++;
            else if (DownloadTask.STATUS_COMPLETED.equals(t.status)) done++;
            else if (DownloadTask.STATUS_FAILED.equals(t.status)) failed++;
        }
        downloadSummary.setText(active + " active • " + done + " completed • " + failed + " failed");
        downloadList.removeAllViews();
        int shown = 0;
        for (DownloadTask t : tasks) {
            if (!matchesDownloadFilter(t)) continue;
            downloadList.addView(downloadTaskCard(t));
            shown++;
        }
        if (shown == 0) downloadList.addView(emptyState("Nothing here yet", downloadFilter.equals("all") ? "Downloads you start in the browser will appear here." : "No downloads match this filter."));
    }

    private boolean matchesDownloadFilter(DownloadTask t) {
        if ("active".equals(downloadFilter)) return isActive(t);
        if ("finished".equals(downloadFilter)) return DownloadTask.STATUS_COMPLETED.equals(t.status);
        if ("failed".equals(downloadFilter)) return DownloadTask.STATUS_FAILED.equals(t.status);
        return true;
    }

    private boolean isActive(DownloadTask t) {
        return DownloadTask.STATUS_QUEUED.equals(t.status) || DownloadTask.STATUS_DOWNLOADING.equals(t.status) || DownloadTask.STATUS_PAUSED.equals(t.status);
    }

    private View downloadTaskCard(DownloadTask t) {
        MaterialCardView card = UiKit.card(this);
        LinearLayout box = verticalBox(16);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.TOP);
        ImageView icon = new ImageView(this);
        icon.setImageResource(DownloadTask.MODE_MP3.equals(t.mode) ? R.drawable.ic_music : R.drawable.ic_video);
        icon.setColorFilter(ContextCompat.getColor(this, R.color.vg_primary));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(UiKit.dp(this, 42), UiKit.dp(this, 42));
        ip.setMargins(0, 0, UiKit.dp(this, 12), 0);
        top.addView(icon, ip);
        LinearLayout info = verticalBox(0);
        TextView name = UiKit.title(this, t.name, 15);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);
        TextView meta = UiKit.muted(this, taskType(t) + " • " + t.shortStatus(), 12);
        meta.setPadding(0, UiKit.dp(this, 4), 0, 0);
        info.addView(meta);
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(statusBadge(t));
        box.addView(top);

        LinearProgressIndicator progress = new LinearProgressIndicator(this);
        progress.setMax(100);
        progress.setProgressCompat(t.progress, false);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, UiKit.dp(this, 6));
        pp.setMargins(0, UiKit.dp(this, 14), 0, UiKit.dp(this, 8));
        box.addView(progress, pp);

        String amount = t.total > 0 ? UiKit.bytes(t.downloaded) + " / " + UiKit.bytes(t.total) : t.downloaded > 0 ? UiKit.bytes(t.downloaded) : t.progress + "%";
        box.addView(UiKit.muted(this, amount, 12));
        if (DownloadTask.STATUS_FAILED.equals(t.status) && t.error != null && !t.error.isEmpty()) {
            TextView err = UiKit.text(this, t.error, 12, R.color.vg_error);
            err.setPadding(0, UiKit.dp(this, 8), 0, 0);
            box.addView(err);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, UiKit.dp(this, 10), 0, 0);
        MaterialButton primary = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle);
        primary.setText(taskAction(t));
        primary.setAllCaps(false);
        primary.setOnClickListener(v -> actOnTask(t));
        MaterialButton remove = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        remove.setText("Remove");
        remove.setAllCaps(false);
        remove.setOnClickListener(v -> removeTask(t));
        actions.addView(primary, new LinearLayout.LayoutParams(0, UiKit.dp(this, 46), 1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 46), 1);
        rp.setMargins(UiKit.dp(this, 8), 0, 0, 0);
        actions.addView(remove, rp);
        box.addView(actions);
        card.addView(box);
        return card;
    }

    private TextView statusBadge(DownloadTask t) {
        int color = R.color.vg_primary;
        String label = "QUEUED";
        if (DownloadTask.STATUS_COMPLETED.equals(t.status)) { color = R.color.vg_success; label = "DONE"; }
        else if (DownloadTask.STATUS_FAILED.equals(t.status)) { color = R.color.vg_error; label = "FAILED"; }
        else if (DownloadTask.STATUS_PAUSED.equals(t.status)) { color = R.color.vg_warning; label = "PAUSED"; }
        else if (DownloadTask.STATUS_DOWNLOADING.equals(t.status)) label = "ACTIVE";
        else if (DownloadTask.STATUS_CANCELED.equals(t.status)) { color = R.color.vg_text_muted; label = "CANCELED"; }
        return UiKit.badge(this, label, color);
    }

    private String taskAction(DownloadTask t) {
        if (DownloadTask.STATUS_DOWNLOADING.equals(t.status) || DownloadTask.STATUS_QUEUED.equals(t.status)) return "Pause";
        if (DownloadTask.STATUS_PAUSED.equals(t.status)) return "Resume";
        if (DownloadTask.STATUS_COMPLETED.equals(t.status)) return "Play";
        return "Retry";
    }

    private void actOnTask(DownloadTask t) {
        if (DownloadTask.STATUS_DOWNLOADING.equals(t.status) || DownloadTask.STATUS_QUEUED.equals(t.status)) DownloadQueueService.pause(this, t.id);
        else if (DownloadTask.STATUS_PAUSED.equals(t.status) || DownloadTask.STATUS_FAILED.equals(t.status) || DownloadTask.STATUS_CANCELED.equals(t.status)) DownloadQueueService.resume(this, t.id);
        else if (DownloadTask.STATUS_COMPLETED.equals(t.status) && t.outputUri != null && !t.outputUri.isEmpty()) openPlayer(Uri.parse(t.outputUri), t.mime, t.name);
        renderDownloadList();
    }

    private void removeTask(DownloadTask t) {
        if (isActive(t)) DownloadQueueService.cancel(this, t.id);
        DownloadTaskStore.remove(this, t.id);
        renderDownloadList();
    }

    private String taskType(DownloadTask t) {
        if (DownloadTask.MODE_MP3.equals(t.mode)) return "MP3 " + t.mp3Bitrate + " kbps";
        String k = t.kind == null ? "VIDEO" : t.kind.toUpperCase(Locale.ROOT);
        return k + (t.quality == null || t.quality.isEmpty() ? "" : " • " + t.quality);
    }

    private void showLibrary() {
        toolbar.setTitle("Library");
        toolbar.setSubtitle("Finished videos and audio on your phone");
        View v = getLayoutInflater().inflate(R.layout.screen_library, container, false);
        container.addView(v);
        libraryList = v.findViewById(R.id.library_list);
        librarySummary = v.findViewById(R.id.library_summary);
        v.findViewById(R.id.library_all).setOnClickListener(x -> { libraryFilter = "all"; renderLibrary(); });
        v.findViewById(R.id.library_video).setOnClickListener(x -> { libraryFilter = "video"; renderLibrary(); });
        v.findViewById(R.id.library_audio).setOnClickListener(x -> { libraryFilter = "audio"; renderLibrary(); });
        renderLibrary();
    }

    private void renderLibrary() {
        if (libraryList == null || currentScreen != R.id.nav_library) return;
        List<LibraryRepository.Item> files = LibraryRepository.list(this);
        int video = 0, audio = 0;
        for (LibraryRepository.Item f : files) { if (f.isAudio()) audio++; else video++; }
        librarySummary.setText(files.size() + " files • " + video + " videos • " + audio + " audio");
        libraryList.removeAllViews();
        int shown = 0;
        for (LibraryRepository.Item f : files) {
            if ("video".equals(libraryFilter) && f.isAudio()) continue;
            if ("audio".equals(libraryFilter) && !f.isAudio()) continue;
            libraryList.addView(libraryCard(f));
            shown++;
        }
        if (shown == 0) libraryList.addView(emptyState("Your library is empty", "Completed downloads and MP3 conversions will appear here."));
    }

    private View libraryCard(LibraryRepository.Item f) {
        MaterialCardView card = UiKit.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12));
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(ContextCompat.getColor(this, R.color.vg_surface_variant));
        thumb.setImageResource(f.isAudio() ? R.drawable.ic_music : R.drawable.ic_video);
        thumb.setColorFilter(ContextCompat.getColor(this, R.color.vg_primary));
        row.addView(thumb, new LinearLayout.LayoutParams(UiKit.dp(this, 112), UiKit.dp(this, 78)));
        if (!f.isAudio()) loadThumbnailAsync(f, thumb);

        LinearLayout info = verticalBox(0);
        info.setPadding(UiKit.dp(this, 12), 0, 0, 0);
        TextView name = UiKit.title(this, f.name, 14);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(f.date * 1000L));
        TextView meta = UiKit.muted(this, UiKit.bytes(f.size) + " • " + when, 12);
        meta.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 8));
        info.addView(meta);
        LinearLayout actions = new LinearLayout(this);
        MaterialButton play = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle);
        play.setText("Play"); play.setAllCaps(false);
        MaterialButton more = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        more.setText("More"); more.setAllCaps(false);
        actions.addView(play, new LinearLayout.LayoutParams(0, UiKit.dp(this, 42), 1));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 42), 1); mp.setMargins(UiKit.dp(this, 7), 0, 0, 0);
        actions.addView(more, mp);
        info.addView(actions);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
        play.setOnClickListener(v -> openPlayer(f.uri, f.mime, f.name));
        more.setOnClickListener(v -> showFileActions(f));
        card.setOnClickListener(v -> openPlayer(f.uri, f.mime, f.name));
        return card;
    }

    private void loadThumbnailAsync(LibraryRepository.Item f, ImageView image) {
        new Thread(() -> {
            try {
                Bitmap b = getContentResolver().loadThumbnail(f.uri, new Size(360, 220), null);
                runOnUiThread(() -> { image.clearColorFilter(); image.setImageBitmap(b); });
            } catch (Exception ignored) { }
        }, "vg-thumb").start();
    }

    private void showFileActions(LibraryRepository.Item f) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout root = verticalBox(12);
        root.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 12), UiKit.dp(this, 20), UiKit.dp(this, 24));
        TextView title = UiKit.title(this, f.name, 18);
        title.setMaxLines(2);
        root.addView(title);
        TextView meta = UiKit.muted(this, UiKit.bytes(f.size) + " • Downloads/VideoGrab", 12);
        meta.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 10));
        root.addView(meta);
        root.addView(sheetButton("Play in VideoGrab", v -> { dialog.dismiss(); openPlayer(f.uri, f.mime, f.name); }));
        if (!f.isAudio()) root.addView(sheetButton("Convert to MP3", v -> { dialog.dismiss(); chooseConvertBitrate(f); }));
        root.addView(sheetButton("Share file", v -> { dialog.dismiss(); shareFile(f); }));
        root.addView(sheetButton("Delete file", v -> { dialog.dismiss(); confirmDelete(f); }));
        dialog.setContentView(root);
        dialog.show();
    }

    private MaterialButton sheetButton(String label, View.OnClickListener action) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label); b.setAllCaps(false); b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); b.setOnClickListener(action);
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, UiKit.dp(this, 50)));
        return b;
    }

    private void chooseConvertBitrate(LibraryRepository.Item f) {
        List<String> options = new ArrayList<>(); options.add("128 kbps"); options.add("192 kbps"); options.add("320 kbps");
        showChoiceSheet("Convert to MP3", "Conversion runs locally on your phone", options, i -> {
            int bitrate = i == 0 ? 128 : i == 1 ? 192 : 320;
            Intent in = new Intent(this, AudioConvertService.class);
            in.putExtra(AudioConvertService.EXTRA_URI, f.uri.toString());
            in.putExtra(AudioConvertService.EXTRA_NAME, f.name);
            in.putExtra(AudioConvertService.EXTRA_BITRATE, bitrate);
            startForegroundService(in);
            Snackbar.make(container, "MP3 conversion started", Snackbar.LENGTH_LONG).show();
        });
    }

    private void shareFile(LibraryRepository.Item f) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(f.mime == null ? "*/*" : f.mime);
        i.putExtra(Intent.EXTRA_STREAM, f.uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share file"));
    }

    private void confirmDelete(LibraryRepository.Item f) {
        new MaterialAlertDialogBuilder(this).setTitle("Delete file?").setMessage(f.name + " will be removed from Downloads/VideoGrab.").setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> {
            getContentResolver().delete(f.uri, null, null);
            renderLibrary();
        }).show();
    }

    private void openPlayer(Uri uri, String mime, String name) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_URI, uri.toString());
        i.putExtra(PlayerActivity.EXTRA_MIME, mime);
        i.putExtra(PlayerActivity.EXTRA_NAME, name);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
    }

    private void showSettings() {
        toolbar.setTitle("Settings");
        toolbar.setSubtitle("Detection, appearance, output and storage");
        View v = getLayoutInflater().inflate(R.layout.screen_settings, container, false);
        container.addView(v);
        SwitchMaterial hide = v.findViewById(R.id.setting_hide_small);
        hide.setChecked(AppPrefs.hideSmall(this));
        hide.setOnCheckedChangeListener((button, checked) -> { AppPrefs.setHideSmall(this, checked); updateBrowserStatus(); });
        MaterialButton theme = v.findViewById(R.id.setting_theme);
        MaterialButton mp3 = v.findViewById(R.id.setting_mp3);
        updateThemeButton(theme);
        mp3.setText("Default MP3 • " + AppPrefs.mp3(this) + " kbps");
        theme.setOnClickListener(x -> chooseTheme(theme));
        mp3.setOnClickListener(x -> chooseDefaultMp3(mp3));
        v.findViewById(R.id.setting_clear_browser).setOnClickListener(x -> clearBrowserData());
        v.findViewById(R.id.setting_clear_finished).setOnClickListener(x -> clearFinishedQueue());
    }

    private void chooseTheme(MaterialButton button) {
        String[] labels = {"Follow system", "Light", "Dark"};
        String[] values = {"system", "light", "dark"};
        String cur = AppPrefs.theme(this);
        int checked = "light".equals(cur) ? 1 : "dark".equals(cur) ? 2 : 0;
        new MaterialAlertDialogBuilder(this).setTitle("App theme").setSingleChoiceItems(labels, checked, (d, which) -> {
            d.dismiss();
            AppPrefs.setTheme(this, values[which]);
            updateThemeButton(button);
        }).show();
    }

    private void updateThemeButton(MaterialButton button) {
        String t = AppPrefs.theme(this);
        button.setText("Theme • " + ("light".equals(t) ? "Light" : "dark".equals(t) ? "Dark" : "System"));
    }

    private void chooseDefaultMp3(MaterialButton button) {
        String[] labels = {"128 kbps • smaller files", "192 kbps • balanced", "320 kbps • highest quality"};
        int current = AppPrefs.mp3(this);
        int checked = current == 128 ? 0 : current == 320 ? 2 : 1;
        new MaterialAlertDialogBuilder(this).setTitle("Default MP3 quality").setSingleChoiceItems(labels, checked, (d, which) -> {
            int value = which == 0 ? 128 : which == 1 ? 192 : 320;
            AppPrefs.setMp3(this, value);
            button.setText("Default MP3 • " + value + " kbps");
            d.dismiss();
        }).show();
    }

    private void clearBrowserData() {
        new MaterialAlertDialogBuilder(this).setTitle("Clear browser data?").setMessage("This clears VideoGrab browser cookies, cache, history and site storage. Downloaded files are not affected.").setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> {
            ensureBrowser();
            webView.clearCache(true);
            webView.clearHistory();
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
            Snackbar.make(container, "Browser data cleared", Snackbar.LENGTH_LONG).show();
        }).show();
    }

    private void clearFinishedQueue() {
        int count = 0;
        for (DownloadTask t : DownloadTaskStore.list(this)) {
            if (DownloadTask.STATUS_COMPLETED.equals(t.status)) { DownloadTaskStore.remove(this, t.id); count++; }
        }
        Snackbar.make(container, count == 0 ? "No finished queue records to clear" : "Cleared " + count + " finished queue records", Snackbar.LENGTH_LONG).show();
    }

    private View emptyState(String title, String subtitle) {
        LinearLayout box = verticalBox(18);
        box.setGravity(Gravity.CENTER);
        box.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 50), UiKit.dp(this, 24), UiKit.dp(this, 50));
        TextView icon = UiKit.text(this, "↓", 38, R.color.vg_primary);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon);
        TextView t = UiKit.title(this, title, 17); t.setGravity(Gravity.CENTER); box.addView(t);
        TextView s = UiKit.muted(this, subtitle, 13); s.setGravity(Gravity.CENTER); s.setPadding(0, UiKit.dp(this, 7), 0, 0); box.addView(s);
        return box;
    }

    private LinearLayout verticalBox(int paddingDp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (paddingDp > 0) box.setPadding(UiKit.dp(this, paddingDp), UiKit.dp(this, paddingDp), UiKit.dp(this, paddingDp), UiKit.dp(this, paddingDp));
        return box;
    }

    private String pageHost() { return webView == null ? "" : hostOf(webView.getUrl()); }
    private String hostOf(String url) { try { String h = new URI(url).getHost(); return h == null ? "" : h.replaceFirst("^www\\.", ""); } catch (Exception e) { return ""; } }
    private String trim(String s, int max) { if (s == null) return ""; return s.length() > max ? s.substring(0, max - 1) + "…" : s; }
    private String safe(String s) { return s == null ? "" : s; }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 50);
    }

    @Override public void onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) { drawer.closeDrawer(GravityCompat.START); return; }
        if (currentScreen == R.id.nav_browser && webView != null && webView.canGoBack()) { webView.goBack(); return; }
        if (currentScreen != R.id.nav_home) { bottomNav.setSelectedItemId(R.id.nav_home); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(downloadRefresh);
        if (isFinishing() && webView != null) webView.destroy();
        super.onDestroy();
    }

    public class JsBridge {
        @JavascriptInterface public void found(String u) { detectNetwork(u); }
        @JavascriptInterface public void media(String u, int width, int height, double duration, boolean playing) { detectMediaElement(u, width, height, duration, playing); }
    }
}
