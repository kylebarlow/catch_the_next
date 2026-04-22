import re
import sys
import time
import functools
import bottle
from collections import OrderedDict
from datetime import datetime, timezone, timedelta

from config import load_config

_cfg = load_config()
_log_path = _cfg["ACCESS_LOG_PATH"]
_limit = _cfg["RATE_LIMIT_PER_HOUR"]
_tail_bytes = _cfg["RATE_LIMIT_TAIL_BYTES"]

_LOG_RE = re.compile(r'^(\S+) \S+ \S+ \[([^\]]+)\]')
_TS_FMT = "%d/%b/%Y:%H:%M:%S %z"
_CACHE_TTL = 15
_CACHE_MAX = 1000
_WINDOW = timedelta(hours=1)
_log_missing_warned = False

# OrderedDict as a simple LRU: {ip: (count, checked_at)}
_cache = OrderedDict()


def _parse_log_count(ip):
    global _log_missing_warned
    now = datetime.now(tz=timezone.utc)
    cutoff = now - _WINDOW
    count = 0
    oldest_in_window = None

    try:
        with open(_log_path, "rb") as f:
            try:
                f.seek(-_tail_bytes, 2)
                f.read(512)  # skip to next newline boundary
            except OSError:
                f.seek(0)
            data = f.read().decode("utf-8", errors="replace")
    except FileNotFoundError:
        if not _log_missing_warned:
            print(f"WARNING: access log not found at {_log_path}, rate limiting disabled", file=sys.stderr)
            _log_missing_warned = True
        return 0, None

    for line in data.splitlines():
        m = _LOG_RE.match(line)
        if not m:
            continue
        try:
            ts = datetime.strptime(m.group(2), _TS_FMT)
        except ValueError:
            continue
        if ts < cutoff:
            continue
        if m.group(1) == ip:
            count += 1
            if oldest_in_window is None or ts < oldest_in_window:
                oldest_in_window = ts

    return count, oldest_in_window


def check_rate_limit(ip):
    """Returns (allowed: bool, retry_after_seconds: int)."""
    now = time.monotonic()
    entry = _cache.get(ip)
    if entry and now - entry[1] < _CACHE_TTL:
        count, oldest = entry[0], entry[2]
    else:
        count, oldest = _parse_log_count(ip)
        if len(_cache) >= _CACHE_MAX:
            _cache.popitem(last=False)
        _cache[ip] = (count, now, oldest)
        _cache.move_to_end(ip)

    if count >= _limit:
        if oldest:
            retry_after = max(1, int((_WINDOW - (datetime.now(tz=timezone.utc) - oldest)).total_seconds()))
        else:
            retry_after = 3600
        return False, retry_after

    return True, 0


def require_rate_limit(fn):
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        ip = _get_client_ip()
        allowed, retry_after = check_rate_limit(ip)
        if not allowed:
            raise bottle.HTTPResponse(
                body='{"error":"rate_limited"}',
                status=429,
                headers={"Content-Type": "application/json", "Retry-After": str(retry_after)},
            )
        return fn(*args, **kwargs)
    return wrapper


def _get_client_ip():
    xff = bottle.request.environ.get("HTTP_X_FORWARDED_FOR", "")
    if xff:
        return xff.split(",")[-1].strip()
    return bottle.request.environ.get("REMOTE_ADDR", "unknown")
