package com.daniyal.videograb;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.URLUtil;

public final class DirectDownloader {
    private DirectDownloader() { }

    public static long enqueue(Context context, String url, String userAgent, String cookie, String referer) {
        String name = URLUtil.guessFileName(url, null, null);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
        r.setTitle(name);
        r.setDescription("Downloading video");
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        r.setAllowedOverMetered(true);
        r.setAllowedOverRoaming(false);
        r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
        if (userAgent != null && !userAgent.trim().isEmpty()) r.addRequestHeader("User-Agent", userAgent);
        if (cookie != null && !cookie.trim().isEmpty()) r.addRequestHeader("Cookie", cookie);
        if (referer != null && !referer.trim().isEmpty()) r.addRequestHeader("Referer", referer);
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long id = dm.enqueue(r);
        DownloadHistoryStore.add(context, name, url);
        return id;
    }
}
