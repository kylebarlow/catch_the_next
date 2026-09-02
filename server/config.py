import os
import sys

def load_config():
    config = {
        "TRANSITLAND_API_KEY": os.environ.get("TRANSITLAND_API_KEY", ""),
        "TRANSITLAND_BASE_URL": os.environ.get("TRANSITLAND_BASE_URL", "https://transit.land/api/v2/rest"),
        "APP_API_KEYS": [k.strip() for k in os.environ.get("APP_API_KEYS", "").split(",") if k.strip()],
        # Public, 511-only keys (e.g. the committed F-Droid "Bay" edition key). The backend
        # hard-scopes these to local Bay Area / 511 data and refuses any Transitland path.
        "APP_API_KEYS_PUBLIC": [k.strip() for k in os.environ.get("APP_API_KEYS_PUBLIC", "").split(",") if k.strip()],
        "RATE_LIMIT_PER_HOUR": int(os.environ.get("RATE_LIMIT_PER_HOUR", "500")),
        "ACCESS_LOG_PATH": os.environ.get("ACCESS_LOG_PATH", "/home/logs/access_log"),
        "RATE_LIMIT_TAIL_BYTES": int(os.environ.get("RATE_LIMIT_TAIL_BYTES", "262144")),
        "UPSTREAM_CONNECT_TIMEOUT": float(os.environ.get("UPSTREAM_CONNECT_TIMEOUT", "5")),
        "UPSTREAM_READ_TIMEOUT": float(os.environ.get("UPSTREAM_READ_TIMEOUT", "10")),
        "STATS_PATH_SECRET": os.environ.get("STATS_PATH_SECRET", ""),
        "STATS_MAX_LOG_BYTES": int(os.environ.get("STATS_MAX_LOG_BYTES", "50000000")),
        "STATS_CACHE_SECONDS": int(os.environ.get("STATS_CACHE_SECONDS", "30")),
        "CACHE_DB_PATH": os.environ.get("CACHE_DB_PATH", "/home/protected/cache.sqlite"),
        # Accept either name; the project root .env historically uses "511_API_KEY".
        "FIVE_ELEVEN_API_KEY": (
            os.environ.get("FIVE_ELEVEN_API_KEY")
            or os.environ.get("511_API_KEY", "")
        ),
        "FIVE_ELEVEN_BASE_URL": os.environ.get("FIVE_ELEVEN_BASE_URL", "https://api.511.org/transit"),
        "FIVE_ELEVEN_AGENCY": os.environ.get("FIVE_ELEVEN_AGENCY", "RG"),
        "GTFS_511_DB_DIR": os.environ.get("GTFS_511_DB_DIR", "/home/protected/gtfs511"),
        "FIVE_ELEVEN_RT_TTL": int(os.environ.get("FIVE_ELEVEN_RT_TTL", "60")),
        # Alerts change rarely; a longer TTL halves the 511 calls per refresh
        # cycle, which matters against the 60-requests/hour key cap.
        "FIVE_ELEVEN_ALERTS_TTL": int(os.environ.get("FIVE_ELEVEN_ALERTS_TTL", "300")),
        "FIVE_ELEVEN_RT_MAX_STALE": int(os.environ.get("FIVE_ELEVEN_RT_MAX_STALE", "180")),
        "FIVE_ELEVEN_STATIC_TTL": int(os.environ.get("FIVE_ELEVEN_STATIC_TTL", str(86400 * 7))),
        "FIVE_ELEVEN_ENABLED": os.environ.get("FIVE_ELEVEN_ENABLED", "1") not in ("0", "false", "False", ""),
        # ── proxy.py tuning (env-overridable; defaults = historical literals) ──
        "RESPONSE_CACHE_TTL": int(os.environ.get("RESPONSE_CACHE_TTL", "50")),
        "GEOCODE_CACHE_TTL": int(os.environ.get("GEOCODE_CACHE_TTL", "3600")),
        "STOP_ID_CACHE_TTL": int(os.environ.get("STOP_ID_CACHE_TTL", str(86400 * 180))),
        # Batch cap: the Kotlin client sends ≤4 (Tuning.MAX_BATCH_STOPS); 6 is
        # intentional server-side headroom. Keep the two in sync — see
        # shared-android Tuning.kt.
        "BATCH_MAX_STOPS": int(os.environ.get("BATCH_MAX_STOPS", "6")),
        "UPSTREAM_STOPS_LIMIT": int(os.environ.get("UPSTREAM_STOPS_LIMIT", "100")),
        "STOPS_GRID_DEG": float(os.environ.get("STOPS_GRID_DEG", "0.002")),
        "STOPS_GRID_SLACK_M": int(os.environ.get("STOPS_GRID_SLACK_M", "300")),
        "STOPS_GRID_CACHE_TTL": int(os.environ.get("STOPS_GRID_CACHE_TTL", str(6 * 3600))),
    }

    missing = []
    if not config["TRANSITLAND_API_KEY"]:
        missing.append("TRANSITLAND_API_KEY")
    if not config["APP_API_KEYS"]:
        missing.append("APP_API_KEYS")

    if missing:
        print(f"ERROR: missing required env vars: {', '.join(missing)}", file=sys.stderr)
        sys.exit(1)

    return config
