package com.daniyal.videograb;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.TorrentInfo;

import java.io.File;
import java.util.Locale;

public class TorrentAddActivity extends AppCompatActivity {
    private static final int PICK_TORRENT = 701;
    private EditText input;
    private TextView status;
    private ProgressBar progress;
    private MaterialButton add;

    @Override protected void onCreate(Bundle state) {
        AppPrefs.applyTheme(this);
        super.onCreate(state);
        buildUi();
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.vg_background));

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Add torrent");
        toolbar.setSubtitle("Magnet link or .torrent file");
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 64)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 20), UiKit.dp(this, 20), UiKit.dp(this, 24));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView title = UiKit.title(this, "Torrent downloader", 25);
        body.addView(title);
        TextView sub = UiKit.muted(this, "Paste a magnet link, open a .torrent file, or share one to VideoGrab. Downloads run through the same Download Manager.", 13);
        sub.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 18));
        body.addView(sub);

        MaterialCardView card = UiKit.card(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16));
        input = new EditText(this);
        input.setHint("magnet:?xt=urn:btih:… or https://…/file.torrent");
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setMaxLines(5);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, UiKit.dp(this, 12), 0, 0);
        MaterialButton paste = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        paste.setText("Paste"); paste.setAllCaps(false);
        paste.setOnClickListener(v -> pasteClipboard());
        MaterialButton choose = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        choose.setText("Choose .torrent"); choose.setAllCaps(false);
        choose.setOnClickListener(v -> chooseFile());
        row.addView(paste, new LinearLayout.LayoutParams(0, UiKit.dp(this, 48), 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 48), 1); cp.setMargins(UiKit.dp(this, 8), 0, 0, 0);
        row.addView(choose, cp);
        box.addView(row);

        add = new MaterialButton(this);
        add.setText("Add to Download Manager"); add.setAllCaps(false);
        add.setOnClickListener(v -> processText(input.getText().toString()));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, UiKit.dp(this, 52)); ap.setMargins(0, UiKit.dp(this, 10), 0, 0);
        box.addView(add, ap);
        card.addView(box);
        body.addView(card);

        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setPadding(0, UiKit.dp(this, 18), 0, 0);
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        progressRow.addView(progress, new LinearLayout.LayoutParams(UiKit.dp(this, 34), UiKit.dp(this, 34)));
        status = UiKit.muted(this, "Ready", 13);
        status.setPadding(UiKit.dp(this, 12), 0, 0, 0);
        progressRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(progressRow);

        TextView note = UiKit.muted(this, "Use torrent downloads only for content you are authorized to obtain and share.", 12);
        note.setPadding(0, UiKit.dp(this, 18), 0, 0);
        body.addView(note);
        setContentView(root);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri data = intent.getData();
        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            String scheme = data.getScheme() == null ? "" : data.getScheme().toLowerCase(Locale.ROOT);
            if ("magnet".equals(scheme)) { input.setText(data.toString()); processText(data.toString()); return; }
            if ("http".equals(scheme) || "https".equals(scheme)) { input.setText(data.toString()); processText(data.toString()); return; }
            processTorrentUri(data); return;
        }
        if (Intent.ACTION_SEND.equals(action)) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null && !text.toString().trim().isEmpty()) { input.setText(text); processText(text.toString()); return; }
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) { processTorrentUri(stream); return; }
        }
    }

    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        ClipData d = cm == null ? null : cm.getPrimaryClip();
        String value = d != null && d.getItemCount() > 0 ? String.valueOf(d.getItemAt(0).coerceToText(this)).trim() : "";
        if (value.isEmpty()) { status.setText("Clipboard is empty"); return; }
        input.setText(value);
    }

    private void chooseFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_TORRENT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_TORRENT && resultCode == RESULT_OK && data != null && data.getData() != null) processTorrentUri(data.getData());
    }

    private void processText(String raw) {
        String value = extractSource(raw);
        if (value.isEmpty()) { error("Paste a magnet link or a .torrent URL first."); return; }
        if (value.startsWith("magnet:?")) { addMagnet(value); return; }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            if (!looksTorrentUrl(value)) { error("This web link does not look like a .torrent file."); return; }
            fetchTorrent(value); return;
        }
        error("Unsupported torrent source. Use magnet:, a .torrent URL, or choose a .torrent file.");
    }

    private String extractSource(String raw) {
        String s = raw == null ? "" : raw.trim();
        int m = s.indexOf("magnet:?");
        if (m >= 0) { s = s.substring(m); int ws = firstWhitespace(s); return ws > 0 ? s.substring(0, ws) : s; }
        for (String token : s.split("\\s+")) if (token.startsWith("http://") || token.startsWith("https://")) return token;
        return s;
    }

    private int firstWhitespace(String s) { for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i; return -1; }
    private boolean looksTorrentUrl(String u) { String x = u.toLowerCase(Locale.ROOT); int q = x.indexOf('?'); if (q >= 0) x = x.substring(0, q); return x.endsWith(".torrent"); }

    private void addMagnet(String magnet) {
        busy("Reading magnet link…");
        new Thread(() -> {
            try {
                AddTorrentParams p = AddTorrentParams.parseMagnetUri(magnet);
                DownloadTask t = new DownloadTask();
                t.kind = DownloadTask.KIND_TORRENT;
                t.url = magnet;
                t.name = p.getName() == null || p.getName().trim().isEmpty() ? "Magnet torrent" : MediaPublisher.sanitize(p.getName());
                t.torrentHash = p.getInfoHashes().getBest().toHex();
                t.quality = "P2P • Magnet";
                t.mime = "application/x-videograb-torrent";
                runOnUiThread(() -> enqueueAndOpen(t));
            } catch (Throwable e) { runOnUiThread(() -> error(message(e, "Invalid magnet link"))); }
        }, "vg-add-magnet").start();
    }

    private void fetchTorrent(String url) {
        busy("Downloading torrent metadata…");
        String taskId = java.util.UUID.randomUUID().toString();
        new Thread(() -> {
            try {
                File meta = TorrentStorage.fetchTorrentUrl(this, url, "", "VideoGrab/5.1", "", taskId);
                TorrentInfo info = new TorrentInfo(meta);
                if (!info.isValid()) throw new IllegalArgumentException("Invalid .torrent metadata");
                DownloadTask t = taskFromInfo(taskId, url, meta, info);
                runOnUiThread(() -> enqueueAndOpen(t));
            } catch (Throwable e) { runOnUiThread(() -> error(message(e, "Could not open .torrent URL"))); }
        }, "vg-fetch-torrent").start();
    }

    private void processTorrentUri(Uri uri) {
        busy("Importing .torrent file…");
        String taskId = java.util.UUID.randomUUID().toString();
        new Thread(() -> {
            try {
                File meta = TorrentStorage.importTorrentUri(this, uri, taskId);
                TorrentInfo info = new TorrentInfo(meta);
                if (!info.isValid()) throw new IllegalArgumentException("Invalid .torrent metadata");
                DownloadTask t = taskFromInfo(taskId, uri.toString(), meta, info);
                runOnUiThread(() -> enqueueAndOpen(t));
            } catch (Throwable e) { runOnUiThread(() -> error(message(e, "Could not read .torrent file"))); }
        }, "vg-import-torrent").start();
    }

    private DownloadTask taskFromInfo(String id, String source, File meta, TorrentInfo info) {
        DownloadTask t = new DownloadTask();
        t.id = id;
        t.kind = DownloadTask.KIND_TORRENT;
        t.url = source;
        t.torrentMetaPath = meta.getAbsolutePath();
        t.torrentHash = info.infoHash().toHex();
        t.torrentFiles = info.numFiles();
        t.total = info.totalSize();
        t.name = MediaPublisher.sanitize(info.name());
        t.quality = "P2P • " + info.numFiles() + (info.numFiles() == 1 ? " file" : " files");
        t.mime = "application/x-videograb-torrent";
        return t;
    }

    private void enqueueAndOpen(DownloadTask t) {
        TorrentDownloadService.enqueue(this, t);
        status.setText("Added to Download Manager");
        progress.setVisibility(View.GONE);
        Intent i = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_SCREEN, "downloads").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void busy(String message) { progress.setVisibility(View.VISIBLE); status.setText(message); add.setEnabled(false); }
    private void error(String message) {
        progress.setVisibility(View.GONE); add.setEnabled(true); status.setText(message);
        new MaterialAlertDialogBuilder(this).setTitle("Torrent could not be added").setMessage(message).setPositiveButton("OK", null).show();
    }
    private String message(Throwable e, String fallback) { String m = e == null ? null : e.getMessage(); return m == null || m.trim().isEmpty() ? fallback : m; }
}
