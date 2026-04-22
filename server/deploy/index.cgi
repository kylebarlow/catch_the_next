#!/usr/bin/env python3
import sys
import os

sys.path.insert(0, '/home/protected/pylib')
sys.path.insert(0, '/home/protected/server')

_env_path = '/home/protected/server/.env'
if os.path.isfile(_env_path):
    with open(_env_path) as _f:
        for _line in _f:
            _line = _line.strip()
            if _line and not _line.startswith('#') and '=' in _line:
                _key, _, _val = _line.partition('=')
                os.environ.setdefault(_key.strip(), _val.strip())

from app import app
from bottle import run
run(app, server='cgi')
