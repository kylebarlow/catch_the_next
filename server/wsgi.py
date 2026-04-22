import sys
import os

_here = os.path.dirname(os.path.abspath(__file__))

# Make app modules importable under mod_wsgi
sys.path.insert(0, _here)

# Packages installed with pip --target (relative to server dir: ../pylib)
_pylib = os.path.normpath(os.path.join(_here, '..', 'pylib'))
if os.path.isdir(_pylib):
    sys.path.insert(0, _pylib)

# Load .env from the server directory if present
_env_path = os.path.join(_here, '.env')
if os.path.isfile(_env_path):
    with open(_env_path) as _f:
        for _line in _f:
            _line = _line.strip()
            if _line and not _line.startswith('#') and '=' in _line:
                _key, _, _val = _line.partition('=')
                os.environ.setdefault(_key.strip(), _val.strip())

from app import app

application = app
