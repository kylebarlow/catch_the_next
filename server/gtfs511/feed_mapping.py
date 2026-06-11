"""Mapping from Transitland ``feed_onestop_id`` to attribution metadata for
departures we synthesize from 511 data.

Transitland publishes the **combined** SF Bay Area regional feed under
``f-sf~bay~area~rg``, which is the same RG bundle 511 publishes. Stops in the
Bay Area therefore carry that single feed_onestop_id rather than per-operator
IDs. We special-case it so every Bay Area stop routes to the 511 cache.

The legacy per-operator entries (``f-9q9-bart`` etc.) remain in case Transitland
exposes them on certain feeds or stop variants — they are harmless to keep.
"""

# feed_onestop_id (Transitland) -> dict with:
#   agency_id           : 511 GTFS agency_id used as a soft hint for filtering
#   feed_name           : human-readable feed label
#   attribution_text    : displayed under the departure in clients
#   attribution_instructions
#   license_spdx
#   license_url
#   use_without_attribution: bool

_DEFAULT_LICENSE_URL = "https://511.org/open-data/terms-of-use"
_DEFAULT_LICENSE_INSTRUCTIONS = "Data via 511 SF Bay Open Data Program."

# The combined SF Bay Area regional feed (all 511 RG agencies).
REGIONAL_FEED_ID = "f-sf~bay~area~rg"

FEEDS: dict[str, dict] = {
    # Combined SF Bay Area regional feed — what Transitland actually returns
    # today for Bay Area stops. agency_id is None because the combined feed
    # spans every operator; lookup uses raw stop_id only.
    "f-sf~bay~area~rg": {
        "agency_id": None,
        "feed_name": "SF Bay Area (511)",
        "attribution_text": "511 SF Bay",
        "attribution_instructions": _DEFAULT_LICENSE_INSTRUCTIONS,
        "license_spdx": None,
        "license_url": _DEFAULT_LICENSE_URL,
        "use_without_attribution": False,
    },
    "f-9q9-bart": {
        "agency_id": "BA",
        "feed_name": "Bay Area Rapid Transit",
        "attribution_text": "BART, via 511.org",
        "attribution_instructions": _DEFAULT_LICENSE_INSTRUCTIONS,
        "license_spdx": None,
        "license_url": _DEFAULT_LICENSE_URL,
        "use_without_attribution": False,
    },
    "f-9q8y-sfmta": {
        "agency_id": "SF",
        "feed_name": "San Francisco Muni",
        "attribution_text": "SFMTA, via 511.org",
        "attribution_instructions": _DEFAULT_LICENSE_INSTRUCTIONS,
        "license_spdx": None,
        "license_url": _DEFAULT_LICENSE_URL,
        "use_without_attribution": False,
    },
    "f-9q9-actransit": {
        "agency_id": "AC",
        "feed_name": "AC Transit",
        "attribution_text": "AC Transit, via 511.org",
        "attribution_instructions": _DEFAULT_LICENSE_INSTRUCTIONS,
        "license_spdx": None,
        "license_url": _DEFAULT_LICENSE_URL,
        "use_without_attribution": False,
    },
    "f-9q9-samtrans": {
        "agency_id": "SM",
        "feed_name": "SamTrans",
        "attribution_text": "SamTrans, via 511.org",
        "attribution_instructions": _DEFAULT_LICENSE_INSTRUCTIONS,
        "license_spdx": None,
        "license_url": _DEFAULT_LICENSE_URL,
        "use_without_attribution": False,
    },
    "f-9q9-caltrain": {
        "agency_id": "CT",
        "feed_name": "Caltrain",
        "attribution_text": "Caltrain, via 511.org",
        "attribution_instructions": _DEFAULT_LICENSE_INSTRUCTIONS,
        "license_spdx": None,
        "license_url": _DEFAULT_LICENSE_URL,
        "use_without_attribution": False,
    },
    "f-9q9-vta": {
        "agency_id": "SC",
        "feed_name": "VTA",
        "attribution_text": "VTA, via 511.org",
        "attribution_instructions": _DEFAULT_LICENSE_INSTRUCTIONS,
        "license_spdx": None,
        "license_url": _DEFAULT_LICENSE_URL,
        "use_without_attribution": False,
    },
    "f-9q8-goldengatetransit": {
        "agency_id": "GG",
        "feed_name": "Golden Gate Transit",
        "attribution_text": "Golden Gate Transit, via 511.org",
        "attribution_instructions": _DEFAULT_LICENSE_INSTRUCTIONS,
        "license_spdx": None,
        "license_url": _DEFAULT_LICENSE_URL,
        "use_without_attribution": False,
    },
    "f-9q9-marin": {
        "agency_id": "MA",
        "feed_name": "Marin Transit",
        "attribution_text": "Marin Transit, via 511.org",
        "attribution_instructions": _DEFAULT_LICENSE_INSTRUCTIONS,
        "license_spdx": None,
        "license_url": _DEFAULT_LICENSE_URL,
        "use_without_attribution": False,
    },
    "f-9q9-soltrans": {
        "agency_id": "ST",
        "feed_name": "SolTrans",
        "attribution_text": "SolTrans, via 511.org",
        "attribution_instructions": _DEFAULT_LICENSE_INSTRUCTIONS,
        "license_spdx": None,
        "license_url": _DEFAULT_LICENSE_URL,
        "use_without_attribution": False,
    },
    "f-9q9-westcat": {
        "agency_id": "WC",
        "feed_name": "WestCAT",
        "attribution_text": "WestCAT, via 511.org",
        "attribution_instructions": _DEFAULT_LICENSE_INSTRUCTIONS,
        "license_spdx": None,
        "license_url": _DEFAULT_LICENSE_URL,
        "use_without_attribution": False,
    },
    "f-9q9-tridelta": {
        "agency_id": "3D",
        "feed_name": "Tri Delta Transit",
        "attribution_text": "Tri Delta Transit, via 511.org",
        "attribution_instructions": _DEFAULT_LICENSE_INSTRUCTIONS,
        "license_spdx": None,
        "license_url": _DEFAULT_LICENSE_URL,
        "use_without_attribution": False,
    },
}


