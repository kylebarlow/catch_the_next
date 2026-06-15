# Privacy Policy — Catch The Next

_Last updated: 2026-06-15_

Catch The Next ("the app") is a transit departures app for Android phones and Wear OS
watches. This policy explains what data the app handles. It applies to both editions:
**Catch The Next** (Google Play) and **Catch The Next: Bay** (F-Droid).

## What the app collects

**Location (approximate and precise).** The app uses your device location to find nearby
transit stops and to track the closest favorite stop. Location is used **in-app only** while
you are using those features. It is **not** sold, shared with third parties for advertising,
or used to build an advertising profile.

- The **Play** edition uses Google Play Services (FusedLocation) to obtain location.
- The **Bay (F-Droid)** edition uses the Android framework location APIs only (no Google Play
  Services).

**Favorites and settings.** Your favorite stops and preferences (units, alert thresholds) are
stored **locally on your device**. On the Play edition they may sync between your paired phone
and watch over the Wear OS Data Layer; this stays within your own devices. There is no user
account and no server-side storage of your favorites.

## Data sent to our backend

To look up stops and departures, the app sends transit queries (such as coordinates for
"nearby stops", or stop identifiers for departures) to our backend proxy at
`transitapi.nfshost.com`. Each request carries an app API key that identifies the app edition
(not you). We do not require or collect any personal account, name, or email.

The backend forwards requests to transit data providers as needed:

- **511 SF Bay** (`511.org`) and the regional GTFS feed, for Bay Area data.
- **Transitland** (`transit.land`), for stops outside the Bay Area (**Play edition only** — the
  Bay/F-Droid edition is restricted to Bay Area / 511 data and never reaches Transitland).
- **Nominatim / OpenStreetMap** (`nominatim.openstreetmap.org`), for place search/geocoding.

The backend keeps short-lived operational logs (standard web access logs) for rate limiting and
abuse prevention. It does not associate requests with a personal identity.

The backend itself is **open source** and part of this project's repository, so its behavior is
auditable and self-hostable.

## Permissions

- **Location** (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`) — nearby stops and closest-stop
  tracking, as described above.
- **Notifications** / **Foreground service (location)** (phone) — live departure tracking shown
  as an ongoing notification while you track a stop.
- **Internet** — to reach the backend.

## Your choices

- You can deny or revoke the location permission in Android Settings; stop search and
  closest-stop features will be unavailable, but you can still use saved favorites.
- You can remove favorites at any time within the app.

## Contact

Questions about this policy: **kb@barlo.ws**

Source code: https://codeberg.org/ursidaureus/catch_the_next
