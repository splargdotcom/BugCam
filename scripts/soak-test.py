#!/usr/bin/env python3
"""Read-only LAN soak test. No phone settings are changed. Python standard library only."""
import argparse
import concurrent.futures
import datetime
import json
import threading
import time
import urllib.error
import urllib.request


def fetch(url, timeout=10):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read()


def sample_stream(base, seconds, stop):
    frames = 0
    errors = 0
    while not stop.is_set():
        try:
            with urllib.request.urlopen(base + '/stream', timeout=15) as response:
                until = time.monotonic() + seconds
                while not stop.is_set() and time.monotonic() < until:
                    line = response.readline(4096)
                    if not line:
                        raise EOFError('stream ended')
                    if line.strip() != b'--bugcamframe':
                        continue
                    headers = {}
                    while True:
                        line = response.readline(4096)
                        if line in (b'\r\n', b'\n'):
                            break
                        if not line:
                            raise EOFError('part headers ended')
                        key, value = line.decode('ascii').split(':', 1)
                        headers[key.lower()] = value.strip()
                    length = int(headers['content-length'])
                    if not 0 < length <= 16 * 1024 * 1024:
                        raise ValueError('invalid JPEG length')
                    jpeg = response.read(length)
                    if len(jpeg) != length or not jpeg.startswith(b'\xff\xd8') or not jpeg.endswith(b'\xff\xd9'):
                        raise ValueError('truncated or invalid JPEG')
                    if response.read(2) != b'\r\n':
                        raise ValueError('missing multipart CRLF')
                    frames += 1
        except (OSError, EOFError, ValueError) as exc:
            errors += 1
            print(json.dumps({'stream_error': str(exc)}), flush=True)
            stop.wait(3)
    return {'stream_frames': frames, 'stream_errors': errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('url', help='e.g. http://192.168.1.50:8080')
    parser.add_argument('--minutes', type=float, default=60)
    parser.add_argument('--interval', type=float, default=10)
    parser.add_argument('--stream', action='store_true', help='also validate MJPEG continuously')
    args = parser.parse_args()
    if args.minutes <= 0 or args.interval <= 0:
        parser.error('minutes and interval must be positive')
    base = args.url.rstrip('/')
    stop = threading.Event()
    deadline = time.monotonic() + args.minutes * 60
    last_count = None
    last_uptime = None
    failures = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        stream = pool.submit(sample_stream, base, 120, stop) if args.stream else None
        try:
            while time.monotonic() < deadline:
                record = {'time_utc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
                try:
                    health = json.loads(fetch(base + '/health'))
                    restarted = last_uptime is not None and health['service_uptime_seconds'] < last_uptime
                    advancing = last_count is None or restarted or health['frame_count'] > last_count
                    jpeg = fetch(base + '/snapshot.jpg')
                    valid = jpeg.startswith(b'\xff\xd8') and jpeg.endswith(b'\xff\xd9')
                    ok = bool(health['camera_active'] and advancing and valid)
                    record.update(health, ok=ok, snapshot_bytes=len(jpeg), service_restarted=restarted)
                    failures += not ok
                    last_count = health['frame_count']
                    last_uptime = health['service_uptime_seconds']
                except (OSError, ValueError, KeyError) as exc:
                    record.update(ok=False, error=str(exc))
                    failures += 1
                print(json.dumps(record), flush=True)
                stop.wait(min(args.interval, max(0, deadline - time.monotonic())))
        except KeyboardInterrupt:
            pass
        finally:
            stop.set()
        if stream:
            result = stream.result(timeout=20)
            print(json.dumps(result), flush=True)
            failures += result['stream_errors']
    print(json.dumps({'failed_samples_or_stream_connections': failures}), flush=True)
    return 1 if failures else 0


if __name__ == '__main__':
    raise SystemExit(main())
