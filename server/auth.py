import hmac
import functools
import bottle
from config import load_config

_cfg = load_config()
_full_keys = _cfg["APP_API_KEYS"]
_public_keys = _cfg["APP_API_KEYS_PUBLIC"]

# Authorization scopes. "full" keys reach everything (Transitland for nationwide stops,
# 511 offload when possible). "public" keys are hard-restricted to local Bay Area / 511 data
# and may never trigger a Transitland call (enforced in proxy.py).
SCOPE_FULL = "full"
SCOPE_PUBLIC = "public"

# Where require_auth stashes the resolved scope for the duration of the request.
_SCOPE_ENV_KEY = "catchthenext.scope"


def check_auth():
    """Return the scope ("full"/"public") of the matching key, or None if unknown.

    Full keys take precedence if a key somehow appears in both sets.
    """
    client_key = bottle.request.headers.get("X-API-Key", "")
    client_bytes = client_key.encode()
    for valid_key in _full_keys:
        if hmac.compare_digest(client_bytes, valid_key.encode()):
            return SCOPE_FULL
    for valid_key in _public_keys:
        if hmac.compare_digest(client_bytes, valid_key.encode()):
            return SCOPE_PUBLIC
    return None


def current_scope():
    """The scope of the authenticated request. Defaults to full if unset (e.g. an
    endpoint that did not go through require_auth)."""
    return bottle.request.environ.get(_SCOPE_ENV_KEY, SCOPE_FULL)


def require_auth(fn):
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        # Do not echo any part of the matched key back to the client — it
        # needlessly discloses key material and serves no client purpose.
        scope = check_auth()
        if scope is None:
            raise bottle.HTTPResponse(
                body='{"error":"unauthorized"}',
                status=401,
                headers={"Content-Type": "application/json"},
            )
        bottle.request.environ[_SCOPE_ENV_KEY] = scope
        return fn(*args, **kwargs)
    return wrapper
