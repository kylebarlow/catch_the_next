import hmac
import functools
import bottle
from config import load_config

_cfg = load_config()
_valid_keys = _cfg["APP_API_KEYS"]


def check_auth():
    client_key = bottle.request.headers.get("X-API-Key", "")
    for valid_key in _valid_keys:
        if hmac.compare_digest(client_key.encode(), valid_key.encode()):
            return valid_key[:6]
    return None


def require_auth(fn):
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        prefix = check_auth()
        if prefix is None:
            raise bottle.HTTPResponse(
                body='{"error":"unauthorized"}',
                status=401,
                headers={"Content-Type": "application/json"},
            )
        bottle.response.set_header("X-API-Key-Prefix", prefix)
        return fn(*args, **kwargs)
    return wrapper
