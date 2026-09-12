package com.daniyal.videograb;

import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.FFprobeSession;
import com.arthenica.ffmpegkit.ReturnCode;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DashInspector {
    public static class Option {
        public final String label;
        public final int videoStream;
        public final int audioStream;
        public final int height;
        public final long bitrate;
        Option(String label, int videoStream, int audioStream, int height, long bitrate) {
            this.label = label; this.videoStream = videoStream; this.audioStream = audioStream; this.height = height; this.bitrate = bitrate;
        }
    }

    private DashInspector() { }

    public static List<Option> inspect(String url, String cookie, String userAgent, String referer) throws Exception {
        String mpd = HlsInspector.fetchText(url, cookie, userAgent, referer);
        String lower = mpd.toLowerCase();
        if (lower.contains("<contentprotection") || lower.contains("urn:mpeg:cenc") || lower.contains("widevine") || lower.contains("playready")) {
            throw new IllegalArgumentException("DRM/protected DASH is not supported");
        }

        String headers = headers(cookie, userAgent, referer);
        String cmd = "-v error -print_format json -show_streams " + (headers.isEmpty() ? "" : "-headers " + q(headers) + " ") + q(url);
        FFprobeSession session = FFprobeKit.execute(cmd);
        if (!ReturnCode.isSuccess(session.getReturnCode())) throw new IllegalStateException("Could not inspect DASH streams");
        String output = session.getOutput();
        JSONObject root = new JSONObject(output == null ? "{}" : output);
        JSONArray streams = root.optJSONArray("streams");
        if (streams == null || streams.length() == 0) throw new IllegalArgumentException("No DASH streams found");

        int bestAudio = -1;
        long bestAudioRate = -1;
        Map<Integer, Option> byHeight = new LinkedHashMap<>();
        List<Option> unknown = new ArrayList<>();

        for (int i = 0; i < streams.length(); i++) {
            JSONObject s = streams.optJSONObject(i); if (s == null) continue;
            int index = s.optInt("index", i);
            String type = s.optString("codec_type", "");
            long rate = parseLong(s.optString("bit_rate", "0"));
            if ("audio".equals(type)) {
                if (rate > bestAudioRate) { bestAudioRate = rate; bestAudio = index; }
                continue;
            }
            if (!"video".equals(type)) continue;
            int h = s.optInt("height", 0);
            String codec = s.optString("codec_name", "video");
            String label = h > 0 ? h + "p • " + codec.toUpperCase() : "Video • " + codec.toUpperCase();
            Option o = new Option(label, index, -1, h, rate);
            if (h > 0) {
                Option old = byHeight.get(h);
                if (old == null || rate > old.bitrate) byHeight.put(h, o);
            } else unknown.add(o);
        }

        List<Option> out = new ArrayList<>(byHeight.values()); out.addAll(unknown);
        final int audioIndex = bestAudio;
        List<Option> paired = new ArrayList<>();
        for (Option o : out) paired.add(new Option(o.label + (audioIndex >= 0 ? " + audio" : " • video only"), o.videoStream, audioIndex, o.height, o.bitrate));
        paired.sort(Comparator.<Option>comparingInt(o -> o.height).thenComparingLong(o -> o.bitrate).reversed());
        if (paired.isEmpty()) throw new IllegalArgumentException("No video representations found in DASH manifest");
        return paired;
    }

    public static int bestAudioStream(String url, String cookie, String userAgent, String referer) throws Exception {
        List<Option> options = inspect(url, cookie, userAgent, referer);
        return options.get(0).audioStream;
    }

    public static String headers(String cookie, String userAgent, String referer) {
        StringBuilder b = new StringBuilder();
        if (userAgent != null && !userAgent.isEmpty()) b.append("User-Agent: ").append(userAgent.replace("\r", "").replace("\n", "")).append("\r\n");
        if (referer != null && !referer.isEmpty()) b.append("Referer: ").append(referer.replace("\r", "").replace("\n", "")).append("\r\n");
        if (cookie != null && !cookie.isEmpty()) b.append("Cookie: ").append(cookie.replace("\r", "").replace("\n", "")).append("\r\n");
        return b.toString();
    }

    public static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static long parseLong(String s) { try { return Long.parseLong(s == null || s.isEmpty() ? "0" : s); } catch (Exception e) { return 0; } }
}
