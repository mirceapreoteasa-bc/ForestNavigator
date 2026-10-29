# AGENTS.md

Forest Navigator — Android app for forestry field navigation. Kotlin, two Gradle modules.

## Commands

```bash
# full check (the order that matters: core first, it is where the logic lives)
./gradlew :gcap-core:test :app:testDebugUnitTest :app:assembleDebug

# one test class — the fastest useful loop (~4s)
./gradlew :gcap-core:test --tests "org.gcap.core.nav.TransitTransitionTest"

# failure detail (gradle output is terse; the XML has the messages)
python3 -c "import xml.etree.ElementTree as ET; \
r=ET.parse('gcap-core/build/test-results/test/TEST-<FQCN>.xml').getroot(); \
print(r.get('tests'),'tests',r.get('failures'),'failures'); \
[print('FAIL',t.get('name'),(t.find('failure').get('message') or '')[:200]) \
 for t in r.iter('testcase') if t.find('failure') is not None]"
```

## Traps

**The two modules use different JUnit versions.** `:gcap-core` is JUnit 5
(`useJUnitPlatform()`, `org.junit.jupiter.*`). `:app` is JUnit 4 (`org.junit.*`).
Writing jupiter imports into an `:app` test fails to compile. Copy the imports from a
sibling test in the same module, never across modules.

**`FlightPlan` has three builders and only one is current.** `buildFromFlownLines`
(pipeline: join → fillet → heal → blend the real `flight_path`) is what the app calls.
`buildTraced` (A\* routing) and `buildByRule` are **superseded** — they were rejected
because they cut across ground the pilot avoided. `build(calls)` is the straight-line
fallback when `flight_path.shp` is missing. Read top-down you will hit the dead A\*
version first; don't wire it.

**Release builds are unsigned.** `signingConfigs` in `app/build.gradle.kts` is an empty
TODO. Debug APKs install fine; a release APK will not sign as-is.

## Constraints

**No `INTERNET` permission.** The manifest declares only `ACCESS_FINE_LOCATION` and
`ACCESS_COARSE_LOCATION`. This is deliberate — the app must work with no signal. All map
data is read from the device via SAF. Do not add a network dependency or permission
without the user asking.

**Emulator layer loading needs a manual folder pick.** The SAF picker does not open
automatically on the headless emulator; the known workaround is drawer → Downloads →
SELECT. This is why "verified on emulator" claims in the design doc often stop at "picker
lists the points" rather than "layers drawn". Recorded in
`docs/NAVIGATION_REQUIREMENTS.md` (~line 697).

**Real-data tests write outside the repo.** Ten test classes read the survey layers and
several write shapefiles to `~/ForestNavigator/flight_plan_export`. They skip
themselves via `Assumptions.assumeTrue` when the data is absent.

Data location: `FORNAV_DATA` env var, defaulting to `~/ForestNavigator/FOR_NAV`.
`:gcap-core` tests are configured for a 3 GB heap because of them — leave that alone.

## Where things live

- `gcap-core` — all navigation logic, pure Kotlin/JVM, unit-testable without a device.
  Button layout lives here too (`nav/MainGrid.kt`), deliberately, so the grid is testable.
- `app` — Android layer. `MapActivity` is the single activity and holds essentially
  everything: grid, map canvas, GPS, navigation, flight plan, network building.
- `docs/NAVIGATION_REQUIREMENTS.md` — the design document. Sections 9 (built and tested),
  10 (staged build order) and 11 (open questions) are the ones to read.

Java 21 toolchain for `:gcap-core`, JVM target 17 for `:app`.

## Sources of truth, and which ones lie

`MainGrid.kt` is authoritative for the grid: cell IDs, button IDs and labels. The user's
design lives in `forest_navigator_ODS.ods`, `settings_1.ods` and `layer_settings.ods`, and
they refer to cells by ID (`C1`, `A21`, `B3`). **Those ODS files lag the code** — they
contain no reference to `FLIGHT`, `Flight Path`, `on_air` or `on_ground`, so the air-mode
work is code-only. Treat the ODS as design *intent* and `MainGrid.kt` as current state.

The design doc is stale on numbers: section 9 claims "58 tests, 0 failures" when the
suite is far larger. Trust the executable source over any prose count.

To read an ODS (they are zips):

```bash
unzip -p forest_navigator_ODS.ods content.xml | grep -oP '<text:p[^>]*>.{0,80}Record track'
```

Text is split across XML runs, so a plain `grep` for a multi-word value can miss it
entirely. Verify a lookup actually returns before concluding a value is absent.

## Conventions

- No comments unless asked. Comments that exist carry *why*, not *what*.
- Real-data behaviour belongs in a `*RealDataTest` that prints its measurements to
  stdout; `testLogging { showStandardStreams = true }` makes them visible.
- Prefer fixing a model over tuning a threshold — three separate threshold sweeps in this
  repo each looked like progress and each was a symptom of a wrong assumption.