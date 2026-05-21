"""HTTP fetchers for 511.org transit endpoints.

511 doesn't accept ``Accept-Encoding: gzip`` consistently and a few of the
data endpoints return BOM-prefixed bytes — we leave the body untouched and
hand raw bytes to the protobuf / zipfile parsers downstream.
"""

import io
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass

from config import load_config

_cfg = load_config()
_API_KEY = _cfg["FIVE_ELEVEN_API_KEY"]
_BASE = _cfg["FIVE_ELEVEN_BASE_URL"].rstrip("/")
_AGENCY = _cfg["FIVE_ELEVEN_AGENCY"]
_CONNECT_TIMEOUT = _cfg["UPSTREAM_CONNECT_TIMEOUT"]
# Static GTFS bundle for RG is multi-MB; the default read timeout is too tight.
_RT_READ_TIMEOUT = max(_cfg["UPSTREAM_READ_TIMEOUT"], 30.0)
_STATIC_READ_TIMEOUT = max(_cfg["UPSTREAM_READ_TIMEOUT"], 120.0)

_USER_AGENT = "CatchTheNext-511/1.0 (+https://codeberg.org/ursidaureus/catch_the_next)"


@dataclass
class DownloadResult:
    url: str
    bytes_: bytes
    elapsed_s: float
    status: int

    @property
    def size(self) -> int:
        return len(self.bytes_)


class FiveElevenError(RuntimeError):
    """Raised on any 511 upstream problem (timeout, non-2xx, etc.)."""


def _require_key() -> str:
    if not _API_KEY:
        raise FiveElevenError("FIVE_ELEVEN_API_KEY is not configured")
    return _API_KEY


def _get(url: str, params: dict, read_timeout: float) -> DownloadResult:
    full_params = {"api_key": _require_key(), **params}
    full_url = f"{url}?{urllib.parse.urlencode(full_params)}"
    req = urllib.request.Request(full_url, headers={"User-Agent": _USER_AGENT})

    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=_CONNECT_TIMEOUT + read_timeout) as resp:
            body = resp.read()
            status = resp.status
    except urllib.error.HTTPError as e:
        raise FiveElevenError(f"511 HTTP {e.code} for {url}") from e
    except (socket.timeout, TimeoutError) as e:
        raise FiveElevenError(f"511 timeout for {url}") from e
    except (urllib.error.URLError, OSError) as e:
        raise FiveElevenError(f"511 transport error for {url}: {e}") from e
    elapsed = time.monotonic() - start

    # 511 sometimes returns a UTF-8 BOM on text endpoints (not the protobuf
    # ones, but be defensive for the static feed listing).
    if body.startswith(b"\xef\xbb\xbf"):
        body = body[3:]

    # Sanitize URL in the metric — strip api_key.
    safe_url = full_url.replace(_API_KEY, "***") if _API_KEY else full_url
    return DownloadResult(url=safe_url, bytes_=body, elapsed_s=elapsed, status=status)


def download_tripupdates() -> DownloadResult:
    """Fetch the GTFS-Realtime TripUpdates protobuf for the configured agency."""
    return _get(f"{_BASE}/tripupdates", {"agency": _AGENCY}, _RT_READ_TIMEOUT)


def download_servicealerts() -> DownloadResult:
    """Fetch the GTFS-Realtime ServiceAlerts protobuf for the configured agency."""
    return _get(f"{_BASE}/servicealerts", {"agency": _AGENCY}, _RT_READ_TIMEOUT)


def download_static_zip() -> DownloadResult:
    """Fetch the static GTFS zip bundle for the configured agency.

    511's endpoint is ``/datafeeds?operator_id=<agency>``.
    """
    return _get(f"{_BASE}/datafeeds", {"operator_id": _AGENCY}, _STATIC_READ_TIMEOUT)


def open_static_zip(result: DownloadResult) -> zipfile.ZipFile:
    """Open the downloaded bytes as a ZipFile. Raises FiveElevenError on bad zip."""
    try:
        return zipfile.ZipFile(io.BytesIO(result.bytes_))
    except zipfile.BadZipFile as e:
        # First few bytes often help diagnose (e.g. 511 returning an HTML error page).
        head = result.bytes_[:200]
        raise FiveElevenError(f"static feed is not a valid zip; head={head!r}") from e
