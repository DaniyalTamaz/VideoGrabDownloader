package com.daniyal.videograb;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

public class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_MIME = "mime";
    public static final String EXTRA_NAME = "name";
    private VideoView video;

    @Override protected void onCreate(Bundle savedInstanceState) {
        AppPrefs.applyTheme(this);
        super.onCreate(savedInstanceState);

        String raw = getIntent().getStringExtra(EXTRA_URI);
        String mime = getIntent().getStringExtra(EXTRA_MIME);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (raw == null) { finish(); return; }

        Uri uri = Uri.parse(raw);
        if ("application/x-videograb-torrent".equals(mime) || "videograb-torrent".equals(uri.getScheme())) {
            String taskId = uri.getHost();
            if (taskId == null || taskId.isEmpty()) taskId = uri.getSchemeSpecificPart().replaceFirst("^//", "");
            Intent i = new Intent(this, TorrentFilesActivity.class).putExtra(TorrentFilesActivity.EXTRA_TASK_ID, taskId);
            startActivity(i);
            finish();
            return;
        }

        setContentView(R.layout.activity_player);
        MaterialToolbar toolbar = findViewById(R.id.player_toolbar);
        TextView nameView = findViewById(R.id.player_name);
        TextView meta = findViewById(R.id.player_meta);
        TextView audioArt = findViewById(R.id.player_audio_art);
        MaterialButton share = findViewById(R.id.player_share);
        video = findViewById(R.id.player_video);

        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitle(mime != null && mime.startsWith("audio/") ? "Audio Player" : "Video Player");
        nameView.setText(name == null || name.trim().isEmpty() ? "VideoGrab media" : name);
        meta.setText((mime == null ? "Media" : mime) + " • Downloads/VideoGrab");

        boolean audio = (mime != null && mime.startsWith("audio/")) || (name != null && name.toLowerCase().endsWith(".mp3"));
        audioArt.setVisibility(audio ? View.VISIBLE : View.GONE);

        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setVideoURI(uri);
        video.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            video.start();
            controller.show(3500);
        });
        video.setOnErrorListener((mp, what, extra) -> {
            meta.setText("This file could not be played by the built-in Android media player.");
            return false;
        });

        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime == null ? "*/*" : mime);
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share file"));
        });
    }

    @Override protected void onPause() {
        if (video != null && video.isPlaying()) video.pause();
        super.onPause();
    }
}
