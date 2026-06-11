"""End-to-end: search → favorite → departures runs fully offline from
Transitland for Bay Area stops served by the local 511 static DB."""
import os
os.environ.setdefault("TRANSITLAND_API_KEY", "real-upstream-key")
os.environ.setdefault("APP_API_KEYS", "test-key")

from datetime import datetime, timedelta
from unittest.mock import patch

import proxy
import gtfs511
from gtfs511 import api, download, static_db, feed_mapping
from gtfs511.download import FiveElevenError
from tests.gtfs511_fixtures import make_static_gtfs_zip


def _service_dates():
    today = datetime.now().date()
    return [(today + timedelta(days=d)).strftime("%Y%m%d") for d in range(-1, 3)]


def test_search_to_departures_offline_from_transitland(gtfs511_dir, monkeypatch):
    # Build a real static DB with stops inside the Bay Area bbox (37.7, -122.4).
    zip_bytes = make_static_gtfs_zip(service_dates=_service_dates())
    static_db.build_static_db(zip_bytes, str(gtfs511_dir / "gtfs_511_static.sqlite"))

    # The combined regional feed id must be recognized as Bay Area.
    assert gtfs511.is_bay_area_feed(feed_mapping.REGIONAL_FEED_ID)

    # 1) Search at Bay Area coords → synthetic stops, zero Transitland calls.
    with patch("proxy._http_get_json", side_effect=AssertionError("no TL during search")):
        stops = proxy.get_stops(37.7, -122.4, radius=2000, limit=50)

    synthetic = [s for s in stops["stops"] if s["onestop_id"].startswith("511:")]
    assert synthetic, "expected synthetic 511 stops from local search"
    # Pick the CHILD1 platform (it has scheduled departures in the fixture).
    child = next(s for s in synthetic if s["onestop_id"].endswith(":CHILD1"))

    # 2) Feed that onestop_id into departures with 511 RT download failing.
    monkeypatch.setattr(download, "download_tripupdates",
                        lambda: (_ for _ in ()).throw(FiveElevenError("rt down")))
    with patch("proxy._http_get_json", side_effect=AssertionError("no TL during departures")):
        result = proxy.get_departures_by_onestop_ids([child["onestop_id"]], next_seconds=86400)

    deps = result["stops"][0]["departures"]
    assert deps, "expected schedule-only departures"
    assert all(d["time_source"] == "SCHEDULED" for d in deps)
