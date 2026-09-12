package com.daniyal.videograb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.provider.MediaStore;
import android.webkit.URLUtil;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HlsDownloadService extends Service {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_COOKIE = "cookie";
    public static final String EXTRA_UA = "ua";
    public static final String EXTRA_REFERER = "referer";
    private static final String CHANNEL = "video_downloads";
    private static final int NOTIFICATION_ID = 8181;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Video downloads", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String url = intent.getStringExtra(EXTRA_URL);
        String cookie = intent.getStringExtra(EXTRA_COOKIE);
        String ua = intent.getStringExtra(EXTRA_UA);
        String referer = intent.getStringExtra(EXTRA_REFERER);
        startForeground(NOTIFICATION_ID, notification("Preparing HLS download", 0, true));
        new Thread(() -> {
            try {
                download(url, cookie, ua, referer);
            } catch (Exception e) {
                notifyDone("Download failed: " + compact(e.getMessage()));
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf(startId);
            }
        }, "hls-downloader").start();
        return START_NOT_STICKY;
    }

    private void download(String source, String cookie, String ua, String referer) throws Exception {
        if (source == null) throw new IllegalArgumentException("Missing playlist URL");
        String playlistUrl = source;
        String playlist = fetchText(playlistUrl, cookie, ua, referer);
        if (!playlist.startsWith("#EXTM3U")) throw new IllegalArgumentException("Not a valid HLS playlist");

        if (playlist.contains("#EXT-X-STREAM-INF")) {
            playlistUrl = chooseBestVariant(playlistUrl, playlist);
            playlist = fetchText(playlistUrl, cookie, ua, referer);
        }

        String upper = playlist.toUpperCase(Locale.ROOT);
        if (containsProtectedKey(playlist)) throw new IllegalArgumentException("Encrypted/DRM HLS is not supported");
        if (upper.contains("#EXT-X-BYTERANGE")) throw new IllegalArgumentException("Byte-range HLS is not supported in this version");
        if (!upper.contains("#EXT-X-ENDLIST")) throw new IllegalArgumentException("Live HLS streams are not supported in this version");

        List<String> parts = new ArrayList<>();
        String map = extractMapUri(playlist);
        if (map != null) parts.add(resolve(playlistUrl, map));
        for (String line : playlist.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            parts.add(resolve(playlistUrl, line));
        }
        if (parts.isEmpty()) throw new IllegalArgumentException("No media segments found");

        boolean fragmentedMp4 = map != null || parts.stream().anyMatch(s -> {
            String x = s.toLowerCase(Locale.ROOT);
            return x.contains(".m4s") || x.contains(".mp4");
        });
        String ext = fragmentedMp4 ? ".mp4" : ".ts";
        String base = URLUtil.guessFileName(playlistUrl, null, null).replaceAll("(?i)\\.m3u8$", "");
        if (base.trim().isEmpty()) base = "video_" + System.currentTimeMillis();
        String filename = sanitize(base) + ext;

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, fragmentedMp4 ? "video/mp4" : "video/mp2t");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VideoGrab");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri item = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (item == null) throw new IllegalStateException("Could not create output file");

        boolean ok = false;
        try (OutputStream out = getContentResolver().openOutputStream(item, "w")) {
            if (out == null) throw new IllegalStateException("Could not open output file");
            for (int i = 0; i < parts.size(); i++) {
                copyUrl(parts.get(i), out, cookie, ua, referer);
                int progress = (int) (((i + 1) * 100L) / parts.size());
                updateNotification("Downloading " + filename, progress);
            }
            out.flush();
            ok = true;
        } finally {
            if (ok) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(item, done, null, null);
            } else {
                getContentResolver().delete(item, null, null);
            }
        }
        DownloadHistoryStore.add(this, filename, source);
        notifyDone("Saved to Downloads/VideoGrab/" + filename);
    }

    private boolean containsProtectedKey(String playlist) {
        for (String line : playlist.split("\\r?\\n")) {
            String u = line.trim().toUpperCase(Locale.ROOT);
            if (!u.startsWith("#EXT-X-KEY:")) continue;
            int p = u.indexOf("METHOD=");
            if (p < 0) return true;
            int s = p + 7;
            int e = u.indexOf(',', s);
            String method = (e < 0 ? u.substring(s) : u.substring(s, e)).trim();
            if (!"NONE".equals(method)) return true;
        }
        return false;
    }

    private String chooseBestVariant(String masterUrl, String master) throws Exception {
        String[] lines = master.split("\\r?\\n");
        long bestBandwidth = -1;
        String best = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            long bw = parseBandwidth(line);
            for (int j = i + 1; j < lines.length; j++) {
                String candidate = lines[j].trim();
                if (candidate.isEmpty()) continue;
                if (candidate.startsWith("#")) break;
                if (bw > bestBandwidth) {
                    bestBandwidth = bw;
                    best = resolve(masterUrl, candidate);
                }
                break;
            }
        }
        if (best == null) throw new IllegalArgumentException("No playable HLS variant found");
        return best;
    }

    private long parseBandwidth(String line) {
        try {
            int p = line.indexOf("BANDWIDTH=");
            if (p < 0) return 0;
            int s = p + 10;
            int e = line.indexOf(',', s);
            String v = e < 0 ? line.substring(s) : line.substring(s, e);
            return Long.parseLong(v.trim());
        } catch (Exception ignored) { return 0; }
    }

    private String extractMapUri(String playlist) {
        for (String line : playlist.split("\\r?\\n")) {
            line = line.trim();
            if (!line.startsWith("#EXT-X-MAP:")) continue;
            int p = line.indexOf("URI=\"");
            if (p < 0) continue;
            int s = p + 5;
            int e = line.indexOf('"', s);
            if (e > s) return line.substring(s, e);
        }
        return null;
    }

    private String fetchText(String url, String cookie, String ua, String referer) throws Exception {
        HttpURLConnection c = open(url, cookie, ua, referer);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder b = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) b.append(line).append('\n');
                return b.toString();
            }
        } finally { c.disconnect(); }
    }

    private void copyUrl(String url, OutputStream out, String cookie, String ua, String referer) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection c = null;
            try {
                c = open(url, cookie, ua, referer);
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("Segment HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                }
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(400L * (attempt + 1));
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last == null ? new IllegalStateException("Segment failed") : last;
    }

    private HttpURLConnection open(String url, String cookie, String ua, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "*/*");
        if (ua != null && !ua.trim().isEmpty()) c.setRequestProperty("User-Agent", ua);
        if (cookie != null && !cookie.trim().isEmpty()) c.setRequestProperty("Cookie", cookie);
        if (referer != null && !referer.trim().isEmpty()) c.setRequestProperty("Referer", referer);
        return c;
    }

    private String resolve(String base, String part) throws Exception {
        return URI.create(base).resolve(part).toString();
    }

    private String sanitize(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String compact(String s) {
        if (s == null || s.trim().isEmpty()) return "Unknown error";
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private Notification notification(String text, int progress, boolean indeterminate) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("VideoGrab")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(indeterminate || progress < 100);
        if (indeterminate) b.setProgress(100, 0, true);
        else if (progress < 100) b.setProgress(100, progress, false);
        return b.build();
    }

    private void updateNotification(String text, int progress) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text, progress, false));
    }

    private void notifyDone(String text) {
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("VideoGrab")
                .setContentText(text)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, n);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
