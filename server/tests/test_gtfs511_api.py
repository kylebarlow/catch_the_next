"""Tests for the non-fatal refresh contract of gtfs511.api.refresh_if_stale."""
import os
os.environ.setdefault("TRANSITLAND_API_KEY", "real-upstream-key")
os.environ.setdefault("APP_API_KEYS", "test-key")

import time

import gtfs511
from gtfs511 import api, download, static_db, rt_db
from gtfs511.download import DownloadResult, FiveElevenError
from tests.gtfs511_fixtures import make_static_gtfs_zip, make_tripupdates_pb


def _dl(b: bytes) -> DownloadResult:
    return DownloadResult(url="x", bytes_=b, elapsed_s=0.0, status=200)


def _build_dbs(gtfs511_dir, *, rt_predicted=None):
    """Build both static + rt DBs directly so we have a baseline snapshot."""
    static_db.build_static_db(
        make_static_gtfs_zip(service_dates=["20990101"]),
        str(gtfs511_dir / "gtfs_511_static.sqlite"),
    )
    if rt_predicted is not None:
        rt_db.build_rt_db(
            make_tripupdates_pb(predicted_epoch=rt_predicted), None,
            str(gtfs511_dir / "gtfs_511_rt.sqlite"),
        )


def _backdate(path, seconds, table):
    """Make a DB file appear `seconds` old via its refresh timestamp row."""
    import sqlite3
    db = sqlite3.connect(path)
    old = int(time.time()) - seconds
    db.execute(f"UPDATE {table} SET value = ? WHERE key = 'refreshed_at'", (str(old),))
    db.commit()
    db.close()


def test_rt_download_failure_is_nonfatal(gtfs511_dir, monkeypatch):
    _build_dbs(gtfs511_dir, rt_predicted=int(time.time()) + 600)
    _backdate(str(gtfs511_dir / "gtfs_511_rt.sqlite"), 300, "rt_meta")

    monkeypatch.setattr(download, "download_tripupdates",
                        lambda: (_ for _ in ()).throw(FiveElevenError("boom")))
    # static is fresh enough; force only rt refresh by leaving static recent.
    outcome = api.refresh_if_stale()
    assert outcome.refreshed_rt is False
    assert outcome.rt_error is not None
    assert outcome.rt_age_seconds is not None and outcome.rt_age_seconds >= 250


def test_static_download_failure_serves_old_db(gtfs511_dir, monkeypatch):
    _build_dbs(gtfs511_dir)
    _backdate(str(gtfs511_dir / "gtfs_511_static.sqlite"), 86400 * 10, "feed_meta")  # stale
    monkeypatch.setattr(download, "download_static_zip",
                        lambda: (_ for _ in ()).throw(FiveElevenError("nope")))
    monkeypatch.setattr(download, "download_tripupdates",
                        lambda: _dl(make_tripupdates_pb(predicted_epoch=int(time.time()) + 600)))
    outcome = api.refresh_if_stale()
    assert outcome.static_error is not None
    assert outcome.static_age_seconds is not None  # old DB still present
    assert os.path.isfile(str(gtfs511_dir / "gtfs_511_static.sqlite"))


def test_rt_build_failure_keeps_old_snapshot(gtfs511_dir, monkeypatch):
    _build_dbs(gtfs511_dir, rt_predicted=int(time.time()) + 600)
    _backdate(str(gtfs511_dir / "gtfs_511_rt.sqlite"), 300, "rt_meta")
    monkeypatch.setattr(download, "download_tripupdates", lambda: _dl(b"garbage-not-a-protobuf"))
    outcome = api.refresh_if_stale()
    assert outcome.refreshed_rt is False
    assert outcome.rt_error is not None
    assert os.path.isfile(str(gtfs511_dir / "gtfs_511_rt.sqlite"))


def test_data_ages_and_static_available(gtfs511_dir):
    assert api.data_ages() == (None, None)
    assert api.static_db_available() is False
    _build_dbs(gtfs511_dir, rt_predicted=int(time.time()) + 600)
    static_age, rt_age = api.data_ages()
    assert static_age is not None
    assert rt_age is not None
    assert api.static_db_available() is True
