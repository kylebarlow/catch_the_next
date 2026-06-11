"""In-process fixture builders for the gtfs511 tests.

We deliberately avoid binary fixtures in the repo — both the static GTFS zip
and the realtime protobuf are constructed at runtime so changes to fixture
content stay reviewable in plain Python.
"""

import io
import zipfile
from datetime import datetime, timezone

from google.transit import gtfs_realtime_pb2  # type: ignore


def make_static_gtfs_zip(*, service_dates: list[str], departure_time: str = "10:00:00",
                         extra_stops_rows: str = "") -> bytes:
    """Build a minimal but valid GTFS zip in memory.

    Topology:
        agency:   TA = Test Agency (America/Los_Angeles)
        routes:   R1 (TA), R2 (TA)
        trips:    T1 on R1, T2 on R2 (both service SVC1)
        stops:    PARENT (station), CHILD1 (parent=PARENT), STAND  (standalone)
        stop_times: each trip stops at CHILD1 at *departure_time*, then at STAND
        calendar: SVC1 active monday..sunday for the dates we pass in
        calendar_dates: empty (we don't model exceptions in the standard fixture)
    """
    files = {}
    files["agency.txt"] = "agency_id,agency_name,agency_url,agency_timezone\n"\
        "TA,Test Agency,https://example.com,America/Los_Angeles\n"
    files["routes.txt"] = "route_id,agency_id,route_short_name,route_long_name,route_type\n"\
        "R1,TA,1,Route One,3\n"\
        "R2,TA,2,Route Two,3\n"
    files["stops.txt"] = "stop_id,stop_code,stop_name,stop_lat,stop_lon,parent_station,location_type\n"\
        "PARENT,,Parent Station,37.7,-122.4,,1\n"\
        "CHILD1,C1,Parent Station Platform 1,37.7,-122.4,PARENT,0\n"\
        "STAND,SD,Standalone Stop,37.71,-122.41,,0\n" + (extra_stops_rows or "")
    files["trips.txt"] = "trip_id,route_id,service_id,trip_headsign,direction_id,block_id,shape_id\n"\
        "T1,R1,SVC1,Toward Downtown,0,,\n"\
        "T2,R2,SVC1,Toward Outbound,1,,\n"
    files["stop_times.txt"] = "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n"\
        f"T1,{departure_time},{departure_time},CHILD1,1,0,0\n"\
        "T1,10:05:00,10:05:00,STAND,2,0,0\n"\
        f"T2,{departure_time},{departure_time},CHILD1,1,0,0\n"\
        "T2,10:07:00,10:07:00,STAND,2,0,0\n"

    sd_lo = min(service_dates)
    sd_hi = max(service_dates)
    files["calendar.txt"] = "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"\
        f"SVC1,1,1,1,1,1,1,1,{sd_lo},{sd_hi}\n"
    files["calendar_dates.txt"] = "service_id,date,exception_type\n"

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, content in files.items():
            zf.writestr(name, content)
    return buf.getvalue()


def make_tripupdates_pb(*, predicted_epoch: int, trip_id: str = "T1",
                        stop_id: str = "CHILD1", stop_seq: int = 1,
                        delay: int | None = 60) -> bytes:
    """Build a GTFS-RT TripUpdates protobuf with a single prediction."""
    feed = gtfs_realtime_pb2.FeedMessage()
    feed.header.gtfs_realtime_version = "2.0"
    feed.header.incrementality = gtfs_realtime_pb2.FeedHeader.FULL_DATASET
    feed.header.timestamp = int(datetime.now(tz=timezone.utc).timestamp())

    entity = feed.entity.add()
    entity.id = "e1"
    tu = entity.trip_update
    tu.trip.trip_id = trip_id
    tu.trip.route_id = "R1"
    stu = tu.stop_time_update.add()
    stu.stop_id = stop_id
    stu.stop_sequence = stop_seq
    stu.departure.time = predicted_epoch
    if delay is not None:
        stu.departure.delay = delay
    return feed.SerializeToString()


def make_servicealerts_pb(*, header: str = "Test Alert", description: str = "Body") -> bytes:
    feed = gtfs_realtime_pb2.FeedMessage()
    feed.header.gtfs_realtime_version = "2.0"
    feed.header.incrementality = gtfs_realtime_pb2.FeedHeader.FULL_DATASET
    feed.header.timestamp = int(datetime.now(tz=timezone.utc).timestamp())
    entity = feed.entity.add()
    entity.id = "alert-1"
    a = entity.alert
    a.header_text.translation.add(language="en", text=header)
    a.description_text.translation.add(language="en", text=description)
    a.cause = gtfs_realtime_pb2.Alert.MAINTENANCE
    a.effect = gtfs_realtime_pb2.Alert.MODIFIED_SERVICE
    return feed.SerializeToString()
