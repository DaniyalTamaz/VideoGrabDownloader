package com.daniyal.videograb;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

public final class MediaCandidate {
    public enum Kind { DIRECT, HLS, DASH }

    public final String url;
    public final Kind kind;

    public MediaCandidate(String url, Kind kind) {
        this.url = url;
        this.kind = kind;
    }

    public static MediaCandidate fromUrl(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return null;
        String clean = u.toLowerCase(Locale.ROOT);
        int q = clean.indexOf('?');
        String path = q >= 0 ? clean.substring(0, q) : clean;
        if (path.matches(".*\\.(mp4|m4v|webm|mov|3gp|mkv)$")) return new MediaCandidate(u, Kind.DIRECT);
        if (path.endsWith(".m3u8")) return new MediaCandidate(u, Kind.HLS);
        if (path.endsWith(".mpd")) return new MediaCandidate(u, Kind.DASH);
        return null;
    }

    public String displayName() {
        try {
            String p = new URI(url).getPath();
            if (p != null && !p.trim().isEmpty()) {
                String n = p.substring(p.lastIndexOf('/') + 1);
                if (!n.trim().isEmpty()) return kind.name() + " • " + n;
            }
        } catch (Exception ignored) { }
        return kind.name() + " • " + url;
    }

    @Override public boolean equals(Object o) {
        return o instanceof MediaCandidate && url.equals(((MediaCandidate) o).url);
    }

    @Override public int hashCode() { return Objects.hash(url); }
}
