#!/usr/bin/env python3
import sys
import os

sys.path.insert(0, '/home/protected/pylib')
sys.path.insert(0, '/home/protected/server')

def _load_env(path):
    if os.path.isfile(path):
        with open(path) as _f:
            for _line in _f:
                _line = _line.strip()
                if _line and not _line.startswith('#') and '=' in _line:
                    _key, _, _val = _line.partition('=')
                    os.environ.setdefault(_key.strip(), _val.strip())

# Load root .env first (has keys like 511_API_KEY), then server/.env.
# setdefault means the first value wins, so server/.env can override.
_load_env('/home/protected/.env')
_load_env('/home/protected/server/.env')

from app import app
from bottle import run
run(app, server='cgi')
