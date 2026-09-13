#!/usr/bin/env python3
import concurrent.futures
import datetime as dt
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

from check_stream_health import test_stream

API = "https://iptv-org.github.io/api"
WORKERS = 16
DEFAULT_LIMIT = 30


def utc_now():
    return dt.datetime.now(dt.timezone.utc).isoformat().replace('+00:00', 'Z')


def fetch_json(name):
    req = urllib.request.Request(
        f"{API}/{name}.json",
        headers={
            "User-Agent": "SportNexa-CatalogScanner/1.0",
            "Accept": "application/json",
            "Accept-Encoding": "identity",
        },
    )
    with urllib.request.urlopen(req, timeout=45) as response:
        return json.load(response)


def clean(value):
    return str(value or "").strip()


def stream_format(url):
    path = urllib.parse.urlparse(url).path.lower()
    if path.endswith(".m3u8"):
        return "HLS"
    if path.endswith(".mpd"):
        return "DASH"
    return ""


def quality_score(value):
    match = re.search(r"(\d{3,4})p", clean(value), re.I)
    return int(match.group(1)) if match else 0


def feed_key(channel, feed):
    return f"{channel or ''}::{feed or ''}"


def build_candidates(channels, feeds, streams, blocklist):
    channel_by_id = {clean(x.get("id")): x for x in channels if clean(x.get("id"))}
    blocked = {clean(x.get("channel")) for x in blocklist if clean(x.get("channel"))}

    feed_by_key = {}
    main_feed = {}
    for feed in feeds:
        channel_id = clean(feed.get("channel"))
        feed_id = clean(feed.get("id"))
        if channel_id:
            feed_by_key[feed_key(channel_id, feed_id)] = feed
            if feed.get("is_main") is True and channel_id not in main_feed:
                main_feed[channel_id] = feed

    best = {}
    for stream in streams:
        channel_id = clean(stream.get("channel"))
        if not channel_id or channel_id in blocked:
            continue
        channel = channel_by_id.get(channel_id)
        if not channel or channel.get("is_nsfw") is True:
            continue

        url = clean(stream.get("url"))
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            continue
        fmt = stream_format(url)
        if not fmt:
            continue
        if clean(stream.get("referrer")) or clean(stream.get("user_agent")):
            continue

        feed_id = clean(stream.get("feed"))
        feed = feed_by_key.get(feed_key(channel_id, feed_id)) or main_feed.get(channel_id) or {}
        categories = [clean(x) for x in (channel.get("categories") or []) if clean(x)]
        countries = set()
        country = clean(channel.get("country")).upper()
        if country:
            countries.add(country)
        for area in feed.get("broadcast_area") or []:
            match = re.match(r"^c/(.+)$", clean(area), re.I)
            if match:
                countries.add(match.group(1).upper())
        languages = {clean(x) for x in (feed.get("languages") or []) if clean(x)}

        candidate = {
            "channelId": channel_id,
            "name": clean(channel.get("name")) or clean(stream.get("title")) or channel_id,
            "title": clean(stream.get("title")) or clean(channel.get("name")) or channel_id,
            "url": url,
            "format": fmt,
            "quality": clean(stream.get("quality")),
            "label": clean(stream.get("label")),
            "country": country,
            "countries": sorted(countries),
            "languages": sorted(languages),
            "categories": categories,
            "website": clean(channel.get("website")),
        }
        score = quality_score(candidate["quality"])
        score += 40 if parsed.scheme == "https" else 0
        score += 20 if fmt == "HLS" else 10
        score += 5 if not candidate["label"] else 0
        score += 5 if candidate["website"].startswith(("http://", "https://")) else 0
        candidate["score"] = score

        old = best.get(channel_id)
        if old is None or candidate["score"] > old["score"]:
            best[channel_id] = candidate

    return list(best.values())


def matches(candidate, preset):
    category = clean(preset.get("category"))
    country = clean(preset.get("country")).upper()
    language = clean(preset.get("language"))
    if category and category not in candidate["categories"]:
        return False
    if country and country not in candidate["countries"]:
        return False
    if language and language not in candidate["languages"]:
        return False
    return True


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: scan_iptv_catalog.py request.json results.json")

    request_path = Path(sys.argv[1])
    result_path = Path(sys.argv[2])
    request = json.loads(request_path.read_text(encoding="utf-8"))
    presets = request.get("presets") if isinstance(request.get("presets"), list) else []
    if not presets:
        raise SystemExit("scan request contains no presets")

    channels, feeds, streams, blocklist = [
        fetch_json(name) for name in ("channels", "feeds", "streams", "blocklist")
    ]
    candidates = build_candidates(channels, feeds, streams, blocklist)

    selected_by_preset = []
    unique = {}
    for preset in presets:
        pool = [c for c in candidates if matches(c, preset)]
        pool.sort(key=lambda c: (-c["score"], c["name"].casefold()))
        limit = max(1, min(60, int(preset.get("limit") or DEFAULT_LIMIT)))
        chosen = pool[:limit]
        selected_by_preset.append((preset, len(pool), chosen))
        for item in chosen:
            unique[item["url"]] = item

    health_by_url = {}
    items = list(unique.values())
    if items:
        with concurrent.futures.ThreadPoolExecutor(max_workers=min(WORKERS, len(items))) as executor:
            futures = {
                executor.submit(test_stream, {"url": item["url"], "name": item["name"]}): item["url"]
                for item in items
            }
            for future in concurrent.futures.as_completed(futures):
                health_by_url[futures[future]] = future.result()

    preset_results = []
    total_working = 0
    for preset, candidate_count, chosen in selected_by_preset:
        results = []
        counts = {}
        for candidate in chosen:
            health = health_by_url.get(candidate["url"], {})
            status = clean(health.get("status")) or "error"
            counts[status] = counts.get(status, 0) + 1
            if status == "working":
                total_working += 1
            item = dict(candidate)
            item.pop("score", None)
            item["health"] = health
            results.append(item)

        preset_results.append({
            "id": clean(preset.get("id")) or clean(preset.get("label")),
            "label": clean(preset.get("label")) or clean(preset.get("id")) or "Scan",
            "filters": {
                "category": clean(preset.get("category")),
                "country": clean(preset.get("country")).upper(),
                "language": clean(preset.get("language")),
            },
            "candidateCount": candidate_count,
            "testedCount": len(chosen),
            "counts": counts,
            "results": results,
        })

    payload = {
        "version": "1",
        "scanId": clean(request.get("scanId")),
        "requestedAt": clean(request.get("createdAt")),
        "testedAt": utc_now(),
        "source": "IPTV-org public API",
        "note": "Technical reachability only. A working result does not establish redistribution or embedding rights. Geo-restricted streams can differ on the user's device.",
        "catalog": {
            "channels": len(channels),
            "streams": len(streams),
            "eligibleBestPerChannel": len(candidates),
        },
        "uniqueStreamsTested": len(items),
        "workingPresetHits": total_working,
        "presets": preset_results,
    }
    result_path.parent.mkdir(parents=True, exist_ok=True)
    result_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({
        "scanId": payload["scanId"],
        "uniqueStreamsTested": len(items),
        "workingPresetHits": total_working,
        "presetCounts": {x["id"]: x["counts"] for x in preset_results},
    }))


if __name__ == "__main__":
    main()
