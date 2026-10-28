# Lessons from building and field-testing Forest Navigator

Written after testing on two real phones (Blackview BV5200, Android 12; BV9800, Android 9).
Every item here was found on a device. None were found by the test suite, which is the
point of writing them down: the tests were green throughout.

## The bugs that mattered most, and what they teach

### 1. Two errors that cancelled out produced a plausible number
Navigating showed "+5000 km" to a waypoint a few tens of km away. The GPS fix was being
passed through the inverse UTM projection as if it were metres, *and* the waypoint's
easting and northing were swapped. Neither alone would have been obvious, and together
they gave a stable, believable, completely wrong reading.

**Lesson:** a plausible number is not a correct one. When a distance looks wrong, check the
sign of the error before hunting for the cause. A wrong answer that barely varies with the
inputs is a fixed mistake, not a calculation error.

**Test now:** `NavDistanceTest` pins the real values and includes a case that asserts the
old mistake produces a million-metre reading, so it cannot come back.

### 2. Dead code that silently disabled a feature
`loadNetworks()` existed, was correct, and was never called. Networks were built to disk
and the graph thrown away, so Navigate always said "no network in this project" - even
immediately after a successful build.

**Lesson:** if a function has no callers, the feature it belongs to is not implemented,
regardless of what the code says. Search for callers before assuming something works.

### 3. Arrays indexed by the wrong variable
Region statistics reported a "largest region" with 20,014 edges in a network containing
10,591, and 2,474 km out of 1,293 km total. Two arrays were sized by edge count but
indexed by region, and edges were counted twice because adjacency holds one arc per
direction.

**Lesson:** a reported statistic needs an assertion that it cannot exceed the whole.
`assertRegionFiguresAreSane` now checks exactly that, and it failed against the old code.

### 4. Memory: the shape of the problem, not the size of the data
Roads building died on the BV9800. The log said:

    TimeoutException: BinderInternal$GcWatcher.finalize() timed out after 10 seconds

That is not "out of memory" in the usual sense - it is Android's GC watchdog killing a
process whose garbage collection took too long. The heap cap was 384 MB.

The tempting fix is to pre-simplify the data. That would have been wrong: the app already
simplifies during the build (769,217 -> 282,201 points). The real fix was
`android:largeHeap`, and roads then built in 67 s.

**Lesson:** read the actual exception. "It crashed while building roads" and "the device
could not hold the graph" lead to opposite conclusions about what to change.

Related, same session:
- Every pan started a new raster decode thread holding a full screen of pixels twice over,
  with nothing discarding superseded work. Now one worker with a ticket check.
- `saveView` ran on every GPS fix. Throttled to 5 s.

### 5. A crash blamed on the wrong feature
Selecting "Map up: compass" crashed both phones. Compass was innocent. The cause was a
scroll-position fix added the previous turn:

    ClassCastException: android.widget.FrameLayout cannot be cast to android.widget.ScrollView

It would have crashed on *any* Settings change.

**Lesson:** when a crash appears at the same moment as a new feature, check the previous
change before rewriting the new feature. And wrap cosmetic UI work so it cannot take the
app down - a redraw failure must never cost a user their session.

### 6. Uncaught exceptions in helper threads kill the app
A progress ticker used a raw `Thread` with `Thread.sleep`. Interrupting it when the build
finished threw `InterruptedException`, which on Android terminates the whole process.

**Lesson:** any thread you interrupt needs a catch. There is no partial credit.

### 7. Long text in a dialog silently destroys the list
The Default CRS picker opened with an explanatory paragraph. Android gave the text all the
space and the layer list zero height. The dialog looked complete and was completely
unusable - no items, no error.

**Lesson:** a screen that renders is not a screen that works. This was caught by
screenshotting the device, not by any test.

### 8. Placeholder text is not a label
The layer editor's two size boxes had greyed hints ("size", "label size") and no captions.
The hint disappears the moment you type, leaving an unlabelled field. The third control on
that row was captioned only "label", and the fourth was truncated to "label s".

**Lesson:** every control needs a caption that stays visible. Put units in it.

