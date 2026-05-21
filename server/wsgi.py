import sys
import os

_here = os.path.dirname(os.path.abspath(__file__))

# Make app modules importable under mod_wsgi
sys.path.insert(0, _here)

# Packages installed with pip --target (relative to server dir: ../pylib)
_pylib = os.path.normpath(os.path.join(_here, '..', 'pylib'))
if os.path.isdir(_pylib):
    sys.path.insert(0, _pylib)

# Load .env files into os.environ. We replay them at WSGI startup because
# docker-compose's env_file silently drops keys that don't match shell-name
# conventions (e.g. `511_API_KEY` starts with a digit), and Apache strips
# most shell vars from WSGI workers anyway. Python's os.environ has no such
# restriction.
#
# Order: try the project root (parent of /app) first, then server/.env —
# setdefault() means later loads don't clobber earlier ones, so the more
# specific file wins.
for _candidate in (
    os.path.normpath(os.path.join(_here, '..', '.env')),  # repo root
    os.path.join(_here, '.env'),                          # server/
):
    if os.path.isfile(_candidate):
        with open(_candidate) as _f:
            for _line in _f:
                _line = _line.strip()
                if _line and not _line.startswith('#') and '=' in _line:
                    _key, _, _val = _line.partition('=')
                    os.environ.setdefault(_key.strip(), _val.strip())

from app import app

application = app
