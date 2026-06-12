import json

import bottle
import pytest

import validation
from validation import (
    ValidationError,
    validated,
    optional_int,
    require_str,
    require_float_pair,
    optional_float_pair,
    require_csv,
)


def set_query(**params):
    """Install a fake bottle request whose query carries the given params."""
    env = {"QUERY_STRING": "&".join(f"{k}={v}" for k, v in params.items())}
    bottle.request.bind(env)


def test_optional_int_default_and_parse():
    set_query()
    assert optional_int("next", 3600) == 3600
    set_query(next=10)
    assert optional_int("next", 3600) == 10


def test_optional_int_rejects_non_numeric():
    set_query(next="abc")
    with pytest.raises(ValidationError) as exc:
        optional_int("next", 3600)
    assert exc.value.detail == "next must be an integer"


def test_optional_int_rejects_negative():
    set_query(radius="-1")
    with pytest.raises(ValidationError) as exc:
        optional_int("radius", 500)
    assert exc.value.detail == "radius must be non-negative"


def test_require_str_required_and_too_long():
    set_query(q="")
    with pytest.raises(ValidationError) as exc:
        require_str("q", max_len=200)
    assert exc.value.detail == "q is required"
    set_query(q="x" * 201)
    with pytest.raises(ValidationError) as exc:
        require_str("q", max_len=200)
    assert exc.value.detail == "q is too long"


def test_require_float_pair():
    set_query(lat="1.0", lon="2.0")
    assert require_float_pair("lat", "lon") == (1.0, 2.0)
    set_query(lat="1.0")
    with pytest.raises(ValidationError) as exc:
        require_float_pair("lat", "lon")
    assert exc.value.detail == "lat and lon are required"
    set_query(lat="x", lon="y")
    with pytest.raises(ValidationError) as exc:
        require_float_pair("lat", "lon")
    assert exc.value.detail == "lat and lon must be numeric"


def test_optional_float_pair_both_or_neither():
    set_query()
    assert optional_float_pair("focus_lat", "focus_lon") == (None, None)
    set_query(focus_lat="1.0")
    with pytest.raises(ValidationError) as exc:
        optional_float_pair("focus_lat", "focus_lon")
    assert exc.value.detail == "focus_lat and focus_lon must be provided together"


def test_require_csv():
    set_query(onestop_ids="a,b, c ,")
    assert require_csv("onestop_ids", 6, "too many onestop_ids") == ["a", "b", "c"]
    set_query()
    with pytest.raises(ValidationError) as exc:
        require_csv("onestop_ids", 6, "too many onestop_ids")
    assert exc.value.detail == "onestop_ids is required"
    set_query(onestop_ids="a,b,c")
    with pytest.raises(ValidationError) as exc:
        require_csv("onestop_ids", 2, "too many onestop_ids")
    assert exc.value.detail == "too many onestop_ids"


def test_validated_renders_400():
    @validated
    def route():
        raise ValidationError("lat and lon are required")

    with pytest.raises(bottle.HTTPResponse) as exc:
        route()
    resp = exc.value
    assert resp.status_code == 400
    assert json.loads(resp.body) == {"error": "bad_request", "detail": "lat and lon are required"}
