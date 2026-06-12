"""Pure unit tests for routing.classify_departure_source and the synthetic-id
helpers — predicates injected as plain lambdas, no proxy/gtfs511 import needed."""
from routing import (
    DeparturePlan,
    classify_departure_source,
    parse_synthetic_onestop_id,
    make_synthetic_onestop_id,
    synthetic_integer_id,
)

BAY_FEED = "f-sf~bay~area~rg"


def _is_bay(feed_id):
    return feed_id == BAY_FEED


def _no_resolve(_oid):
    return None


def test_synthetic_valid_routes_local_always():
    plan = classify_departure_source(
        f"511:{BAY_FEED}:CHILD1",
        resolve_stop_info=_no_resolve,
        is_bay_area_feed=_is_bay,
    )
    assert plan == DeparturePlan("local_always", f"511:{BAY_FEED}:CHILD1",
                                 feed_id=BAY_FEED, stop_id="CHILD1")
    assert plan.needs_511


def test_synthetic_malformed_falls_through():
    # Too few parts → not synthetic → resolve returns None → "none".
    plan = classify_departure_source(
        "511:onlytwo",
        resolve_stop_info=_no_resolve,
        is_bay_area_feed=_is_bay,
    )
    assert plan.kind == "none"
    assert not plan.needs_511


def test_synthetic_non_bay_feed_not_parsed():
    assert parse_synthetic_onestop_id("511:f-other:STOP", _is_bay) is None


def test_resolved_bay_area_routes_local_or_tl():
    def resolve(_oid):
        return {"feed_onestop_id": BAY_FEED, "stop_id": "EMBR", "integer_id": 1}

    plan = classify_departure_source(
        "s-bart-embarcadero",
        resolve_stop_info=resolve,
        is_bay_area_feed=_is_bay,
    )
    assert plan == DeparturePlan("local_or_tl", "s-bart-embarcadero",
                                 feed_id=BAY_FEED, stop_id="EMBR")
    assert plan.needs_511


def test_resolved_non_bay_routes_tl():
    def resolve(_oid):
        return {"feed_onestop_id": "f-dr5r-mta", "stop_id": "GRAND", "integer_id": 2}

    plan = classify_departure_source(
        "s-nyc-grandcentral",
        resolve_stop_info=resolve,
        is_bay_area_feed=_is_bay,
    )
    assert plan.kind == "tl"
    assert not plan.needs_511


def test_unresolvable_routes_none():
    plan = classify_departure_source(
        "s-unknown",
        resolve_stop_info=_no_resolve,
        is_bay_area_feed=_is_bay,
    )
    assert plan.kind == "none"


def test_511_disabled_predicate_returns_false():
    # When 511 is disabled the proxy passes a predicate that is always False.
    always_false = lambda _f: False
    syn = classify_departure_source(
        f"511:{BAY_FEED}:CHILD1",
        resolve_stop_info=_no_resolve,
        is_bay_area_feed=always_false,
    )
    assert syn.kind == "none"  # synthetic not recognized, unresolvable


def test_make_synthetic_onestop_id():
    assert make_synthetic_onestop_id(BAY_FEED, "CHILD1") == f"511:{BAY_FEED}:CHILD1"
    assert make_synthetic_onestop_id(BAY_FEED, "") is None
    assert make_synthetic_onestop_id(BAY_FEED, "a,b") is None


def test_synthetic_integer_id_stable_and_negative():
    oid = f"511:{BAY_FEED}:CHILD1"
    assert synthetic_integer_id(oid) == synthetic_integer_id(oid)
    assert synthetic_integer_id(oid) < 0
