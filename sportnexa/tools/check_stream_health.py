#!/usr/bin/env python3
import concurrent.futures
import datetime as dt
import json
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

MAX_STREAMS = 30
MAX_BYTES = 131072
TIMEOUT_SECONDS = 12
WORKERS = 8


def utc_now():
    return dt.datetime.now(dt.timezone.utc).isoformat().replace('+00:00', 'Z')


def detect_format(url, content_type, body):
    path = urllib.parse.urlparse(url).path.lower()
    text = body.decode('utf-8', errors='ignore').lstrip('\ufeff\x00 \t\r\n')
    ctype = (content_type or '').lower()
    if '#EXTM3U' in text[:4096] or path.endswith('.m3u8') and '#EXT' in text[:16384]:
        return 'HLS'
    if '<MPD' in text[:16384] or 'application/dash+xml' in ctype or path.endswith('.mpd') and '<' in text[:4096]:
        return 'DASH'
    return ''


def classify_http(code):
    if code in (401, 403, 451):
        return 'restricted'
    if code in (404, 410):
        return 'dead'
    return 'http_error'


def test_stream(item):
    url = str(item.get('url') or '').strip()
    name = str(item.get('name') or '').strip()
    result = {
        'url': url,
        'name': name,
        'status': 'error',
        'httpStatus': None,
        'format': '',
        'latencyMs': None,
        'contentType': '',
        'finalUrl': '',
        'bytesRead': 0,
        'message': '',
        'checkedAt': utc_now(),
    }
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ('http', 'https') or not parsed.netloc:
        result['status'] = 'invalid'
        result['message'] = 'Invalid HTTP/HTTPS URL'
        return result

    req = urllib.request.Request(
        url,
        headers={
            'User-Agent': 'SportNexa/0.8 Android StreamHealth/1.0',
            'Accept': 'application/vnd.apple.mpegurl, application/x-mpegURL, application/dash+xml, audio/mpegurl, text/plain, */*',
            'Accept-Encoding': 'identity',
            'Connection': 'close',
        },
        method='GET',
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as response:
            result['httpStatus'] = int(response.getcode() or 0)
            result['contentType'] = str(response.headers.get('Content-Type') or '')
            result['finalUrl'] = str(response.geturl() or url)
            body = response.read(MAX_BYTES)
            result['bytesRead'] = len(body)
            result['latencyMs'] = int((time.monotonic() - started) * 1000)
            detected = detect_format(result['finalUrl'], result['contentType'], body)
            result['format'] = detected
            if 200 <= result['httpStatus'] < 300 and detected:
                result['status'] = 'working'
                result['message'] = f'{detected} manifest reachable'
            elif 200 <= result['httpStatus'] < 300:
                result['status'] = 'http_ok'
                result['message'] = 'HTTP response reachable, but HLS/DASH manifest was not detected'
            else:
                result['status'] = classify_http(result['httpStatus'])
                result['message'] = f'HTTP {result["httpStatus"]}'
    except urllib.error.HTTPError as exc:
        result['httpStatus'] = int(exc.code or 0)
        result['latencyMs'] = int((time.monotonic() - started) * 1000)
        result['finalUrl'] = str(exc.geturl() or url)
        result['status'] = classify_http(result['httpStatus'])
        result['message'] = f'HTTP {result["httpStatus"]}'
    except (socket.timeout, TimeoutError):
        result['latencyMs'] = int((time.monotonic() - started) * 1000)
        result['status'] = 'timeout'
        result['message'] = f'Timed out after {TIMEOUT_SECONDS}s'
    except urllib.error.URLError as exc:
        result['latencyMs'] = int((time.monotonic() - started) * 1000)
        reason = getattr(exc, 'reason', exc)
        if isinstance(reason, socket.timeout):
            result['status'] = 'timeout'
            result['message'] = f'Timed out after {TIMEOUT_SECONDS}s'
        else:
            result['status'] = 'error'
            result['message'] = str(reason)[:180]
    except Exception as exc:
        result['latencyMs'] = int((time.monotonic() - started) * 1000)
        result['status'] = 'error'
        result['message'] = f'{type(exc).__name__}: {exc}'[:180]
    return result


def main():
    if len(sys.argv) != 3:
        raise SystemExit('usage: check_stream_health.py queue.json results.json')
    queue_path = Path(sys.argv[1])
    result_path = Path(sys.argv[2])
    queue = json.loads(queue_path.read_text(encoding='utf-8'))
    streams = queue.get('streams') if isinstance(queue.get('streams'), list) else []
    streams = streams[:MAX_STREAMS]

    results = []
    if streams:
        with concurrent.futures.ThreadPoolExecutor(max_workers=min(WORKERS, len(streams))) as pool:
            future_map = {pool.submit(test_stream, item): i for i, item in enumerate(streams)}
            indexed = {}
            for future in concurrent.futures.as_completed(future_map):
                indexed[future_map[future]] = future.result()
            results = [indexed[i] for i in range(len(streams))]

    counts = {}
    for item in results:
        counts[item['status']] = counts.get(item['status'], 0) + 1

    payload = {
        'version': '1',
        'queueId': str(queue.get('queueId') or ''),
        'requestedAt': str(queue.get('createdAt') or ''),
        'testedAt': utc_now(),
        'runnerRegionNote': 'Tested from a GitHub-hosted cloud runner. Geo-restricted results can differ on the user device.',
        'counts': counts,
        'results': results,
    }
    result_path.parent.mkdir(parents=True, exist_ok=True)
    result_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps({'queueId': payload['queueId'], 'tested': len(results), 'counts': counts}))


if __name__ == '__main__':
    main()
