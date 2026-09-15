package com.daniyal.videograb;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import androidx.core.content.FileProvider;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class TorrentStorage {
    private static final long MAX_META = 16L * 1024 * 1024;
    private TorrentStorage() { }

    public static File taskDir(Context c, String id) {
        File base = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = new File(c.getFilesDir(), "external-downloads");
        File out = new File(base, "VideoGrab/Torrents/" + safeId(id));
        out.mkdirs();
        return out;
    }

    public static File metadataDir(Context c) {
        File d = new File(c.getFilesDir(), "torrent-meta");
        d.mkdirs();
        return d;
    }

    public static File importTorrentUri(Context c, Uri uri, String taskId) throws IOException {
        File out = new File(metadataDir(c), safeId(taskId) + ".torrent");
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Unable to open .torrent file");
            copyLimited(in, out, MAX_META);
        }
        return out;
    }

    public static File fetchTorrentUrl(Context c, String url, String cookie, String userAgent, String referer, String taskId) throws Exception {
        HttpURLConnection con = HlsInspector.open(url, cookie, userAgent, referer);
        con.setInstanceFollowRedirects(true);
        int code = con.getResponseCode();
        if (code < 200 || code >= 300) { con.disconnect(); throw new IOException("Torrent file HTTP " + code); }
        long len = con.getContentLengthLong();
        if (len > MAX_META) { con.disconnect(); throw new IOException("Torrent metadata file is too large"); }
        File out = new File(metadataDir(c), safeId(taskId) + ".torrent");
        try (InputStream in = con.getInputStream()) { copyLimited(in, out, MAX_META); }
        finally { con.disconnect(); }
        return out;
    }

    private static void copyLimited(InputStream in, File out, long max) throws IOException {
        long total = 0;
        byte[] buffer = new byte[65536];
        try (OutputStream os = new FileOutputStream(out)) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > max) { out.delete(); throw new IOException("Torrent metadata exceeds 16 MB"); }
                os.write(buffer, 0, n);
            }
        }
        if (total == 0) { out.delete(); throw new IOException("Torrent metadata file is empty"); }
    }

    public static List<File> files(Context c, String taskId) {
        List<File> out = new ArrayList<>();
        walk(taskDir(c, taskId), out);
        Collections.sort(out, Comparator.comparing(File::getAbsolutePath, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static void walk(File f, List<File> out) {
        File[] children = f.listFiles();
        if (children == null) return;
        for (File x : children) {
            if (x.isDirectory()) walk(x, out);
            else if (x.isFile()) out.add(x);
        }
    }

    public static String relative(Context c, String taskId, File file) {
        String base = taskDir(c, taskId).getAbsolutePath();
        String p = file.getAbsolutePath();
        return p.startsWith(base) ? p.substring(Math.min(p.length(), base.length() + 1)) : file.getName();
    }

    public static Uri uri(Context c, File file) {
        return FileProvider.getUriForFile(c, c.getPackageName() + ".files", file);
    }

    public static String mime(File f) {
        String m = URLConnection.guessContentTypeFromName(f.getName());
        return m == null ? "application/octet-stream" : m;
    }

    public static void deleteTaskData(Context c, String id) { delete(taskDir(c, id)); }
    public static void deleteMetadata(String path) { if (path != null && !path.isEmpty()) new File(path).delete(); }

    private static void delete(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] xs = f.listFiles(); if (xs != null) for (File x : xs) delete(x); }
        f.delete();
    }

    private static String safeId(String id) { return id == null ? "unknown" : id.replaceAll("[^A-Za-z0-9._-]", "_"); }
}
