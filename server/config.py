import os
import sys

def load_config():
    config = {
        "TRANSITLAND_API_KEY": os.environ.get("TRANSITLAND_API_KEY", ""),
        "TRANSITLAND_BASE_URL": os.environ.get("TRANSITLAND_BASE_URL", "https://transit.land/api/v2/rest"),
        "APP_API_KEYS": [k.strip() for k in os.environ.get("APP_API_KEYS", "").split(",") if k.strip()],
        "RATE_LIMIT_PER_HOUR": int(os.environ.get("RATE_LIMIT_PER_HOUR", "500")),
        "ACCESS_LOG_PATH": os.environ.get("ACCESS_LOG_PATH", "/home/logs/access_log"),
        "RATE_LIMIT_TAIL_BYTES": int(os.environ.get("RATE_LIMIT_TAIL_BYTES", "262144")),
        "UPSTREAM_CONNECT_TIMEOUT": float(os.environ.get("UPSTREAM_CONNECT_TIMEOUT", "5")),
        "UPSTREAM_READ_TIMEOUT": float(os.environ.get("UPSTREAM_READ_TIMEOUT", "10")),
        "STATS_PATH_SECRET": os.environ.get("STATS_PATH_SECRET", ""),
        "STATS_MAX_LOG_BYTES": int(os.environ.get("STATS_MAX_LOG_BYTES", "50000000")),
        "STATS_CACHE_SECONDS": int(os.environ.get("STATS_CACHE_SECONDS", "30")),
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
