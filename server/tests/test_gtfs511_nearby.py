"""Tests for gtfs511 local nearby-stops search + Bay Area bbox."""
import os
os.environ.setdefault("TRANSITLAND_API_KEY", "real-upstream-key")
os.environ.setdefault("APP_API_KEYS", "test-key")

import gtfs511
from gtfs511 import feed_mapping, lookup, static_db
from tests.gtfs511_fixtures import make_static_gtfs_zip


# Extra stops near (37.70, -122.40): NEAR1 ~closest, NEAR2 farther, FAR way out,
# ENTR a station entrance (location_type 2, excluded), BAD with junk coords.
_EXTRA = (
    "NEAR1,N1,Near One,37.7005,-122.4005,,0\n"
    "NEAR2,N2,Near Two,37.702,-122.402,,0\n"
    "FAR,FR,Far Stop,37.80,-122.50,,0\n"
    "ENTR,EN,Entrance,37.7001,-122.4001,,2\n"
    "BADC,BD,Bad Coords,notalat,notalon,,0\n"
)


def _build(gtfs511_dir):
    zip_bytes = make_static_gtfs_zip(service_dates=["20990101"], extra_stops_rows=_EXTRA)
    static_db.build_static_db(zip_bytes, str(gtfs511_dir / "gtfs_511_static.sqlite"))
    return str(gtfs511_dir / "gtfs_511_static.sqlite")


def test_nearby_sorted_by_distance(gtfs511_dir):
    path = _build(gtfs511_dir)
    sdb = static_db.open_static_db(path)
    try:
        rows = lookup.nearby_stops(sdb, 37.70, -122.40, radius_m=2000, limit=20)
    finally:
        sdb.close()
    ids = [r["stop_id"] for r in rows]
    # CHILD1/PARENT/STAND all at ~37.7; NEAR1 closest of the extras.
    assert "NEAR1" in ids
    dists = [r["_dist_m"] for r in rows]
    assert dists == sorted(dists)


def test_nearby_radius_respected(gtfs511_dir):
    path = _build(gtfs511_dir)
    sdb = static_db.open_static_db(path)
    try:
        rows = lookup.nearby_stops(sdb, 37.70, -122.40, radius_m=300, limit=20)
    finally:
        sdb.close()
    ids = [r["stop_id"] for r in rows]
    assert "FAR" not in ids
    assert all(r["_dist_m"] <= 300 for r in rows)


def test_nearby_excludes_entrances_and_bad_coords(gtfs511_dir):
    path = _build(gtfs511_dir)
    sdb = static_db.open_static_db(path)
    try:
        rows = lookup.nearby_stops(sdb, 37.70, -122.40, radius_m=5000, limit=50)
    finally:
        sdb.close()
    ids = [r["stop_id"] for r in rows]
    assert "ENTR" not in ids  # location_type 2 excluded
    assert "BADC" not in ids  # unparseable coords skipped


def test_nearby_limit_respected(gtfs511_dir):
    path = _build(gtfs511_dir)
    sdb = static_db.open_static_db(path)
    try:
        rows = lookup.nearby_stops(sdb, 37.70, -122.40, radius_m=5000, limit=2)
    finally:
        sdb.close()
    assert len(rows) == 2


def test_api_nearby_none_when_static_missing(gtfs511_dir):
    # No DB built → None.
    assert gtfs511.nearby_stops(37.70, -122.40, 500, 20) is None


def test_api_nearby_returns_rows_after_build(gtfs511_dir):
    _build(gtfs511_dir)
    rows = gtfs511.nearby_stops(37.70, -122.40, 2000, 20)
    assert rows is not None and len(rows) > 0


def test_in_bay_area_spot_checks():
    inside = [(37.77, -122.42), (37.80, -122.27), (37.00, -121.57),
              (38.36, -121.97), (38.80, -123.01), (37.25, -122.41)]
    outside = [(40.75, -73.97), (38.58, -121.49), (36.97, -122.03),
               (38.55, -121.74), (37.74, -121.43), (38.75, -122.62)]
    for lat, lon in inside:
        assert feed_mapping.in_bay_area(lat, lon), (lat, lon)
    for lat, lon in outside:
        assert not feed_mapping.in_bay_area(lat, lon), (lat, lon)