def is_bay_area_feed(feed_onestop_id: str | None) -> bool:
    return bool(feed_onestop_id) and feed_onestop_id in FEEDS


def in_bay_area(lat: float, lon: float) -> bool:
    """True if (lat, lon) is inside the 511 RG feed's coverage rectangle.

    Over-exclusion is safe (falls back to Transitland); over-inclusion would
    hide local agencies, so we carve out three wedges for neighboring systems
    NOT in the RG bundle (Santa Cruz Metro, Yolo/Davis, Lake County).
    """
    if not (36.95 <= lat <= 38.87 and -123.55 <= lon <= -121.55):
        return False
    if lat < 37.18 and lon < -121.84:   # Santa Cruz strip
        return False
    if lat > 38.45 and lon > -122.10:   # Yolo / Davis
        return False
    if lat > 38.65 and lon > -122.75:   # Lake county
        return False
    return True


def metadata_for(feed_onestop_id: str) -> dict:
    """Return a dict suitable for `lookup.lookup_departures(feed_metadata=)`.

    Always includes ``feed_onestop_id`` even if the feed isn't known, so the
    record shape stays consistent.
    """
    base = FEEDS.get(feed_onestop_id, {})
    return {
        "feed_onestop_id": feed_onestop_id,
        "feed_name": base.get("feed_name"),
        "attribution_text": base.get("attribution_text"),
        "attribution_instructions": base.get("attribution_instructions"),
        "use_without_attribution": base.get("use_without_attribution", False),
        "license_spdx": base.get("license_spdx"),
        "license_url": base.get("license_url"),
        "agency_id": base.get("agency_id"),
    }


def all_feed_ids() -> list[str]:
    return sorted(FEEDS.keys())
