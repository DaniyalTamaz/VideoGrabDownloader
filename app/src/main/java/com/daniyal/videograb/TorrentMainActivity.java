package com.daniyal.videograb;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.widget.EditText;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;

import java.util.Locale;

public class TorrentMainActivity extends MainActivity {
    private static final String TAG_PASTE = "vg-torrent-paste";
    private static final String TAG_GO = "vg-torrent-go";
    private static final String TAG_WEB = "vg-torrent-web";
    private ViewTreeObserver.OnGlobalLayoutListener hook;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        View root = findViewById(android.R.id.content);
        hook = this::installTorrentHooks;
        root.getViewTreeObserver().addOnGlobalLayoutListener(hook);
        installTorrentHooks();
    }

    private void installTorrentHooks() {
        View paste = findViewById(R.id.home_paste);
        if (paste != null && !TAG_PASTE.equals(paste.getTag())) {
            paste.setTag(TAG_PASTE);
            paste.setOnClickListener(v -> pasteSmart());
        }

        View go = findViewById(R.id.browser_go);
        EditText address = findViewById(R.id.browser_address);
        if (go != null && address != null && !TAG_GO.equals(go.getTag())) {
            go.setTag(TAG_GO);
            go.setOnClickListener(v -> routeBrowserInput(address.getText().toString()));
            address.setOnEditorActionListener((v, actionId, event) -> { routeBrowserInput(address.getText().toString()); return true; });
        }

        WebView web = findViewById(R.id.browser_webview);
        if (web != null && !TAG_WEB.equals(web.getTag())) {
            web.setTag(TAG_WEB);
            web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
                if (isTorrentSource(url) || "application/x-bittorrent".equalsIgnoreCase(mimeType)) openTorrentSource(url);
            });
        }
    }

    private void pasteSmart() {
        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        ClipData d = cm == null ? null : cm.getPrimaryClip();
        String value = d != null && d.getItemCount() > 0 ? String.valueOf(d.getItemAt(0).coerceToText(this)).trim() : "";
        if (value.isEmpty()) { Snackbar.make(findViewById(android.R.id.content), "Clipboard is empty", Snackbar.LENGTH_LONG).show(); return; }
        String source = extractTorrentSource(value);
        if (!source.isEmpty()) { openTorrentSource(source); return; }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            BottomNavigationView nav = findViewById(R.id.bottom_nav);
            if (nav != null) nav.setSelectedItemId(R.id.nav_browser);
            findViewById(android.R.id.content).post(() -> loadNormalUrl(value));
        } else Snackbar.make(findViewById(android.R.id.content), "Clipboard does not contain a web, magnet, or .torrent link", Snackbar.LENGTH_LONG).show();
    }

    private void routeBrowserInput(String raw) {
        String value = raw == null ? "" : raw.trim();
        String torrent = extractTorrentSource(value);
        if (!torrent.isEmpty()) { openTorrentSource(torrent); return; }
        loadNormalUrl(value);
    }

    private void loadNormalUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String u = raw.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            if (u.contains(" ") || !u.contains(".")) u = "https://www.google.com/search?q=" + Uri.encode(u);
            else u = "https://" + u;
        }
        WebView web = findViewById(R.id.browser_webview);
        EditText address = findViewById(R.id.browser_address);
        if (address != null) address.setText(u);
        if (web != null) web.loadUrl(u);
    }

    private String extractTorrentSource(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int magnet = s.indexOf("magnet:?");
        if (magnet >= 0) {
            String m = s.substring(magnet);
            for (int i = 0; i < m.length(); i++) if (Character.isWhitespace(m.charAt(i))) return m.substring(0, i);
            return m;
        }
        for (String token : s.split("\\s+")) if (isTorrentSource(token)) return token;
        return isTorrentSource(s) ? s : "";
    }

    private boolean isTorrentSource(String value) {
        if (value == null) return false;
        String s = value.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("magnet:?")) return true;
        if (!(s.startsWith("http://") || s.startsWith("https://"))) return false;
        int q = s.indexOf('?'); if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#'); if (h >= 0) s = s.substring(0, h);
        return s.endsWith(".torrent");
    }

    private void openTorrentSource(String source) {
        Intent i = new Intent(this, TorrentAddActivity.class);
        if (source != null && source.toLowerCase(Locale.ROOT).startsWith("magnet:?")) i.setData(Uri.parse(source)).setAction(Intent.ACTION_VIEW);
        else i.setData(Uri.parse(source)).setAction(Intent.ACTION_VIEW);
        startActivity(i);
    }

    @Override protected void onDestroy() {
        View root = findViewById(android.R.id.content);
        if (root != null && hook != null && root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnGlobalLayoutListener(hook);
        super.onDestroy();
    }
}