### 9. Colour named as a word is not a colour
"blue" in a dropdown tells you nothing about how a layer will look over the basemap, and
it is easy to miss entirely - which is exactly what happened when the feature was reported
as missing.

**Lesson:** if the value is a visual thing, show it visually.

### 10. Count what is, not what is not
Prepare layers reported "Working zone: degrees" for a folder that is UTM 10N. The test
asked "does any layer lack a projection?" and treated "no" as "this project is in degrees".
A folder whose .prj files could not be read got confidently told the wrong thing.

**Lesson:** decide a question positively. Count the layers that *are* geographic against
the ones that are projected.

### 11. Saving is not the same as persisting
Map up worked all session and was gone on restart - it lived only in memory. Every other
setting on that screen did persist, so the page looked consistent.

**Lesson:** "does this setting save?" must be answered by killing the app and looking again,
not by reading the setter. One setting out of nine failing is invisible unless each is
checked independently.

### 12. Spreadsheets outlive the decision to drop them
`settings_1.ods` and `layer_settings.ods` were read in four places long after the decision
to use `mirshp.json` alone. Each place worked, so nothing looked broken.

**Lesson:** when a source of truth changes, grep for the old one by name. "We decided not
to use it" is not the same as "nothing reads it".

## Process lessons

- **Test on both devices.** Every crash above except one appeared on only one phone. The
  BV9800 (Android 9, 1080x2340) and the BV5200 (Android 12, 720x1560) differ in heap cap,
  screen size, and available sensors.
- **Screenshot the UI.** Three of these bugs (1, 7, 9) were invisible to the test suite
  and would have been visible in a screenshot.
- **Build release variants for install.** A debug build has a different signing key and
  silently refuses to update a release-installed app.
- **Gradle flavour dimension.** shp-cap has `standard` and `bv9800` flavours; assembling
  `assembleDebug` alone produces nothing new.

## Applied to shp-cap (Forest Point Collector)

Built and installed on both phones, then checked against the above.

**Already right - do not "fix" these:**

- Layer settings captions every control (Symbol, Color, Size, Label, LblSize). This is the
  lesson from bug 8, already applied.
- Has Up/Down per layer. Forest Navigator is missing this.
- Settings are stored in `mirshp.json`, the same file and same JSON structure as Forest
  Navigator. One format across both apps.
- CRS handling is correct in the paths checked: `utmToLatLon(e, n, zone)` is given metres
  and returns `(lat, lon)`, which is then written as `(lon, lat)` - shapefile order. The
  bug from lesson 1 is not present.
- `Verify layers` already exists, checking sidecars, readable headers and known CRS.

**Still to check / worth fixing:**

- **Colour is a dropdown of words**, not a swatch - lesson 9 applies here too.
- **A header row lists column names** ("Layer ON/OFF Editable Symbol Symbol Color Symbol
  Size Label Label Size") that do not line up with the controls below, which sit on two
  rows with the checkboxes on the first. It reads as a table that is not one.
- **Fields truncate**: `GCALL_I`, `Call_Nur`, `single li..`. Captioned but too narrow.
- **Settings use an explicit Save button**, unlike Forest Navigator which saves as you go.
  Both are defensible; the Save button suits a form, the immediate save suits toggles.
  Worth confirming the Save actually commits every field, including ones scrolled off
  screen.
- **No ProjectScan.** shp-cap has its own `gcap-core` without the `nav` package, so
  `ProjectScan`, `NetworkBuilder`, `NetworkReader` and `Router` do not exist there. Not
  added - it is a meaningful amount of code and a decision about scope, not a copy-paste.
  If wanted, the natural entry point is the existing Verify layers screen.

## Housekeeping

Both phones were checked for leftovers from this work. Neither has test or scratch files.
The `*_network.*` shapefiles present are legitimate build output, not junk, and the app
now loads them at map start.

Note that the two phones currently hold *different* networks: the BV5200 has ACCESS and
TRAILS, the BV9800 has ACCESS and ROADS. Worth making consistent before they are used
side by side.