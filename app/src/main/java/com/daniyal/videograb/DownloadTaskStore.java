package com.daniyal.videograb;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class DownloadTaskStore {
    private static final String PREF = "vg_queue_v3";
    private static final String KEY = "tasks";
    private static final Object LOCK = new Object();
    private DownloadTaskStore() { }

    public static List<DownloadTask> list(Context context) {
        synchronized (LOCK) {
            List<DownloadTask> out = read(context);
            Collections.sort(out, Comparator.comparingLong((DownloadTask t) -> t.createdAt).reversed());
            return out;
        }
    }
    public static DownloadTask get(Context context, String id) {
        synchronized (LOCK) { for (DownloadTask t : read(context)) if (t.id.equals(id)) return t; return null; }
    }
    public static void upsert(Context context, DownloadTask task) {
        synchronized (LOCK) {
            List<DownloadTask> tasks = read(context); boolean found = false;
            for (int i = 0; i < tasks.size(); i++) if (tasks.get(i).id.equals(task.id)) { tasks.set(i, task); found = true; break; }
            if (!found) tasks.add(task); write(context, tasks);
        }
    }
    public static void remove(Context context, String id) {
        synchronized (LOCK) { List<DownloadTask> tasks = read(context); tasks.removeIf(t -> t.id.equals(id)); write(context, tasks); }
    }
    private static List<DownloadTask> read(Context context) {
        List<DownloadTask> out = new ArrayList<>();
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(p.getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) out.add(DownloadTask.fromJson(o)); }
        } catch (Exception ignored) { }
        return out;
    }
    private static void write(Context context, List<DownloadTask> tasks) {
        JSONArray a = new JSONArray();
        for (DownloadTask t : tasks) try { a.put(t.toJson()); } catch (Exception ignored) { }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply();
    }
}
