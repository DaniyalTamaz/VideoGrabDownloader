package com.daniyal.videograb;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class MediaPublisher {
    private MediaPublisher() { }
    public static Uri publish(Context context, File source, String displayName, String mime) throws Exception {
        ContentValues v = new ContentValues(); v.put(MediaStore.MediaColumns.DISPLAY_NAME, sanitize(displayName));
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? guessMime(displayName) : mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VideoGrab"); v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v); if (uri == null) throw new IllegalStateException("Could not create output file");
        boolean ok = false;
        try (InputStream in = new FileInputStream(source); OutputStream out = context.getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IllegalStateException("Could not open output file"); byte[] buf = new byte[128 * 1024]; int n; while ((n = in.read(buf)) >= 0) out.write(buf, 0, n); out.flush(); ok = true;
        } finally {
            if (ok) { ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0); context.getContentResolver().update(uri, done, null, null); }
            else context.getContentResolver().delete(uri, null, null);
        }
        return uri;
    }
    public static Uri convertFileToMp3(Context context, File source, String baseName, int bitrate) throws Exception {
        File out = new File(context.getCacheDir(), "vg_" + System.nanoTime() + ".mp3");
        String command = "-hide_banner -loglevel error -y -i " + source.getAbsolutePath() + " -vn -c:a libmp3lame -b:a " + bitrate + "k " + out.getAbsolutePath();
        FFmpegSession session = FFmpegKit.execute(command);
        if (!ReturnCode.isSuccess(session.getReturnCode()) || !out.exists() || out.length() == 0) {
            String logs = session.getAllLogsAsString(); if (logs != null && logs.length() > 180) logs = logs.substring(logs.length() - 180);
            throw new IllegalStateException("MP3 conversion failed" + (logs == null || logs.isEmpty() ? "" : ": " + logs));
        }
        try { return publish(context, out, stripExtension(baseName) + ".mp3", "audio/mpeg"); } finally { out.delete(); }
    }
    public static File copyUriToCache(Context context, Uri uri, String suffix) throws Exception {
        File out = new File(context.getCacheDir(), "vg_src_" + System.nanoTime() + suffix);
        try (InputStream in = context.getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IllegalStateException("Cannot read selected file"); byte[] buf = new byte[128 * 1024]; int n; while ((n = in.read(buf)) >= 0) os.write(buf, 0, n);
        }
        return out;
    }
    public static String stripExtension(String name) { if (name == null || name.trim().isEmpty()) return "audio"; int q = name.indexOf('?'); if (q >= 0) name = name.substring(0, q); int dot = name.lastIndexOf('.'); if (dot > 0 && name.length() - dot <= 6) return name.substring(0, dot); return name; }
    public static String sanitize(String s) { if (s == null || s.trim().isEmpty()) return "VideoGrab_" + System.currentTimeMillis(); return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim(); }
    public static String guessMime(String name) { String ext = MimeTypeMap.getFileExtensionFromUrl(name == null ? "" : name); String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext == null ? "" : ext.toLowerCase()); return mime == null ? "application/octet-stream" : mime; }
}
