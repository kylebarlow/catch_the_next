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
            return True
    return False


def require_auth(fn):
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        # Do not echo any part of the matched key back to the client — it
        # needlessly discloses key material and serves no client purpose.
        if not check_auth():
            raise bottle.HTTPResponse(
                body='{"error":"unauthorized"}',
                status=401,
                headers={"Content-Type": "application/json"},
            )
        return fn(*args, **kwargs)
    return wrapper
