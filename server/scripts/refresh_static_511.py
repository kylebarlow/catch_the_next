#!/usr/bin/env python3
"""Refresh the 511 static GTFS SQLite. Designed for NFSN scheduled tasks."""
import os
import sys
import time

sys.path.insert(0, '/home/protected/pylib')
sys.path.insert(0, '/home/protected/server')


def _load_env(path):
    if os.path.isfile(path):
        with open(path) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith('#') and '=' in line:
                    k, _, v = line.partition('=')
                    os.environ.setdefault(k.strip(), v.strip())


# Load root .env first (has 511_API_KEY), then server/.env.
# setdefault means the first value wins, so server/.env can override.
_load_env('/home/protected/.env')
_load_env('/home/protected/server/.env')

from gtfs511 import api  # noqa: E402  (env must load first)

t0 = time.monotonic()
m = api.refresh_static()
elapsed = time.monotonic() - t0
print(
    f"refresh_static ok: {m.final_static_db_bytes} bytes, "
    f"{sum(m.static_rows.values())} rows, {elapsed:.1f}s"
)
