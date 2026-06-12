"""Query-parameter validation for the proxy routes.

Centralizes the small set of 400-producing checks the routes share. The detail
strings are kept byte-identical to the historical inline messages so clients and
tests see no behavior change.
"""
import json
import functools

import bottle


class ValidationError(Exception):
    """Raised by the helpers below; rendered as a 400 by `@validated`."""

    def __init__(self, detail):
        super().__init__(detail)
        self.detail = detail


def validated(func):
    """Render any ValidationError raised in the wrapped route as the standard 400 JSON."""
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        try:
            return func(*args, **kwargs)
        except ValidationError as exc:
            raise bottle.HTTPResponse(
                body=json.dumps({"error": "bad_request", "detail": exc.detail}),
                status=400,
                headers={"Content-Type": "application/json"},
            )
    return wrapper


def optional_int(name, default, min_value=0):
    """Read a non-negative integer query param, or raise ValidationError.

    Mirrors the historical `_int_param`: missing/empty returns the default,
    non-numeric raises "<name> must be an integer", below `min_value` raises
    "<name> must be non-negative". Upstream caps still apply downstream.
    """
    raw = bottle.request.query.get(name)
    if raw is None or raw == "":
        return default
    try:
        value = int(raw)
    except (ValueError, TypeError):
        raise ValidationError(f"{name} must be an integer")
    if value < min_value:
        raise ValidationError(f"{name} must be non-negative")
    return value


def require_str(name, max_len=None):
    """Require a non-empty (after strip) string query param."""
    value = bottle.request.query.get(name, "").strip()
    if not value:
        raise ValidationError(f"{name} is required")
    if max_len is not None and len(value) > max_len:
        raise ValidationError(f"{name} is too long")
    return value


def require_float_pair(lat_name, lon_name):
    """Require both params present and numeric. Detail strings match the legacy lat/lon checks."""
    lat = bottle.request.query.get(lat_name)
    lon = bottle.request.query.get(lon_name)
    if lat is None or lon is None:
        raise ValidationError(f"{lat_name} and {lon_name} are required")
    try:
        return float(lat), float(lon)
    except ValueError:
        raise ValidationError(f"{lat_name} and {lon_name} must be numeric")


def optional_float_pair(lat_name, lon_name):
    """Both-or-neither pair of numeric params; returns (None, None) when absent."""
    lat = bottle.request.query.get(lat_name)
    lon = bottle.request.query.get(lon_name)
    if (lat is None) != (lon is None):
        raise ValidationError(f"{lat_name} and {lon_name} must be provided together")
    if lat is None:
        return None, None
    try:
        return float(lat), float(lon)
    except ValueError:
        raise ValidationError(f"{lat_name} and {lon_name} must be numeric")


def require_csv(name, max_items, too_many_detail):
    """Split a comma-separated param into a non-empty list of trimmed values."""
    raw = bottle.request.query.get(name)
    items = [s.strip() for s in raw.split(",") if s.strip()] if raw else []
    if not items:
        raise ValidationError(f"{name} is required")
    if len(items) > max_items:
        raise ValidationError(too_many_detail)
    return items
