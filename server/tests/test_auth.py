import os
os.environ.setdefault("TRANSITLAND_API_KEY", "dummy")
os.environ.setdefault("APP_API_KEYS", "key-one,key-two")

from unittest.mock import patch, MagicMock
import bottle
import pytest
from auth import check_auth, require_auth, current_scope, SCOPE_FULL, SCOPE_PUBLIC


def _mock_request(key=None):
    mock = MagicMock()
    mock.headers.get.return_value = key or ""
    mock.environ = {}
    return mock


@patch("auth._full_keys", ["key-one", "key-two"])
@patch("auth._public_keys", [])
def test_valid_first_key():
    with patch("auth.bottle.request", _mock_request("key-one")):
        assert check_auth() == SCOPE_FULL


@patch("auth._full_keys", ["key-one", "key-two"])
@patch("auth._public_keys", [])
def test_valid_second_key():
    with patch("auth.bottle.request", _mock_request("key-two")):
        assert check_auth() == SCOPE_FULL


@patch("auth._full_keys", ["key-one"])
@patch("auth._public_keys", ["pub-key"])
def test_public_key_returns_public_scope():
    with patch("auth.bottle.request", _mock_request("pub-key")):
        assert check_auth() == SCOPE_PUBLIC


@patch("auth._full_keys", ["dual-key"])
@patch("auth._public_keys", ["dual-key"])
def test_full_scope_takes_precedence():
    with patch("auth.bottle.request", _mock_request("dual-key")):
        assert check_auth() == SCOPE_FULL


def test_missing_key_returns_none():
    with patch("auth.bottle.request", _mock_request()):
        assert check_auth() is None


def test_wrong_key_returns_none():
    with patch("auth.bottle.request", _mock_request("bad-key")):
        assert check_auth() is None


def test_require_auth_blocks_missing_key():
    @require_auth
    def handler():
        return "ok"

    with patch("auth.bottle.request", _mock_request()):
        with pytest.raises(bottle.HTTPResponse) as exc_info:
            handler()
    assert exc_info.value.status_code == 401


@patch("auth._full_keys", ["key-one", "key-two"])
@patch("auth._public_keys", [])
def test_require_auth_allows_valid_key():
    @require_auth
    def handler():
        return "ok"

    with patch("auth.bottle.request", _mock_request("key-one")):
        with patch("auth.bottle.response"):
            result = handler()
    assert result == "ok"


@patch("auth._full_keys", ["key-one"])
@patch("auth._public_keys", ["pub-key"])
def test_require_auth_stashes_scope():
    @require_auth
    def handler():
        return current_scope()

    req = _mock_request("pub-key")
    with patch("auth.bottle.request", req):
        with patch("auth.bottle.response"):
            assert handler() == SCOPE_PUBLIC
