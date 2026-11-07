# Line audit remediation (2026-11-07)

Baseline: main `f6f6763`; the pre-existing PR #2 head `1aa3fce` is merged into this branch to retain its cloud pricing, credential delegation and reconciliation fixes. This branch is for review, not automatic deployment or merge.

## Scope addressed

- D1–D4, D7: no replay of uncertain/accepted local booking writes, explicit selected-seat/region policy, no historical/random fallback, exact returned booking identity/time checks, durable uncertain outcome instead of retries, serialized booking mutations, truthful cancellation errors and no implicit sign-in during cancellation. Unknown outcomes require read/manual reconciliation; cancellation without a verified unknown booking ID is refused.
- D5: deactivate and drain native authentication/academic operations before clearing shared cookies, recheck activity after mutex acquisition, remove direct UI/academic import cookie exports outside the lifecycle boundary.
- D6: account generation in alarms, cold initialization barrier, durable WorkManager execution/restoration and re-arming for the active account. WorkManager/Android scheduling does **not** guarantee execution to the second. A stored write intent prevents repeat writes after a killed process. Only explicit opening-window rejections may be retried in a bounded wave after a successful no-active-booking read.
- D8–D10: import ownership filtering; bounded week/period parsing; revoked package consent must cover all requested privileges.
- D11–D13: channel-specific reminder checks; widget privacy on auxiliary text; unresolved points cannot generate misleading term/year/trend GPA. Currently conservatively withholds local subset GPA if any selected course remains unresolved; official GPA remains available.
- D14–D16: preserve actual location fix time and reject stale cached fixes; bound numeric expansion before allocation; GeoJSON longitude-first coordinates.
- D17–D20: immutable login input snapshots; numeric building-name boundaries; personal-timetable classroom results explicitly unknown rather than campus-wide availability; reminders use REMINDER_DELIVERED, not booking confirmation.
- UI1–UI5: region/date response generation isolation, serialized utility unlink/query, fail-closed web timetable import, no global cookie deletion for parcel login, privacy for GPA-derived views. File import is bounded to 2 MiB off the UI thread.
- MAP1–MAP3: deny embedded map geolocation, restrict embedded main-frame navigation to the configured HTTPS GIS origin, isolate floor/route/request identity, remove wrong-datum OSM fallback and invalidate old tile cache. External navigation is available for browser-controlled location consent.
- AP1–AP6: Jinzhi section regex, fail-closed structural/completeness checks, bounded mixed-week/parity parsing, validated term query parameters, HTML password-input recognition, safe Unicode entities. These adapters still have no production gateway transport integration; this is not a claim of live-school compatibility.

## Independent review follow-up

- Reject explicit invalid legacy booking identifiers rather than falling back to a different active booking; plan-scoped cancellation requires a verified positive numeric ID. Synthetic regression inputs do not contact a school.
- Preserve accepted local cloud prices/free payment states during sync unless authoritative order fields are supplied. Missing remote price/payment information remains unknown; remove date-only job matching and hard-coded billing labels.
- A separate reviewer checked booking/lock ordering/WorkManager callsites; no Android compilation was available.

## Verification

- `git diff --check`: passed locally.
- `python scripts/check-line-audit-safety.py`: passed; source invariants, **not Kotlin execution**.
- UI static guards and request identity model: passed in the private audit evidence workspace.
- Java 21 source-extracted protocol regex / Unicode probes: passed in the private audit evidence workspace.
- Added Kotlin/JUnit regression cases for failed booking reads, huge week ranges, numeric expansion, revoked empty consent, partial GPA, numeric building suffixes, GeoJSON order and synthetic protocol inputs.
- Local Gradle/JUnit/Android compilation **not executed**: no wrapper distribution/SDK cache; retry with writable GRADLE_USER_HOME failed to reach `services.gradle.org` (`Network is unreachable`). The draft PR's Android CI must be inspected before merge. No test pass claim is made for the added JUnit tests until CI runs them.
- No real school accounts, booking operations, credentials, payments or production services were used during remediation.

## Review gates

Check all Android module compilation, Robolectric/JUnit, lint and schema checks in CI, then exercise cold-start/switch-profile/unknown-result behavior on an emulator with synthetic endpoints. Verify supported school response contracts before enabling currently unintegrated academic adapters. Stricter malformed-response handling intentionally preserves cached data instead of guessing empty successful results.

## Upstream integration and release impact

Integrated upstream through `50b0e852597e20e3a9c4550a3165d0bfb6040aab`, retaining dining, Liquid Glass, moments/updater, full candidate lists, multi-day status displays and generation-safe server-job alarm backup. The backup invokes only the same registered cloud job, always with the configured cloud endpoint. Sync is reconciliation and does not automatically recreate plans or accept prices. Distinct server jobs/dates are not merged by date alone.

**School write gate:** no school-issued authorization evidence has been verified. `SchoolWriteAuthorization` therefore defaults closed with no user-setting bypass. Real automatic booking creation/FIRE and native school write actions are disabled; existing official manual WebViews, reminder-only mode, mock adapters and offline tools remain available, free local operation does not require SSO, and blocked automatic jobs do not cause delegation-secret uploads. Reopening requires independently reviewed school/provider authorization, scoped permission/time limits, injected mock and device regression evidence. This branch does not claim live-school compatibility. Users with existing reservations must manage them through the official school interface while this gate is closed.

**Dining/UI:** private lock-screen notification handling and cancellation on disabling dining; exclude disliked items without restoring them; favorites respect meal slots; preset ratings are not described as real public reviews. Wallpaper identity participates in glass cache keys and switches expose toggle state to accessibility.

**Moments:** preserve offline drafts while treating unacknowledged cloud publishing/sending as uncertain rather than success. Drafts have no working cloud-claim QR. Standard QR encoding replaces hash matrices; empty snapshots do not invent public posts. Ownership UI is not server security. The paired cloud change closes public moments read/write routes until authenticated ownership/capability controls are available; do not assume attaching a bearer token alone enables them. No real user data or writes were used for tests.
