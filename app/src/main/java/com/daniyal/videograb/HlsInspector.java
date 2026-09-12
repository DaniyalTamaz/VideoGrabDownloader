package com.daniyal.videograb;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class HlsInspector {
    public static class Option {
        public final String label; public final String url; public final int height; public final long bandwidth;
        Option(String label, String url, int height, long bandwidth) { this.label = label; this.url = url; this.height = height; this.bandwidth = bandwidth; }
    }
    private HlsInspector() { }

    public static List<Option> inspect(String source, String cookie, String userAgent, String referer) throws Exception {
        String text = fetchText(source, cookie, userAgent, referer);
        if (!text.startsWith("#EXTM3U")) throw new IllegalArgumentException("Not an HLS playlist");
        List<Option> out = new ArrayList<>();
        if (!text.contains("#EXT-X-STREAM-INF")) { out.add(new Option("Original HLS", source, 0, 0)); return out; }
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim(); if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            int height = parseHeight(line); long bw = parseLongAttr(line, "BANDWIDTH"); String name = parseStringAttr(line, "NAME");
            String next = null;
            for (int j = i + 1; j < lines.length; j++) { String candidate = lines[j].trim(); if (candidate.isEmpty()) continue; if (candidate.startsWith("#")) break; next = candidate; break; }
            if (next == null) continue;
            String label;
            if (height > 0) label = height + "p"; else if (!name.isEmpty()) label = name; else if (bw > 0) label = String.format(Locale.US, "%.1f Mbps", bw / 1_000_000.0); else label = "HLS variant";
            out.add(new Option(label, URI.create(source).resolve(next).toString(), height, bw));
        }
        out.sort(Comparator.<Option>comparingInt(o -> o.height).thenComparingLong(o -> o.bandwidth).reversed());
        if (out.isEmpty()) out.add(new Option("Original HLS", source, 0, 0));
        return out;
    }

    static String fetchText(String url, String cookie, String userAgent, String referer) throws Exception {
        HttpURLConnection c = open(url, cookie, userAgent, referer);
        try {
            int code = c.getResponseCode(); if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder b = new StringBuilder(); String line; while ((line = r.readLine()) != null) b.append(line).append('\n'); return b.toString();
            }
        } finally { c.disconnect(); }
    }

    static HttpURLConnection open(String url, String cookie, String userAgent, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true); c.setRequestProperty("Accept", "*/*");
        if (userAgent != null && !userAgent.isEmpty()) c.setRequestProperty("User-Agent", userAgent);
        if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
        return c;
    }

    private static int parseHeight(String line) { try { String v = attrValue(line, "RESOLUTION"); int x = v.toLowerCase(Locale.ROOT).indexOf('x'); return x > 0 ? Integer.parseInt(v.substring(x + 1).replaceAll("[^0-9].*$", "")) : 0; } catch (Exception e) { return 0; } }
    private static long parseLongAttr(String line, String key) { try { return Long.parseLong(attrValue(line, key).replaceAll("[^0-9].*$", "")); } catch (Exception e) { return 0; } }
    private static String parseStringAttr(String line, String key) { String v = attrValue(line, key); if (v.startsWith("\"") && v.endsWith("\"") && v.length() > 1) return v.substring(1, v.length() - 1); return v; }
    private static String attrValue(String line, String key) {
        String marker = key + "="; int p = line.indexOf(marker); if (p < 0) return ""; int s = p + marker.length();
        if (s < line.length() && line.charAt(s) == '"') { int e = line.indexOf('"', s + 1); return e > s ? line.substring(s, e + 1) : ""; }
        int e = line.indexOf(',', s); return (e < 0 ? line.substring(s) : line.substring(s, e)).trim();
    }
}
