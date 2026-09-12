package com.daniyal.videograb;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

public class PlayerActivity extends Activity {
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_MIME = "mime";
    public static final String EXTRA_NAME = "name";
    private VideoView video;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String raw = getIntent().getStringExtra(EXTRA_URI);
        String mime = getIntent().getStringExtra(EXTRA_MIME);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (raw == null) { finish(); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(8), dp(10), dp(8));
        header.setBackgroundColor(Color.rgb(24, 28, 36));
        Button back = new Button(this); back.setText("‹"); back.setTextSize(22);
        TextView title = new TextView(this);
        title.setText(name == null ? "VideoGrab Player" : name);
        title.setTextColor(Color.WHITE); title.setTextSize(15); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setSingleLine(true);
        Button share = new Button(this); share.setText("Share"); share.setAllCaps(false);
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        header.addView(share, new LinearLayout.LayoutParams(dp(82), dp(48)));
        root.addView(header);

        video = new VideoView(this);
        root.addView(video, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        Uri uri = Uri.parse(raw);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setVideoURI(uri);
        video.setOnPreparedListener(mp -> { mp.setLooping(false); video.start(); controller.show(3000); });

        back.setOnClickListener(v -> finish());
        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime == null ? "*/*" : mime);
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share with"));
        });
    }

    @Override protected void onPause() {
        if (video != null && video.isPlaying()) video.pause();
        super.onPause();
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
