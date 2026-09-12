package com.daniyal.videograb;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

public final class MediaCandidate {
    public enum Kind { DIRECT, HLS, DASH }

    public String url;
    public final Kind kind;
    public int width;
    public int height;
    public double duration;
    public boolean fromMediaElement;
    public boolean playing;
    public long lastSeen = System.currentTimeMillis();

    public MediaCandidate(String url, Kind kind) {
        this.url = url;
        this.kind = kind;
    }

    public static MediaCandidate fromUrl(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return null;
        String clean = u.toLowerCase(Locale.ROOT);
        int hash = clean.indexOf('#');
        if (hash >= 0) clean = clean.substring(0, hash);
        int q = clean.indexOf('?');
        String path = q >= 0 ? clean.substring(0, q) : clean;
        if (path.matches(".*\\.(mp4|m4v|webm|mov|3gp|mkv)$")) return new MediaCandidate(u, Kind.DIRECT);
        if (path.endsWith(".m3u8")) return new MediaCandidate(u, Kind.HLS);
        if (path.endsWith(".mpd")) return new MediaCandidate(u, Kind.DASH);
        return null;
    }

    public String canonicalKey() {
        try {
            URI u = new URI(url);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            String path = u.getPath() == null ? "" : u.getPath().toLowerCase(Locale.ROOT);
            return kind.name() + "|" + host + "|" + path;
        } catch (Exception e) {
            String x = url;
            int q = x.indexOf('?');
            if (q >= 0) x = x.substring(0, q);
            return kind.name() + "|" + x.toLowerCase(Locale.ROOT);
        }
    }

    public void mergeFrom(MediaCandidate newer) {
        if (newer == null) return;
        this.url = newer.url; // newest signed/tokenized URL wins
        this.lastSeen = System.currentTimeMillis();
        if (newer.fromMediaElement) this.fromMediaElement = true;
        if (newer.playing) this.playing = true;
        if (newer.width > 0) this.width = Math.max(this.width, newer.width);
        if (newer.height > 0) this.height = Math.max(this.height, newer.height);
        if (newer.duration > 0) this.duration = Math.max(this.duration, newer.duration);
    }

    public int score(String pageHost) {
        int s = 0;
        if (fromMediaElement) s += 900;
        if (playing) s += 900;
        long area = (long) width * height;
        if (area >= 1280L * 720L) s += 450;
        else if (area >= 640L * 360L) s += 300;
        else if (area > 0 && area <= 320L * 180L) s -= 350;
        if (duration >= 120) s += 350;
        else if (duration >= 30) s += 200;
        else if (duration > 0 && duration < 15) s -= 350;
        if (kind == Kind.HLS || kind == Kind.DASH) s += 100;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("doubleclick") || lower.contains("/ads/") || lower.contains("/ad/") || lower.contains("preroll") || lower.contains("pre-roll") || lower.contains("vast") || lower.contains("promo")) s -= 800;
        try {
            String host = new URI(url).getHost();
            if (pageHost != null && host != null && (host.equalsIgnoreCase(pageHost) || host.endsWith("." + pageHost))) s += 100;
        } catch (Exception ignored) { }
        return s;
    }

    public String displayName(String pageHost, boolean likelyMain) {
        StringBuilder b = new StringBuilder();
        if (likelyMain) b.append("★ Likely main video\n");
        b.append(kind.name());
        if (width > 0 && height > 0) b.append(" • ").append(width).append("×").append(height);
        if (duration > 0 && duration < 24 * 3600) {
            int sec = (int) Math.round(duration);
            b.append(" • ").append(sec / 60).append(':').append(String.format(Locale.US, "%02d", sec % 60));
        }
        if (playing) b.append(" • Playing");
        try {
            URI u = new URI(url);
            String host = u.getHost();
            if (host != null && !host.isEmpty()) b.append("\n").append(host);
            String p = u.getPath();
            if (p != null && !p.isEmpty()) {
                String n = p.substring(p.lastIndexOf('/') + 1);
                if (!n.isEmpty()) b.append(" • ").append(n.length() > 42 ? n.substring(0, 42) + "…" : n);
            }
        } catch (Exception ignored) { }
        return b.toString();
    }

    public String displayName() { return displayName(null, false); }

    @Override public boolean equals(Object o) {
        return o instanceof MediaCandidate && canonicalKey().equals(((MediaCandidate) o).canonicalKey());
    }

    @Override public int hashCode() { return Objects.hash(canonicalKey()); }
}
