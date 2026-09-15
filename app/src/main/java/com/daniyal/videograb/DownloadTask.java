package com.daniyal.videograb;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

public class DownloadTask {
    public static final String KIND_DIRECT = "direct";
    public static final String KIND_HLS = "hls";
    public static final String KIND_DASH = "dash";
    public static final String MODE_VIDEO = "video";
    public static final String MODE_MP3 = "mp3";
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_DOWNLOADING = "downloading";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELED = "canceled";

    public String id = UUID.randomUUID().toString();
    public String name = "video";
    public String url = "";
    public String kind = KIND_DIRECT;
    public String mode = MODE_VIDEO;
    public String quality = "Original";
    public String status = STATUS_QUEUED;
    public String userAgent = "";
    public String cookie = "";
    public String referer = "";
    public String tempPath = "";
    public String outputUri = "";
    public String error = "";
    public String mime = "video/mp4";
    public int progress = 0;
    public int mp3Bitrate = 192;
    public int segmentIndex = 0;
    public int dashVideoStream = -1;
    public int dashAudioStream = -1;
    public long downloaded = 0;
    public long total = -1;
    public long speedBps = 0;
    public long updatedAt = System.currentTimeMillis();
    public long createdAt = System.currentTimeMillis();

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("name", name); o.put("url", url); o.put("kind", kind); o.put("mode", mode);
        o.put("quality", quality); o.put("status", status); o.put("userAgent", userAgent); o.put("cookie", cookie);
        o.put("referer", referer); o.put("tempPath", tempPath); o.put("outputUri", outputUri); o.put("error", error);
        o.put("mime", mime); o.put("progress", progress); o.put("mp3Bitrate", mp3Bitrate); o.put("segmentIndex", segmentIndex);
        o.put("dashVideoStream", dashVideoStream); o.put("dashAudioStream", dashAudioStream);
        o.put("downloaded", downloaded); o.put("total", total); o.put("speedBps", speedBps); o.put("updatedAt", updatedAt); o.put("createdAt", createdAt);
        return o;
    }

    public static DownloadTask fromJson(JSONObject o) {
        DownloadTask t = new DownloadTask();
        t.id = o.optString("id", t.id); t.name = o.optString("name", "video"); t.url = o.optString("url", "");
        t.kind = o.optString("kind", KIND_DIRECT); t.mode = o.optString("mode", MODE_VIDEO); t.quality = o.optString("quality", "Original");
        t.status = o.optString("status", STATUS_QUEUED); t.userAgent = o.optString("userAgent", ""); t.cookie = o.optString("cookie", "");
        t.referer = o.optString("referer", ""); t.tempPath = o.optString("tempPath", ""); t.outputUri = o.optString("outputUri", "");
        t.error = o.optString("error", ""); t.mime = o.optString("mime", "video/mp4"); t.progress = o.optInt("progress", 0);
        t.mp3Bitrate = o.optInt("mp3Bitrate", 192); t.segmentIndex = o.optInt("segmentIndex", 0);
        t.dashVideoStream = o.optInt("dashVideoStream", -1); t.dashAudioStream = o.optInt("dashAudioStream", -1);
        t.downloaded = o.optLong("downloaded", 0); t.total = o.optLong("total", -1); t.speedBps = o.optLong("speedBps", 0);
        t.updatedAt = o.optLong("updatedAt", System.currentTimeMillis()); t.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        return t;
    }

    public String shortStatus() {
        switch (status) {
            case STATUS_QUEUED: return "Queued";
            case STATUS_DOWNLOADING: return KIND_DASH.equals(kind) ? "Processing DASH • " + progress + "%" : "Downloading " + progress + "%";
            case STATUS_PAUSED: return "Paused • " + progress + "%";
            case STATUS_COMPLETED: return "Completed";
            case STATUS_FAILED: return "Failed";
            case STATUS_CANCELED: return "Canceled";
            default: return status;
        }
    }
}
