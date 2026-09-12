package com.daniyal.videograb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;

import java.io.File;

public class AudioConvertService extends Service {
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_BITRATE = "bitrate";
    private static final String CHANNEL = "vg_audio_convert";
    private static final int ID = 8821;

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Audio conversion", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String raw = intent == null ? null : intent.getStringExtra(EXTRA_URI);
        String name = intent == null ? "audio" : intent.getStringExtra(EXTRA_NAME);
        int bitrate = intent == null ? 192 : intent.getIntExtra(EXTRA_BITRATE, 192);
        startForeground(ID, note("Preparing MP3 conversion", true));
        new Thread(() -> {
            File temp = null;
            try {
                if (raw == null) throw new IllegalArgumentException("Missing source");
                temp = MediaPublisher.copyUriToCache(this, Uri.parse(raw), ".media");
                update("Converting to MP3 • " + bitrate + " kbps");
                MediaPublisher.convertFileToMp3(this, temp, name, bitrate);
                done("MP3 saved in VideoGrab");
            } catch (Exception e) {
                done("Conversion failed: " + compact(e.getMessage()));
            } finally {
                if (temp != null) temp.delete();
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf(startId);
            }
        }, "vg-audio-convert").start();
        return START_NOT_STICKY;
    }

    private Notification note(String text, boolean ongoing) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("VideoGrab")
                .setContentText(text)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void update(String text) { getSystemService(NotificationManager.class).notify(ID, note(text, true)); }

    private void done(String text) {
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("VideoGrab")
                .setContentText(text)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(ID, n);
    }

    private String compact(String s) {
        if (s == null || s.trim().isEmpty()) return "Unknown error";
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
