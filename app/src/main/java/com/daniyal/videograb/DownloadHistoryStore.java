package com.daniyal.videograb;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class DownloadHistoryStore {
    private static final String PREF = "download_history";
    private static final String KEY = "items";

    private DownloadHistoryStore() { }

    public static void add(Context context, String name, String url) {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String old = p.getString(KEY, "");
        String safeName = name.replace("\n", " ").replace("\t", " ");
        String line = System.currentTimeMillis() + "\t" + safeName + "\t" + url.replace("\n", "");
        String merged = line + (old.isEmpty() ? "" : "\n" + old);
        String[] rows = merged.split("\n");
        if (rows.length > 100) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                if (i > 0) b.append('\n');
                b.append(rows[i]);
            }
            merged = b.toString();
        }
        p.edit().putString(KEY, merged).apply();
    }

    public static List<String> list(Context context) {
        String data = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "");
        List<String> result = new ArrayList<>();
        if (data.isEmpty()) return result;
        for (String row : data.split("\n")) {
            String[] f = row.split("\t", 3);
            if (f.length < 2) continue;
            try {
                String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(Long.parseLong(f[0])));
                result.add(f[1] + "\n" + when);
            } catch (Exception ignored) {
                result.add(f[1]);
            }
        }
        return result;
    }
}
