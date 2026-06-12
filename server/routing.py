"""Departure-source routing: decide *which* backend serves a stop's departures.

Pure module — predicates (`resolve_stop_info`, `is_bay_area_feed`) are injected
by the caller, so there is no gtfs511 import cost here. proxy.py owns the
*execution* (the staleness check, the actual upstream fetch); this module only
classifies. Geo gating for `get_stops` (`in_bay_area`) stays in proxy.py — that
boundary is a lat/lon test, not a feed/onestop_id classification.
"""
import zlib
from dataclasses import dataclass

# Stops discovered via the local 511 static DB carry a server-synthesized
# onestop_id "511:<feed_onestop_id>:<stop_id>". The Kotlin client treats it as an
# opaque string, so it round-trips with zero client changes. They have no
# Transitland identity — they always serve from the local 511 path.
SYNTHETIC_PREFIX = "511:"


@dataclass(frozen=True)
class DeparturePlan:
    """How one onestop_id should be served.

    kind:
      "local_always" — synthetic id; always served from 511 (no TL identity)
      "local_or_tl"  — Bay Area TL stop; local when RT fresh, else Transitland
      "tl"           — non-Bay-Area; Transitland only
      "none"         — unresolvable; empty result
    """
    kind: str
    onestop_id: str
    feed_id: str | None = None
    stop_id: str | None = None

    @property
    def needs_511(self) -> bool:
        return self.kind in ("local_always", "local_or_tl")


def parse_synthetic_onestop_id(oid, is_bay_area_feed):
    """Return (feed_id, stop_id) for a synthetic id, else None.

    Requires 3 non-empty parts and a recognized Bay Area feed. ':' inside the
    stop_id is preserved (split with maxsplit=2).
    """
    if not isinstance(oid, str) or not oid.startswith(SYNTHETIC_PREFIX):
        return None
    parts = oid.split(":", 2)
    if len(parts) != 3:
        return None
    _, feed_id, stop_id = parts
    if not feed_id or not stop_id:
        return None
    if not is_bay_area_feed(feed_id):
        return None
    return feed_id, stop_id


def make_synthetic_onestop_id(feed_id, stop_id):
    """Build a synthetic id, or None if stop_id is empty or contains ',' (the
    client's batch separator)."""
    if not stop_id or "," in str(stop_id):
        return None
    return f"{SYNTHETIC_PREFIX}{feed_id}:{stop_id}"


def synthetic_integer_id(onestop_id: str) -> int:
    """Stable negative integer id for a synthetic onestop_id.

    crc32 (not hash(), which is salted per-process) keeps it stable across
    processes; negative so it can't collide with Transitland's positive ids.
    """
    return -((zlib.crc32(onestop_id.encode()) % 0x7FFFFFFF) + 1)


def classify_departure_source(onestop_id, *, resolve_stop_info, is_bay_area_feed):
    """Classify *onestop_id* into a DeparturePlan.

    `resolve_stop_info(oid) -> {feed_onestop_id, stop_id, ...} | None` resolves a
    Transitland stop; `is_bay_area_feed(feed_id) -> bool` recognizes a 511 feed.
    """
    syn = parse_synthetic_onestop_id(onestop_id, is_bay_area_feed)
    if syn is not None:
        return DeparturePlan("local_always", onestop_id, feed_id=syn[0], stop_id=syn[1])
    info = resolve_stop_info(onestop_id)
    if info is None:
        return DeparturePlan("none", onestop_id)
    if is_bay_area_feed(info.get("feed_onestop_id")):
        return DeparturePlan("local_or_tl", onestop_id,
                             feed_id=info["feed_onestop_id"], stop_id=info["stop_id"])
    return DeparturePlan("tl", onestop_id)
