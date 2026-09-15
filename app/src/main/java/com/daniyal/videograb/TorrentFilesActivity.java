package com.daniyal.videograb;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.List;

public class TorrentFilesActivity extends AppCompatActivity {
    public static final String EXTRA_TASK_ID = "task_id";
    private DownloadTask task;
    private LinearLayout list;
    private TextView summary;

    @Override protected void onCreate(Bundle state) {
        AppPrefs.applyTheme(this);
        super.onCreate(state);
        String id = getIntent().getStringExtra(EXTRA_TASK_ID);
        task = id == null ? null : DownloadTaskStore.get(this, id);
        if (task == null) { finish(); return; }
        buildUi();
        render();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.vg_background));

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Torrent files");
        toolbar.setSubtitle(task.name);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 64)));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 16), UiKit.dp(this, 20), UiKit.dp(this, 12));
        header.addView(UiKit.title(this, task.name, 21));
        summary = UiKit.muted(this, "Loading files…", 13);
        summary.setPadding(0, UiKit.dp(this, 6), 0, 0);
        header.addView(summary);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(UiKit.dp(this, 20), 0, UiKit.dp(this, 20), UiKit.dp(this, 28));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void render() {
        List<File> files = TorrentStorage.files(this, task.id);
        long total = 0;
        for (File f : files) total += f.length();
        summary.setText(files.size() + (files.size() == 1 ? " file" : " files") + " • " + UiKit.bytes(total) + " • App-managed torrent storage");
        list.removeAllViews();
        if (files.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UiKit.dp(this, 56), 0, UiKit.dp(this, 56));
            TextView t = UiKit.title(this, "No files found", 17); t.setGravity(Gravity.CENTER); empty.addView(t);
            TextView s = UiKit.muted(this, "The torrent may still be finalizing or its data is no longer available.", 13); s.setGravity(Gravity.CENTER); s.setPadding(0, UiKit.dp(this, 7), 0, 0); empty.addView(s);
            list.addView(empty);
            return;
        }
        for (File f : files) list.addView(fileCard(f));
    }

    private MaterialCardView fileCard(File file) {
        MaterialCardView card = UiKit.card(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 15), UiKit.dp(this, 14), UiKit.dp(this, 15), UiKit.dp(this, 14));

        TextView name = UiKit.title(this, file.getName(), 14);
        name.setMaxLines(2);
        box.addView(name);
        String relative = TorrentStorage.relative(this, task.id, file);
        TextView meta = UiKit.muted(this, UiKit.bytes(file.length()) + " • " + relative, 12);
        meta.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 10));
        box.addView(meta);

        LinearLayout actions = new LinearLayout(this);
        MaterialButton open = new MaterialButton(this);
        open.setText("Open"); open.setAllCaps(false); open.setOnClickListener(v -> openFile(file));
        MaterialButton share = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        share.setText("Share"); share.setAllCaps(false); share.setOnClickListener(v -> shareFile(file));
        MaterialButton export = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        export.setText("Export"); export.setAllCaps(false); export.setOnClickListener(v -> exportFile(file));
        actions.addView(open, new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1); sp.setMargins(UiKit.dp(this, 6), 0, 0, 0);
        actions.addView(share, sp);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1); ep.setMargins(UiKit.dp(this, 6), 0, 0, 0);
        actions.addView(export, ep);
        box.addView(actions);
        card.addView(box);
        card.setOnClickListener(v -> openFile(file));
        return card;
    }

    private void openFile(File file) {
        Uri u = TorrentStorage.uri(this, file);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, TorrentStorage.mime(file));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); }
        catch (Exception e) { Toast.makeText(this, "No app can open this file type", Toast.LENGTH_LONG).show(); }
    }

    private void shareFile(File file) {
        Uri u = TorrentStorage.uri(this, file);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(TorrentStorage.mime(file));
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share torrent file"));
    }

    private void exportFile(File file) {
        Toast.makeText(this, "Exporting to Downloads/VideoGrab/Torrents…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Uri out = null;
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, file.getName());
                cv.put(MediaStore.Downloads.MIME_TYPE, TorrentStorage.mime(file));
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VideoGrab/Torrents/" + MediaPublisher.sanitize(task.name));
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                out = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (out == null) throw new IllegalStateException("Unable to create output file");
                try (FileInputStream in = new FileInputStream(file); OutputStream os = getContentResolver().openOutputStream(out)) {
                    if (os == null) throw new IllegalStateException("Unable to open output file");
                    byte[] b = new byte[131072]; int n; while ((n = in.read(b)) >= 0) os.write(b, 0, n);
                }
                ContentValues done = new ContentValues(); done.put(MediaStore.Downloads.IS_PENDING, 0); getContentResolver().update(out, done, null, null);
                runOnUiThread(() -> Toast.makeText(this, "Exported to Downloads/VideoGrab/Torrents", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                if (out != null) getContentResolver().delete(out, null, null);
                String m = e.getMessage() == null ? "Export failed" : e.getMessage();
                runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
            }
        }, "vg-torrent-export").start();
    }
}
