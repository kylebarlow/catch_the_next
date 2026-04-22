import os
os.environ.setdefault("TRANSITLAND_API_KEY", "dummy")
os.environ.setdefault("APP_API_KEYS", "key-one,key-two")

from unittest.mock import patch, MagicMock
import bottle
import pytest
from auth import check_auth, require_auth


def _mock_request(key=None):
    mock = MagicMock()
    mock.headers.get.return_value = key or ""
    return mock


def test_valid_first_key():
    with patch("auth.bottle.request", _mock_request("key-one")):
        prefix = check_auth()
    assert prefix == "key-on"


def test_valid_second_key():
    with patch("auth.bottle.request", _mock_request("key-two")):
        prefix = check_auth()
    assert prefix == "key-tw"


def test_missing_key_returns_none():
    with patch("auth.bottle.request", _mock_request()):
        prefix = check_auth()
    assert prefix is None


def test_wrong_key_returns_none():
    with patch("auth.bottle.request", _mock_request("bad-key")):
        prefix = check_auth()
    assert prefix is None


def test_require_auth_blocks_missing_key():
    @require_auth
    def handler():
        return "ok"

    with patch("auth.bottle.request", _mock_request()):
        with pytest.raises(bottle.HTTPResponse) as exc_info:
            handler()
    assert exc_info.value.status_code == 401


def test_require_auth_allows_valid_key():
    @require_auth
    def handler():
        return "ok"

    with patch("auth.bottle.request", _mock_request("key-one")):
        with patch("auth.bottle.response"):
            result = handler()
    assert result == "ok"
