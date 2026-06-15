# F-Droid submission notes — Catch The Next: Bay

The F-Droid edition is the `fdroid` product flavor of the `:phone` module
(`applicationId = com.kylebarlow.catchthenext.bay`). It carries **zero proprietary
dependencies** (no Google Play Services; FusedLocation and the Wear sync are confined to the
`play` flavor — see `shared-android/src/play` and `phone/src/play`).

## Verifying FOSS isolation

```
./gradlew :phone:dependencies --configuration fdroidReleaseRuntimeClasspath | grep -i gms
```

Expect **no output**. (`androidx.work` may appear — it is Apache-2.0, pulled transitively by
Glance, and is not a Google Play Services / proprietary dependency.)

## Committed public API key

`phone/build.gradle.kts` sets `bayPublicApiKey` as a committed literal. This is **intentional and
safe**, not a leaked secret:

- The backend hard-scopes this key to **511 / local Bay Area data only**. Any path that would
  reach Transitland is refused with `403 forbidden_upstream` (see `server/auth.py`,
  `server/proxy.py`).
- It is rate-limited server-side.
- The build reads no `local.properties` and no secrets, so the source build is deterministic /
  reproducible.

## Anti-feature: NonFreeNet

The app depends on a network backend plus third-party data (511, Nominatim). This may warrant the
`NonFreeNet` anti-feature flag. Mitigation: the **backend is open source in this same repository**
(`server/`) and is self-hostable, so the network dependency is not on a proprietary service.

## Build recipe (fdroiddata)

Submit an RFP / merge request to `fdroiddata` with a `Builds:` entry roughly like:

```yaml
Categories:
  - Navigation
License: GPL-3.0-or-later
SourceCode: https://codeberg.org/ursidaureus/catch_the_next
IssueTracker: https://codeberg.org/ursidaureus/catch_the_next/issues
RepoType: git
Repo: https://codeberg.org/ursidaureus/catch_the_next.git
AntiFeatures:
  - NonFreeNet            # if flagged by reviewers; see note above

Builds:
  - versionName: '1.0'
    versionCode: 1
    commit: v1.0          # tag matching versionName; bump versionCode monotonically
    subdir: phone
    gradle:
      - fdroid

AutoUpdateMode: Version
UpdateCheckMode: Tags
```

Tag a release whose name matches `versionName` and whose `versionCode` increases monotonically.

## Pre-submission checklist

- [x] Repo has an OSI/FSF-approved `LICENSE` at root (GPL-3.0-or-later); `License:` SPDX matches it.
- [ ] `:phone:assembleFdroidRelease` builds with no `local.properties`.
- [ ] GMS isolation check above prints nothing.
- [ ] Privacy policy (`docs/privacy-policy.md`) is current.
