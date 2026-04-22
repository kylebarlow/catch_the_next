# Catch The Next

Backend logic, CLI test harness, and API proxy server for a WearOS tile that shows the next transit departures from saved favorite stops, powered by the [Transitland v2 REST API](https://www.transit.land/documentation/rest-api).

## Repo layout

```
core/     Kotlin — shared core library
cli/      Kotlin — CLI test harness
wear/     Android — Wear OS application + Tile
server/   Python — lightweight Bottle/WSGI proxy that hides the Transitland API key
```

---

## App (Kotlin CLI)

### Quick start

Start the proxy server first (see Server section below), then:

```bash
cd app
cp .env.example .env
# fill in APP_API_KEY (must match a key in the server's APP_API_KEYS)
# set CATCH_THE_NEXT_BASE_URL if the proxy isn't on localhost:39217
./gradlew run --console=plain
```

The CLI walks through the full flow interactively:

1. Enter a lat/lon and search radius to find nearby stops
2. Select stops by number to save as favorites
3. Print next departures for all favorites — shows minutes until departure, route short name, and headsign

To build a standalone fat-jar:

```bash
cd app
./gradlew jar
java -jar build/libs/catch-the-next-1.0-SNAPSHOT.jar
```

Requires JDK 21. Gradle and Kotlin are managed by the wrapper.

---

## Server (Python proxy)

A stateless Bottle/WSGI app that wraps the Transitland API. Clients send requests with an `X-API-Key` header; the server validates, rate-limits, and forwards to Transitland with the real API key injected server-side.

Production: NearlyFreeSpeech.net running Apache + `mod_wsgi` (no JRE, no build step — just rsync Python files).

### Local dev

Requires Docker.

```bash
cd server
cp .env.example .env
# fill in TRANSITLAND_API_KEY and APP_API_KEYS in .env
docker compose up --build
```

The server listens on **port 39217** by default (change `ports` in `docker-compose.yml` if needed).

```bash
# Health check (no auth required)
curl http://localhost:39217/healthz

# Nearby stops (requires X-API-Key header)
curl -H "X-API-Key: your-app-key" \
  "http://localhost:39217/api/v2/rest/stops?lat=37.7766595&lon=-122.3946275"

# Departures for a stop
curl -H "X-API-Key: your-app-key" \
  "http://localhost:39217/api/v2/rest/stops/2173133854/departures?next=3600"
```

### API endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| `GET` | `/healthz` | none | Container/uptime check |
| `GET` | `/api/v2/rest/stops` | `X-API-Key` | `lat`, `lon` required; `radius` (≤2000, default 500), `limit` (≤50, default 20) |
| `GET` | `/api/v2/rest/stops/<id>/departures` | `X-API-Key` | `next` seconds (≤86400, default 7200) |

Responses mirror Transitland's shape. Auth failures → `401`. Rate limit exceeded → `429` with `Retry-After`. Bad params → `400`. Upstream errors → `502`/`504`.

### Rate limiting

500 requests/hour per IP, shared across all endpoints. Enforced by reading the Apache access log tail on each request (no separate database). Counter resets on daemon restart; suitable for a low-volume shared app key.

### Config (env vars)

| Var | Required | Default | Notes |
|---|---|---|---|
| `TRANSITLAND_API_KEY` | yes | — | Real upstream key, never sent to clients |
| `APP_API_KEYS` | yes | — | Comma-separated list of valid client keys |
| `TRANSITLAND_BASE_URL` | no | `https://transit.land/api/v2/rest` | Override for testing |
| `RATE_LIMIT_PER_HOUR` | no | `500` | |
| `ACCESS_LOG_PATH` | no | `/home/logs/access_log` | Set to `/var/log/apache2/access.log` in Docker |
| `UPSTREAM_CONNECT_TIMEOUT` | no | `5` | Seconds |
| `UPSTREAM_READ_TIMEOUT` | no | `10` | Seconds |

### Tests

```bash
cd server
pip install -r requirements-dev.txt
pytest tests/ -v
```

### Deploy to NFSN

```bash
cd server
NFSN_USER=myuser NFSN_HOST=mysite.nfshost.com ./deploy/push.sh
# then on NFSN: pip install --user -r /home/protected/server/requirements.txt
```

See `deploy/nfsn-htaccess.sample` for the `.htaccess` config. Set `TRANSITLAND_API_KEY` and `APP_API_KEYS` as env vars in the NFSN site/daemon configuration.

---

## Design decisions

**Proxy hides the Transitland key.** The Kotlin client calls the proxy with an app-level key (`X-API-Key` header, from `APP_API_KEY`). The real Transitland key lives only on the server.

**Log-based rate limiting.** The rate limiter reads the Apache access log rather than maintaining a separate database. Stateless, zero extra dependencies, survives restarts — appropriate for a low-volume tile app.

**UI-free Kotlin core.** `TransitlandClient`, `FavoritesManager`, `Stop`, and `Departure` have no Android or CLI dependencies and can be imported directly into the WearOS tile module.

**Departure timing.** Uses `departure.scheduled_utc` when available; falls back to parsing GTFS `HH:MM:SS` departure times (handles `>24:00` midnight-crossing trips).

**Favorites persistence.** CLI saves to `~/.catch_the_next/favorites.json`. The WearOS app will replace this with DataStore — `FavoritesManager` is the seam.

## Next steps

## Next steps

- [x] Automated tests: hardcode stop IDs from CLI sessions and assert departure shapes against the live API
- [x] WearOS tile module: import core packages, wire up `TileService`, render `Stop` + `Departure` data (scaffolded)
- [x] Android client: configure `CATCH_THE_NEXT_BASE_URL` and `APP_API_KEY` for the production proxy URL
- [ ] Favorite stop configuration activity: use the same `getNearbyStops` flow with a map/list UI
- [ ] Realtime data: surface `CANCELED` trips in the tile
- [ ] Refresh strategy: WorkManager vs tile's built-in `onTileRequest` freshness window
- [ ] Offline handling: cache last-known departures and show staleness indicator
