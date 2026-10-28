# Forest Navigator — Navigation Requirements

Status: **living document.** Section 9 records what is built and tested; section 10
lists what is still open. Anything marked *measured* was verified against the real
`FOR_NAV` data or by running code. Anything marked *assumed* needs confirmation.

---

## 1. Purpose

Forest Navigator (`for-nav`) is an offline Android application for **forestry road
navigation and helicopter navigation** in the field. It descends from Forest Point
Collector (`ShpPointCap`), keeping its shapefile, SAF, rendering and settings
machinery, and replacing point collection with navigation.

Two hard constraints shape everything:

- **Fully offline.** No `INTERNET` permission. All data is read through the Storage
  Access Framework from a user-chosen folder. *Measured:* the existing manifest
  already omits `INTERNET`, so this is a property to preserve, not to add.
- **The user must be able to check the app's work.** Every derived product —
  network, contour, count, route — is displayed and accompanied by the numbers that
  produced it, so a wrong answer is visible rather than trusted.

### 1.1 Deliberately deferred

- **Terrain awareness / hillshade as a navigation input.** The user has stated that
  terrain will come from the pilot's own devices. Hillshade remains a *display*
  product only. Revisit at field testing; keep the network model able to carry
  terrain-derived weights later without a format change.
- **Track-based network repair.** Design is fixed (section 6.5) but implementation is
  postponed, and is a strong candidate for a desktop tool.

---

## 2. Source specifications

The design intent lives in three spreadsheets in `~/ForestNavigator`. They are the
authority for UI intent; this document records the decisions taken where they are
ambiguous.

| File | Covers |
|---|---|
| `forest_navigator_ODS.ods` | Main page: **24 commands in a 4-column x 6-row grid**, 16 status readouts |
| `settings_1.ods` | 13 general settings, `Verify_Layers`, 6 network-creation commands |
| `layer_settings.ods` | 20 layers; per-layer ON/OFF, Editable, Symbol, Colour, Size, Label field, Label size, and draw order |

### 2.1 Main page commands

The sheet specifies a **grid of four buttons across and six rows down — 24 commands**,
laid out in this order:

| row | 1 | 2 | 3 | 4 |
|---|---|---|---|---|
| 1 | Start_MAP | Settings | Record track | Start Editing |
| 2 | Close_MAP | Layer settings | Stop recording track | Stop Editing |
| 3 | EXIT | Navigate | Direct_Line | Save Edits |
| 4 | Center at your location | Stop Navig | ROAD | Waypoint |
| 5 | Go to… | ACCESS | TRAIL | Add_Point |
| 6 | Pan | Zoom_IN | Zoom_OUT | MOB |

Three controls added since (Map up, Auto zoom, Network) occupy a seventh row.

### 2.2 Main page readouts

Mapsheet, Project name, Satellites, North arrow, Precision, Go to:…, Date, Time,
Bearing, UTM zone, easting, northing, Elevation, Scale, Latitude, Longitude.

*Measured gap:* the current `MapActivity` provides Longitude, Latitude, UTM zone,
Easting, Northing, Scale, Precision, Heading, waypoint, distance and bearing.
**Mapsheet, Satellites, Date, Time and Elevation are absent.** All five have existing
implementations in `ShpPointCap` to copy: `NtsSheets.kt` for the mapsheet and a
`GnssStatus.Callback` for satellite count.

### 2.3 General settings (`settings_1.ods`)

Layers folder · current map name (default `mirshp`) · Default Coordinate System ·
Save Map As · Save Edits every … seconds (default 40, blank = off) · Restore default
settings · Record track interval … seconds · Minimum distance … meters in track
(default 5) · Available scales for zoom (default 1:500 – 1:400000) · Default Scale ·
Default layer for "Go to…" (default `Ground_Call.shp`) · Search value in .dbf for
"Go to…" · Add Point layer (default `XGV_MP.shp`) · MOB point collection layer
(default `POINTS.shp`) · Track line collection layer (default `track.shp`).

Plus `Verify_Layers` and six network commands: Create/Display ROAD, ACCESS, TRAIL,
BIKE_TRAIL, ACCESS+ROAD, ACCESS+TRAIL.

*Measured:* `LayerStyle.Settings` already models all thirteen settings fields and
they are **entirely dead code** — there is no settings screen, and
`Settings.trackMaxDistanceM` is missing from both `settingsToJson()` and
`parseSettings()` so it never round-trips. The model is done; the UI and the write
path are not.

### 2.4 Layer settings (`layer_settings.ods`)

Twenty layers with symbol vocabulary: triangle, circle, square, single line, dash,
dotted line, no fill, simple fill, hashed /, hashed \. Plus Move UP / Move DOWN.

*Measured:* `MapCanvasView` already parses all of these symbol names, tolerantly and
case-insensitively. `LayerStyle` covers symbol, colour (12 named colours, no hex or
alpha), one size value, and label field + label size with a white halo. Missing:
per-layer transparency, and label colour is hard-coded.

---

## 3. What the existing code already provides

`for-nav` is **not** starting from zero. *Measured:* `MapCanvasView.kt`,
`LayerStyle.kt`, `SafMapDir.kt`, `Utm.kt`, `LayerImport.kt` and `ShpFormat.kt` are
byte-identical copies of `ShpPointCap`'s. Only `MapActivity.kt` and `NavigatorApp.kt`
are new. Around 2,100 lines exist today.

Inherited and worth protecting:

- `ShpFormat.kt` (544 lines) — bbox-filtered shapefile reader plus **writers**
  (`buildPolylineShp`, `buildPolylineShx`, `buildDbf`), and fuzz-hardened bounds and
  non-finite-coordinate rejection.
- SAF layer — `MapFileDir` with cached folder listings. *Measured:* caching
  `listFiles()` took Verify Layers from 45 s to 5 s, and MOB/Save from 25–30 s to
  ~0.13 s. Do not regress this.
- Render cache — per-layer bitmap with a 40 MB LRU budget, draw-time simplification,
  label viewport gating, bbox cull before reading vertices, smallest-first background
  load.
- GPS — `LocationManager.GPS_PROVIDER` with an **explicitly hand-written**
  `LocationListener`. *This is deliberate:* a Kotlin SAM lambda throws
  `AbstractMethodError: onProviderDisabled` on API 28 the moment the user disables
  GPS or power saving kicks in. Never convert it to a lambda.
- `Utm.kt` — forward and inverse Transverse Mercator. **Verified correct** against
  Snyder's working manual; round-trip error 2×10⁻⁴ m in an independent port.

---

## 4. Measured findings about the data

All from `~/ForestNavigator/FOR_NAV` at the time of writing.

### 4.1 The dataset is already multi-CRS

| Count | CRS |
|---|---|
| 16 layers | `NAD_1983_UTM_Zone_10N` (EPSG:26910) |
| 2 layers | **geographic WGS84 (degrees)** — `points.shp`, `tracks.shp` |

Geometry types are mixed: Point, PointZ, PolyLine, PolyLineZ, Polygon, PolygonZ.
`delineation.shp` alone holds **78,092 features**.

Two consequences: reprojection must be per-layer, and NAD83 vs WGS84 differ by
roughly 1–2 m, which matters against a 5 m minimum-distance gate.

### 4.2 Attribute names are degenerate

The layers were round-tripped through an old Arc/Info coverage, which destroyed the
field names. *Measured:*

| Layer | Features | Fields |
|---|---|---|
| `air_calls` | 291 | `OBJECTID`, `F001A`, `F2`, `F3`, `F4` |
| `flight_path` | 1,195 | `OBJECTID`, `SHAPE_Leng` — **nothing else** |
| `access` | 5,177 | `OBJECTID`, `SHAPE_Leng` — **nothing else** |
| `roads` | 940 | `ROAD_SURFA` — rough 657 / loose 259 / paved 24 |
| `tracks` | 0 | `OBJECTID`, `SHAPE_Leng` (empty) |
| `ground_call` | 66 | `Road_Air_W`, `Call_Num` |
| `landings` | 30 | `ELEVATION`, `X`, `Y`, `NOTES`, `Landing` |
| `xgv_mp` | 16 | `GCALL_ID` |
| `points` | 5 | `NAME` |

Only `ROAD_SURFA`, `Call_Num`, `GCALL_ID` and the `landings` fields survived with
usable names. `roads.ROAD_SURFA` is immediately useful for routing weights.

### 4.3 Extent

`access.shp` spans lat 49.198 – 51.255 and lon −125.26 – −122.94, roughly **245 km
north–south**. Every other layer sits in lat 50.6 – 51.4, so `access.shp` reaches
~200 km further south than the rest. The 49th parallel area is a **test block, far
from the operational area**; the user may build a full layer set around it.

All layers are UTM zone 10 with **208 km of clearance to the zone 9/10 boundary**, so
multi-zone support is not exercised by the current data. A 200 km-wide project spans
~2.8° of longitude and therefore *can* straddle a boundary. Maximum scale distortion
for a project centred on a boundary is ~0.05 %, negligible for display but a reason
to compute distances geodesically.

### 4.4 Rasters

| File | Res | Size | On disk | Compression | Overviews | Tiled |
|---|---|---|---|---|---|---|
| `google5m.tif` | 5 m | 23132×15325 | 134.9 MB | JPEG | none | no |
| `sattelite4.tif` | 5 m | 23132×15325 | 115.1 MB | JPEG | none | no |
| `osm4.tif` | 10 m | 11566×7663 | 354.6 MB | none | none | no |

All EPSG:26910, 4-band uint8, all covering 115.7 × 76.6 km.

At 200 × 200 km (3.5× the area) a 5 m raster is 40,000 × 40,000 px = **6.4 GB
uncompressed**. A single GeoTIFF is therefore not viable on a phone; a tiled pyramid
with overviews is mandatory, produced by a prep step (section 6.4).

Source resolution caps useful scale: 5 m native is roughly equivalent to max zoom
12.2, so these rasters are usable to about **1:25,000**, not 1:5000. Better source
imagery is needed for road-level navigation detail.

---

## 5. Defects found in the existing code

*Measured, not yet fixed.*

| # | Defect | Effect |
|---|---|---|
| 0 | `ShpFormat.dbfHeader` read the dBASE record count as 2 bytes while `buildDbf` writes 4 | **Fixed** — tables over 65,535 rows lost their tail; also present in `ShpPointCap`. See 9.6. |
| 1 | `MapCanvasView` has no `Matrix` and no `rotate()`; `mppxE`/`mppxN` computed independently | Rotation is structurally impossible; the map is stretched whenever view aspect ≠ 500×400 m |
| 2 | `windowMeters()` returns 500 m × 400 m at 1:10,000 | Far too tight for a 200 km project; fixed 1.25 aspect regardless of screen shape |
| 3 | `panning` was never assigned `true` | **Fixed** — drag now always pans and suspends following |
| 4 | `showP1P2P3 = false` but `onMapTap` calls `setP1P2P3(...)` | **Fixed** — waypoint marker now draws |
| 5 | `Utm.classifyCrs` matched the literal `"UTMZONE10N"` | **Fixed** — any zone, EPSG codes and central-meridian fallback now recognised (9.8) |
| 6 | `workingZone()` hardcoded to `10` | **Fixed** — derived from the first layer that declares a zone |
| 7 | `Settings.trackMaxDistanceM` absent from `settingsToJson()` and `parseSettings()` | Never round-trips |
| 8 | `mapLoading` assigned, never read | Mid-load commands are not refused |
| 9 | Hit-testing is bbox ±5 m, in the Activity not the view | Cannot pick a line or polygon; `wpLabel` shows the layer stem, not the feature |
| 10 | Test directories exist but are empty | `ShpPointCap` has 173 tests including a shapefile fuzz suite |

---

## 6. Architecture decisions

### 6.0 Three stored networks

The user has settled this: **three** network layers, not six.

| Cell | Label | Meaning |
|---|---|---|
| C4 | ROAD | the ROAD network |
| B5 | ACCESS | the ACCESS network |
| C5 | TRAIL | the TRAIL network |

`BIKE_TRAIL`, `ACCESS+ROAD` and `ACCESS+TRAIL` from `settings_1.ods` are **not**
required. In `on_air` mode C4 becomes **Flight Path** and C5 becomes **ROUTE**.

### 6.1 Six stored network layers

`settings_1.ods` asks for ROAD, ACCESS, TRAIL, BIKE_TRAIL, ACCESS+ROAD and
ACCESS+TRAIL, and **the user has decided these are six stored layers**, not presets
over one graph. Each "Create X_network" therefore materialises its own shapefile, and
"Display X_network" toggles it.

This supersedes the earlier recommendation to build a single weighted graph. The
reasoning that drove it still holds and is applied *within* each stored network: edges
carry `class`, `speed_kph` and `oneway`, so a network is re-weightable without
rebuilding geometry. What is given up is storage - each network is a full copy of its
geometry - which is acceptable because they are exchangeable shapefiles that a desktop
tool can also read.

Candidate source layers are `access`, `roads` and `tracks`, singly or combined;
`transect` is excluded.

*Not yet built:* the six Create/Display buttons are still stubs. The single "Network"
button currently builds one network from all visible line layers, which is the
mechanism the six will each call with a different subset and class.

Default speeds (*confirmed by the user for trails*):

| class | speed | basis |
|---|---|---|
| ROAD, paved | 40 km/h | `roads.ROAD_SURFA = 'paved'` |
| ROAD, rough | 25 km/h | `roads.ROAD_SURFA = 'rough'` |
| ACCESS / loose | 15 km/h | `roads.ROAD_SURFA = 'loose'`, `access` |
| BIKE | 12 km/h | *held pending more information*; a DEM may later inform this |
| TRAIL | **3 km/h** | *confirmed by the user* |

Only 24 of 940 road lines are paved, so a "road network" built from `roads` is
predominantly rough and loose; worth checking this matches field practice.

### 6.2 A network is a shapefile line layer

A built network is written as a noded Polyline layer, every edge starting and ending
at a node, with these attributes:

| Field | Meaning |
|---|---|
| `class` | ROAD / ACCESS / TRAIL / BIKE / AIR |
| `speed_kph` | travel speed; defaults from class, overridable per feature |
| `oneway` | 0 both, 1 forward only, 2 reverse only |
| `surface` | paved / rough / loose / trail |
| `conf` | confidence, 0–100; see 6.5 |
| `src` | originating source layer |

This keeps networks inspectable in QGIS and exchangeable between users, and matches
the app's existing read/write capability.

### 6.3 Connection tolerance is a measured, reported setting

The "maximum distance between two lines to be connected" is the node-welding
tolerance. **Decision:** default **3 m**, adjustable **0.5 – 5 m**, and every build reports
edges, nodes, regions, welds and splits so the result can be verified.

**Hard limit of 5 m for any automatic build.** Welding lines that genuinely do not
meet will confidently "build" a road across a collapsed bridge or a landslide,
producing a network that routes across ground nobody can cross. An automatic build
must never invent infrastructure. `NetworkBuilder` therefore *refuses* a tolerance
above `MAX_UNSUPERVISED_TOLERANCE_M = 5.0` unless constructed with
`allowSupervisedGaps = true`, which only the supervised repair flow (6.5) may pass.

Bridging a real gap is legitimate — but it requires a person to look at it, ideally
supported by a surveyed track across the gap. That is the supervised path, not a
slider.

*Measured on the real layers (6,117 polylines, 111,178 edges). "Largest region" is
reported by **edge count and length**, which is the meaningful measure:*

| tolerance | regions | largest by edges | largest length | endpoints welded |
|---|---|---|---|---|
| 1 m | 3,642 | 36,462 | 467 km | 14 % |
| 3 m | 3,387 | 36,462 | 505 km | 16 % |
| 5 m | 3,151 | 36,462 | 612 km | 18 % |
| 8 m | 2,913 | 38,164 | 972 km | 20 % |
| 12 m | 2,626 | 49,660 | 1,212 km | 24 % |
| 20 m | 2,074 | 65,020 | 1,522 km | 30 % |
| **30 m** | 1,706 | **55,476** | **1,337 km** | 36 % |

**Finding 1 — tolerance is not the lever it appears to be.** Even at 30 m only 36 %
of ~12,234 line ends weld. Most ends are tens of metres apart because the layers are
separately digitised reaches, not near-misses. Tolerance will not unify the network;
2–5 m is a sensible working range.

**Finding 2 — report region size in edges, never in nodes.** An earlier run of this
sweep showed the largest region apparently *shrinking* from 18,206 to 10,865 as
tolerance rose, which looked like a topology bug. It was a bad metric: welding
merges nodes, so a region's **node** count falls even as it covers more road. Node
count is not a progress measure for this operation. `BuildReport` now carries
`maxRegionEdgeCount` and `maxRegionLengthKm` for exactly this reason.

**Finding 3 — over-welding past ~20 m actively destroys connectivity.** At 30 m the
largest region *loses* 185 km and 9,500 edges despite welding the most endpoints.
Mechanism: when a tolerance is wide enough to collapse several nearby junctions into
one node, edges that previously bridged two areas become self-loops at that node and
connect nothing. Net effect is worse connectivity from more merging. **Do not offer
tolerances above ~20 m, and keep the default at 3–5 m.**

### 6.4 Raster display only, prepared offline

Display-only, no annotation, no measurement. **Decision:** render a tiled pyramid
with overviews; **no GDAL on device**. Either a desktop prep step producing tiles, or
libgeotiff (≈1 MB) for reading user-supplied GeoTIFF. Both preserve the offline,
no-`INTERNET` property, and both reuse the existing per-layer bitmap LRU cache.

Requirements: an **overzoom policy** for scales finer than the source (nearest or
bilinear), and an overviews pyramid, without which a 200 km raster is unusable.

`settings_1.ods` gains a **raster preparation** entry (section 9, not yet built).

Hillshade is a 1-band raster rendered through a colour ramp — the same pipeline, no
extra machinery.

### 6.5 Track-based repair is additive, never destructive

The user's scenario: *"Use track … to update … network"* where a recorded track
within ~20 m is judged more reliable than the existing line.

**Decision:** never rewrite base network geometry.

- `conf` (confidence) on each edge expresses reliability.
- A separate **override layer** records `(track_id, replaced_edge_ids, geometry,
  timestamp, author)`.
- Routing prefers higher-confidence geometry.

This makes repairs reversible, keeps provenance for audit, and structurally prevents
the "destroys more than it repairs" outcome, because repairs are additions. The 20 m
proximity search needs only a uniform grid index.

Deferred to a desktop tool, but the data model above is what it will consume.

### 6.6 Distances and bearings are geodesic

Never derived from projected easting/northing arithmetic. A project spanning two UTM
zones has real scale distortion, and mixing an easting from one zone with a northing
from another is meaningless. `Geodesy` in `gcap-core` provides haversine distance and
initial bearing for readouts and off-route tests.

### 6.7 Per-region graphs

Disconnected components are labelled at build time. A 245 km project with an outlying
block is not one navigable graph, and "nearest edge" lookups must not return
something unreachable. Route requests can be restricted to a region; the UI should
default to the region containing the user.

---

## 7. Data fields required from the user

### 7.1 Blocking helicopter mode B

Mode B is *"follow `flight_path` with `air_calls` as consecutive waypoints in their
order"*. Measured gaps:

- **`air_calls` has no order field.** `F001A` looks like a sequence but is cyclic —
  it wraps `9010A → 0011A → 1012A` and ends `0217A → 1001A`, failing a monotonicity
  check. `OBJECTID` has only **30 distinct values across 291 rows**.
- *File order is nonetheless meaningful:* median gap between consecutive calls is
  **2,572 m** across a 112.8 km span, with only two discontinuities — 42.7 km at
  call 268→269 and 57.7 km at 290→291. Those two are the natural flight boundaries.
- **`flight_path` has no attributes at all**, so segments cannot be attributed to a
  flight or given an order.

Required, since the user is editing the layers anyway:

| Layer | Field | Purpose |
|---|---|---|
| `air_calls` | `flight_id` | groups calls into flights |
| `air_calls` | `seq` | 1..n within a flight (a combined `1-014` also works) |
| `flight_path` | `flight_id` | groups segments to a flight |
| `flight_path` | `leg` *(optional)* | intended order |

Without `flight_id` on both, flight grouping must be inferred from spatial gaps.
That works for the current data but is fragile.

### 7.2 Attribute names

Restore meaningful names across the dataset. `F001A`, `F2`, `F3`, `F4`,
`SHAPE_Leng` and similar are coverage-conversion artefacts. `roads.ROAD_SURFA` shows
the pattern that should be followed.

---

## 8. Component specifications

### 8.1 Map transform

Single `metresPerPixel`, rotation about the view centre, exact inverse. Derived from
`map/MapTransform.kt` (built, section 9). Replaces the anisotropic affine and fixes
defect 1 and 2 together.

Scale denominator must be computed from physical pixels — `scaleDen =
metresPerPixel × dpi / 0.0254` — so the same scale number means the same thing on any
device.

### 8.2 Orientation

Three modes: **COURSE_UP** (default), **WAYPOINT_UP**, **NORTH_UP**.

Within COURSE_UP, per the user's rule:

- moving → course over ground
- stationary < 30 s → hold last heading
- stationary > 120 s → north up

Two implementation requirements:

- The heading window is measured in **metres (~100 m)**, not seconds. Helicopter
  speed spans 0–200 km/h, so a time window describes no consistent stretch of ground.
- Heading is the **principal axis of a least-squares line fit** over the window, not
  the bearing between consecutive fixes. Differencing raw fixes amplifies receiver
  noise, which is what makes naive heading unusable at a standstill.

A moving threshold of ~1.0 m/s with hysteresis is assumed; confirm in the field.

Bearing changes must be rate-limited rather than snapped, so one bad fix cannot make
the map lurch. The reason for the current orientation (course / holding / waypoint /
north) is shown to the user.

### 8.3 Network construction

1. Clean input: drop duplicate and zero-length vertices.
2. Weld endpoints within tolerance into shared nodes, using a snapped grid key
   rather than float equality.
3. Detect interior crossings and split both segments, so a route can turn at a
   crossroads. Crossings count **across layers** — an access line meeting a road must
   form a junction.
4. Emit noded edges and rewritable polylines.
5. Build adjacency, honouring one-way restrictions, and label connected components.

**Two traps already hit and fixed**, recorded so they are not reintroduced:

- Spatial index granularity must be sized from the data extent, *not* from the
  welding tolerance. A 170 km line indexed at a 3 m cell is registered in ~56,000
  buckets and exhausts memory.
- Each crossing must split **both** participating segments. Recording only the
  scanned one leaves the graph broken while looking plausible.

### 8.4 Routing

A* on either travel time or ground distance, with a straight-line heuristic scaled by
the fastest network speed.

Requests are in **world coordinates, not node indices** — a vehicle starts part way
along a road, not at a junction. Each end snaps to the nearest point on any edge and
the partial edge is costed. Start and destination stubs run in *opposite* directions,
so one-way checks differ per end, and a zero-length stub has no direction to violate.

Multi-waypoint routing visits waypoints **in the order given and never reorders
them** — for helicopter navigation the sequence comes from the air-call numbers and
must be flown as given. A failed leg is reported with its index rather than silently
dropped.

Off-route detection reports distance from the route and remaining distance, treating
the snap stubs as real legs so a route that starts mid-road does not read as
"off route" for its first hundred metres.

### 8.5 Multi-zone support

Three specific changes:

1. Generalise `Utm.classifyCrs` to parse any UTM zone by regex and return the zone.
2. Remove the hardcoded `workingZone()`; keep one working CRS per project but compute
   the *displayed* zone from the current position.
3. Compute all navigation distances geodesically (6.6).

Preserve the existing rule: **follow whatever the `.prj` says; never relabel an
existing file's CRS.**

---

## 9. What is built and tested

`gcap-core` is a pure Kotlin/JVM module with JUnit 5, so navigation logic is tested
without a device. **58 tests, 0 failures.**

| File | Contents |
|---|---|
| `nav/MapTransform.kt` | Rotation-capable isotropic transform; `Geodesy` (haversine distance, bearing, point–segment distance, destination) |
| `nav/HeadingEstimator.kt` | Least-squares course over an N-metre window, speed gate, quality and straightness, stale-receiver handling |
| `nav/Orientation.kt` | `OrientationMode`, `OrientationController`, `OrientationReason`, rate-limited slewing |
| `nav/Network.kt` | `NetworkBuilder`, `NetworkGraph`, `NetworkEdge`, `RouteClass`, `OneWay`, `BuildReport`, `NodedLine` |
| `nav/Router.kt` | `Router`, A*, `SnapResult`, `Path`, `RouteResult`, `MultiRouteResult`, `progressAlong` |

Verified properties include: lossless world↔screen round trip at every bearing; a
rotated map is not sheared; noise alone is never reported as movement; the 30 s /
120 s stationary rules; time and distance metrics choosing different routes; one-way
edges refusing the wrong direction; ordered waypoints; broken legs reported; a
40 × 40 street grid with 0.8 m vertex jitter welding into a **single** connected
network.

**Against the real layers** (`NetworkRealDataTest`, skipped when data is absent):
6,117 polylines → 109,131 nodes, 111,178 edges, 3,387 regions, 1,972 welds, 4,980
splits, **built in 442 ms**, with routing confirmed inside a region.

### 9.2 Canvas rewiring (done, not yet seen on a device)

`MapCanvasView` now delegates to `MapTransform` and supports a bearing:

- `bearingDeg` rotates about the view centre; the north arrow turns with the map so
  north is still identifiable once the map is not north-up.
- **Isotropic scale.** Ground coverage is now a single `metresPerPixel`; the vertical
  extent follows the real view aspect instead of a fixed 400 m. The nominal
  convention (500 m across at 1:10,000) is preserved, so the existing scale list
  behaves as before.
- **Rotation-aware culling.** The viewport rectangle is the ground bounding box of
  the four screen corners mapped back through the inverse transform; a naive
  axis-aligned box around the centre would miss corners once rotated.
- **Rotation-aware panning.** Dragging maps the two touch points through the inverse
  transform, so panning does not drift as the map turns.
- Cache invalidation now includes the bearing.
- `MapActivity` drives the bearing from `HeadingEstimator` +
  `OrientationController`, adds a **Map up** button cycling course/waypoint/north,
  and reports the current reason and bearing in the readout.
- **Pan button restored** (defect 3) and the **waypoint marker is now shown**
  (defect 4).
- Local `haversineM`/`bearingDeg` copies replaced by the tested `Geodesy`.

`ZoomScale` was extracted into `gcap-core` so the canvas's zoom convention is unit
tested without a device.

**Scale caveat worth knowing:** because ground coverage is
`500 m × (scale / 10000)`, the nominal scale behaves as a wide-angle zoom index:

| nominal scale | ground across |
|---|---|
| 1:10,000 | 0.5 km |
| 1:60,000 | 3 km (road level) |
| 1:250,000 | 12.5 km |
| **1:1,000,000** | **50 km — default, block overview** |
| 1:4,000,000 | 200 km (whole block) |

Useful working scales therefore sit high in the settings list, and **only the top of
the list covers the full 200 km block**. A low default scale would open the app
looking at a single road.

*Not yet verified on a device:* the emulator would not stay up in this environment,
so the on-screen result is unconfirmed. The transform maths is covered by 15 core
tests and the zoom convention by 8 more, but the canvas glue itself still wants a
visual check.

### 9.3 Panning across a block larger than the screen (done)

At 1:1,000,000 the view spans ~50 km across (~33 km tall), so a 200 km block needs
roughly four screen-widths of panning. Three things were needed to make that usable:

- **Dragging always pans.** Previously a drag did nothing unless the Pan button had
  been armed, and that button was never wired, so panning was unreachable. There is
  no arming step now.
- **Dragging suspends map-following.** With Follow on, the view was re-centred on
  every GPS fix (2 s), so anything panned to snapped straight back — fatal over a
  200 km block. `onUserPan` tells the host, which switches Follow off and the button
  label updates. The single Follow button is now the only control; the separate Pan
  button was removed as redundant.
- **Panning is clamped to the data extent** (`clampToDataExtent`). Panning was
  unbounded, so it was possible to drift into blank space and be convinced of being
  lost. The centre is held within the loaded extent plus half a viewport, so the edge
  of the block stays reachable but the map cannot vanish.

Clamping deliberately applies to *manual panning only*. While following, the view
centres on the user even outside the data — the readout shows the true position, and
displacing the view from a real fix would be worse than an empty-looking map.

### 9.4 Speed-adaptive scale (done)

Implemented in `SpeedZoom` from the user's band table, default 1:20,000 (helicopter
transit) on opening:

| speed km/h | nominal scale | ground across view (reference 500 m) |
|---|---|---|
| over 200 | 1:50,000 | 2.5 km |
| 199 – 100 | 1:20,000 | 1.0 km |
| 99 – 70 | 1:15,000 | 750 m |
| 69 – 50 | 1:10,000 | 500 m |
| 49 – 40 | 1:7,500 | 375 m |
| 39 – 30 | 1:5,000 | 250 m |
| 29 – 15 | 1:3,000 | 150 m |
| 14 – 5 | 1:2,000 | 100 m |
| under 5 | 1:1,000 | 50 m |

Two refinements over a plain lookup, both needed in practice:

- **Hysteresis of 8 km/h plus a 1.5 s dwell.** Parked on a threshold, GPS speed
  wanders across it continuously; a naive lookup would change scale many times a
  second. A change is adopted only once the speed is clearly past the boundary and
  has stayed there.
- **Damped stepping, one ladder rung per update.** Snapping from 1:2,000 straight to
  1:50,000 is disorienting at speed.

An **Auto zoom** toggle exists so the user can override it; a manual drag also
suspends following, and speed readouts are shown in the overlay.

**These are starting values, not a calibration.** The 400–600 m observation that
prompted the table was a reference point to build plausible bands around, not a
requirement, so `ZoomScale.REFERENCE_GROUND_WIDTH_M` stays at the inherited 500 and
the table above stands as specified.

Adapting the zoom to a particular screen or task is deliberately deferred to the
Settings screen, which already has **Default Scale** and **Available scales for zoom
functions** waiting for it in `settings_1.ods`. That is the right home for it: the
band table is a sensible default for everyone, while the precise amount of ground a
given user wants on screen is personal to their device, their vehicle and the task.
Candidate controls, for when the Settings screen is built:

- a preferred **ground width** in metres, overriding the derived scale outright;
- a **scale offset** in stops applied to every band, for users who consistently want
  more or less ground;
- per-band **editing**, so a user can move a single threshold;
- disabling auto-zoom entirely, which already exists as the Auto zoom toggle.

Note the nominal scale and the scale a device *reports* are different quantities
(`physicalScaleDenominator` uses screen density); the readout should make clear which
one is shown, since a single number means different things on different screens.

### 9.5 Network layer writer (done)

`NetworkWriter` turns a built graph into an ordinary Polyline shapefile — `.shp`,
`.shx`, `.dbf`, `.prj` — returned as byte arrays so the app can write them into a
SAF folder it has no direct filesystem access to.

One feature per **noded chain** rather than per edge: writing one feature per edge
would be tidier for routing but shreds every road into 2-point fragments on screen.
Each feature carries `CLASS`, `SRC`, `SPEED_KPH`, `ONEWAY`, `CONF`, `N_EDGES`,
`LEN_M`, `REGION`, plus `NODE_A`/`NODE_B` so **the routing graph can be rebuilt from
the written layer alone**. Numeric fields are written as dBASE `N`, not text, so QGIS
and scripts see numbers.

`.prj` is generated for any UTM zone and datum, in the spelling the app's own
`classifyCrs` recognises.

Proven end to end on the real layers: 6,117 polylines → 111,178 edges → **111,178
features written and 111,178 read back**, 2,630 km of network, 3,387 regions. Build
509 ms, write 606 ms, output 20 MB.

### 9.6 Defect found and fixed: dBASE record-count truncation

`ShpFormat.dbfHeader` read the record count with a **2-byte** read, but `buildDbf`
writes it as **4 bytes** — the dBASE standard, and what the writer's own comment
says. Every table with more than 65,535 rows silently lost the tail.

It surfaced as the network layer writing 111,178 features but reading back as
45,642, which is exactly `111178 mod 65536`. **The same bug is present in
`ShpPointCap`'s copy of `ShpFormat`**, so that app is affected too and should be
fixed there as well.

This project's own data triggers it: `delineation.shp` has 78,092 features, so its
DBF rows would have desynchronised from its 78,092 geometries — 12,556 labels lost
and every label after that attached to the wrong shape. Fixed in `for-nav`, with a
regression test building a 70,000-row table.

### 9.7 On-device verification status

The emulator now boots and the app was installed and run:

- **Verified:** installs, launches, renders the full button bar, no
  `AndroidRuntime` errors. Confirms the activity, layout and navigation wiring are
  sound.
- **Not verified:** loading real layers. The SAF picker on this headless emulator
  image does not expose storage roots in its drawer, and a hand-written preference is
  not a real system grant, so the folder cannot be selected non-interactively.

Rather than leave rotation-dependent canvas code untested, `viewportBounds` was moved
into `MapTransform` in `gcap-core` and covered by tests: north-up reproduces the exact
axis-aligned window, rotation provably exposes more ground, and every screen corner is
asserted to lie inside the reported bounds at seven bearings. **A real map with real
geometry is still the outstanding check** and needs a hands-on run.

### 9.8 Multi-zone support (done)

`Utm.classify` now returns a `LayerCrs(kind, utmZone)` instead of a bare enum, and
recognises **any** UTM zone however it is spelled: `UTM_Zone_10N`, `UTM zone 33N`,
`WGS 84 / UTM zone 33N`, `EPSG:32610`, `EPSG:32711` (southern hemisphere), and a
custom Transverse Mercator with only a central meridian to go on. Previously anything
outside zone 10 was classified `OTHER` and **silently skipped**.

Three things followed:

- `projectShapes` no longer returns UTM layers untouched. A layer authored in another
  zone is reprojected through WGS84 into the working zone; before, it was drawn
  hundreds of kilometres out because its eastings were read as if local.
- The **working zone is a `var`, not the constant 10**, and follows the first layer
  that declares one, matching "default coordinate system = first layer" in
  `settings_1.ods`.
- Only same-zone geometry seeds the overall view extent. A foreign zone's bounding box
  is in its own frame and would wreck the fit-to-data calculation.

Covered by 10 new app-module tests (zones 9/10/11/33/34, EPSG north and south, WKT
spacing variants, central-meridian fallback, Albers, missing .prj, and a regression on
the project's own NAD83 zone 10 sidecars).

### 9.14 Route chain: walking the flight path (part done)

The route is built as a **chain**, one step at a time, rather than by welding
everything within a tolerance. Welding merges lines that merely pass near each other;
the chain takes exactly one connection per step and keeps going.

**Terms the walk needs:**

- a **line feature** is one polyline of `flight_path.shp`; it has a *start* end and an *end* end
- a **free end** is a feature end not yet used; a **spent** end has been welded into the route
- the **loose end** is the far end of the feature just attached, which is not yet connected
- the **route** is the air-call sequence, and the chain runs over the **A and B calls only**
  - bare-numbered calls sit mid-segment rather than at a junction, so they are not chain
    nodes. That gives **582 route points**, not 873.

**The rule that keeps it honest: each feature end may be used once.** Without it two
stretches of the walk join at the same junction and quietly build a loop.

**The walk**, from the first call:

1. At a route point, attach a free end to continue from.
2. At a loose end, prefer the **next call** in the sequence if it is within the weld
   distance.
3. Otherwise fall back to the closest free end, also within the weld distance.
4. Each newly attached feature supplies the next loose end.

Two bugs were found by running this against the real layer, both of which had produced a
route that was reported as complete but never joined anything:

- `nearestFreeEnd` did not check the weld distance at all, so the walk attached features
  arbitrarily far away and never needed to fall back.
- When the walk lost its loose end it searched near the *next route point*, which let it
  **teleport** between disconnected parts of the path. The fallback belongs at the loose
  end, so an exhausted chain now stops and says where it ended.

**Choosing between candidates is the part that needed a decision.** Measured at `001B`,
seven feature ends lie within 200 m, and the closest is not the flight line:

```
#33 end      2.6 m  feature    35 m   <- nearest, but a stub
#32 end     20.2 m  feature   184 m
#33 start   37.3 m  feature    35 m
#31 end    179.6 m  feature  1589 m   <- the flight line, cos +0.97 forward
#32 start  188.4 m  feature   184 m
```

Nearest-first therefore stalls immediately. `EndPreference` now offers both, so the choice
is made on measurements:

```
NEAREST        3 features chained,  2 of 582 calls reached
CONTINUE_LINE 12 features chained, 10 of 582 calls reached
```

`CONTINUE_LINE` scores a candidate by how nearly its feature carries the flight on: at a
loose end by heading alignment, and at a route point - where there is no heading - by how
far the candidate's **far end** leads toward the next call. That is what "connect `001A`
through the short segment to `001B`" actually means; nearest-first takes whatever stub is
in range. The trace matches the described workflow:

```
route point 001A (22 m)
route point 001B (20 m)
line #31 end (180 m)      the flight line
route point 002A (189 m)
line #0 start (20 m)
route point 002B (21 m)
line #6 end (136 m)
route point 003A (84 m)
```

### 9.15 The route lines: drop the debris, synthesise the A/B connectors

Measured on the real layer, **`flight_path` is mostly debris**: 582 of its 1195 features
are 50 m or shorter, and there are exactly 582 air-call legs inside the flight-line
triples, so those short features are the `A->bare` and `bare->B` stubs. They are what a
nearest-end walk kept tripping over. `FlightRouteLines` therefore:

1. **drops features shorter than 200 m**, leaving 421 - the flight lines proper, and
2. **synthesises one connector per A/B pair** (`001A` to `001B`, `138A` to `138B`), kept
   only if within the connector limit.

The two limits are separate on purpose. The first removes debris; the second is a
statement about the flight itself.

**The connector limit is 350 m, raised from 200 m.** At 200 m, 105 of the 291 pairs were
refused - triple `006` among them, its A and B calls 223 m apart - leaving the chain with
nothing to attach at that triple. The longest pair in the whole layer is 328 m, so 350 m
accepts every one and nothing is reported.

This also **removed the need to choose a strategy**: with the debris gone, `NEAREST` and
`CONTINUE_LINE` chain the same features, with zero fallbacks. The stub tangle that made
the choice matter no longer exists.

### 9.16 Why the walk cannot reach the end: the path does not go there

This is the finding that matters, and it is a property of the data rather than of the
algorithm.

Between one triple's B call and the next triple's A call the aircraft repositions - a
median of **2396 m**. Sampling each of those 290 transits and measuring the distance from
the straight line to the nearest `flight_path` geometry:

```
transits with no geometry within  50 m: 270 of 290  (93%)
                     within 200 m:  86 of 290  (30%)
                     within 350 m:  55 of 290  (19%)
                     within 800 m:  18 of 290  (6%)
worst: 031B->032A 1325 m, 113B->114A 1225 m, 161B->162A 1201 m, ...
```

**`flight_path` does not record the transit between flight lines.** It holds the working
lines only, so for most triples there is simply nothing to walk between the end of one and
the start of the next - and for 18 of them the nearest geometry is more than 800 m away.

No welding distance can fix that, and the sweep proves it: every combination of feature
threshold, connector limit and weld distance stalls at `006A` or `003B`, and widening the
limits makes it **worse** (10 calls reached at a 200 m weld, 5 at 250 m) because the wider
weld attaches a 4990 m feature belonging to a different flight line.

Raising the weld just above the first gap does gain a little - 215 to 220 m reaches 12
calls instead of 10 - but that is two more calls out of 582, not progress.

**So the route has to be several routes, not one**, split where the flight path genuinely
stops. This is what the user meant by "one or more continuous navigable routes", and it is
now measurable rather than assumed: a split is needed wherever the transit between two
triples exceeds the weld distance, which is 86 of 290 at 200 m.

Two ways forward, and this is a question for the user:

- **Split the route** at every genuine gap, giving a handful of routes each covering the
  triples the path actually connects. Nothing is invented and nothing is misreported.
- **Add the transit legs to `flight_path`**, if they were flown and simply not exported.
  That would make one continuous route possible.

### 9.17 The flight plan (done in core)

What is being built is a **flight plan**, not a path to be followed blindly, and the two
are not the same thing.

- The short `xxxA -> xxxB` leg, a median of **186 m**, is an **observation transect**. The
  helicopter flies it slowly and commonly circles it two or three times to gather what it
  needs. The very short features around each end only mark where the transect begins and
  ends.
- The leg from one triple's B call to the next triple's A call, a median of **2396 m**, is
  **repositioning**.

The circles are exactly why the plan has to be drawn. A pilot and passengers who have just
circled a transect two or three times have lost their bearings, and seeing the plan with
the next point highlighted is what orients them. So `FlightPlan` builds the whole ordered
sequence - every transect and every transit - from the air calls alone, and marks each leg
with whether flight-path geometry actually runs alongside it.

Measured on the real layer:

```
291 transects, 290 transits, 171 transits with no path geometry
transect length: p50 186 m  max 328 m
transit length:  p50 2396 m  max 8747 m
transects following the path: 291 of 291     <- every one is on the path
transits following the path:  119 of 290     <- most repositioning is not recorded
transit gap to path: p50 119 m  p90 597 m  max 1325 m
worst leg: 031B->032A TRANSIT 4368 m (no path geometry)
```

This resolves the question the chain walk could not. **Every one of the 291 transects lies
on the flight path**, so all the day's actual flying can be drawn from recorded geometry.
Most transits are not recorded, and drawing them as plain lines is honest rather than a
failure - the alternative is omitting them, which would hide a leg of the day's work.

The gap is measured per leg as the largest distance from the straight line to the nearest
path geometry, sampled along its length, so "no path geometry" is a measurement and not an
assumption.

### 9.19 FLIGHT PLAN: the plan on the map, flown from a chosen start

`C5` is **FLIGHT PLAN** in `on_air`, not a network. Pressing it draws the plan and then asks
where to start:

1. A **picker of air calls** offers the transect starts in flying order. A picker rather than
   assuming `001A`, because the crew may be starting mid-block or resuming after earlier
   work is done.
2. The app flies a **direct line** to the chosen call. `PlanPhase.APPROACHING`.
3. Within 50 m of it, the **plan takes over from that call onward**. `PlanPhase.ON_PLAN`.
4. Each leg is advanced only when the aircraft is actually within tolerance of its end
   point, so a leg is never skipped on a drift.

`FlightPlanSession` holds this and is tested without Android: the approach keeps its target
until reached, arriving at a call that *starts* a leg targets that leg's far end, choosing
the far end of a leg carries on with the leg that leaves it, an unknown call is refused
rather than silently starting from the beginning, and the distance reported is never NaN.

**In the air, `Navigate` now follows the Flight Path.** It previously defaulted to `C5`,
which is no longer a network - the label would have read "Navigating on FLIGHT PLAN" and
pointed at a button that opens a picker rather than a route to follow.

Drawn on the map: transects solid, transits dashed, calls labelled, the current target
picked out in red with a banner giving the next call, its distance and how many calls are
left. Labels **declutter by collision**, because at 1:313845 the A and B calls of a
transect sit almost on top of each other and overprinted into nonsense like `148AB`; the
next transect's two calls are always labelled since that is what the crew is looking for.

Verified on the emulator against the real layer: picker lists `001A`, `002A`, ...; choosing
`002A` shows "Direct line to 002A"; on reaching it the banner becomes "On plan: next 002B
231 m 579 calls left".

*Known issue:* the GPS fix used for the "next call" choice is not always the one shown on
the readout - two fixes 1300 km apart were seen inside one run. Until that is tracked down,
the "next" call can be chosen against a stale position.

### 9.18 Where the walk stands

### 9.13 Navigation_mode and the navigation commands (done)

`Navigation_mode` in Settings is `on_ground` or `on_air`, and it is the one setting the
main page depends on, because it decides what two of the buttons say. It is remembered
between launches, since a crew should not have to re-set it every morning.

**The grid now lives in `MainGrid`, not in the Activity.** The twenty-four sheet commands
are declared once with their ODS cell (`A1`...`D6`) plus a ground and an air label, and
only `C4` and `C5` differ. Switching mode is then one lookup rather than a second
hand-written list that could drift out of step with the first, and the layout is checked
against the sheet by test: six rows of four, the right label in the right cell, and
exactly two buttons changing with the mode.

`NavigationSession` holds what the crew has asked for, free of Android types so the rules
are testable:

- `Navigate` starts on the mode's default: **ACCESS** on the ground, **ROUTE** in the air.
- A waypoint chosen with `Waypoint` is kept, so choosing one and then pressing Navigate
  heads for it.
- Starting twice is refused rather than silently restarting, which would lose the crew's
  place.
- The three network buttons choose the network both before and during navigation, so
  tapping ROAD then Navigate follows ROAD.
- `Direct_Line` is refused before navigation starts, rather than setting a flag that
  appears to do nothing.
- `Stop_Navigating` asks the caller to put the map back on the GPS position with course
  up, as the main page specifies.
- Changing mode **stops** navigation: a ROUTE and a road network are not interchangeable,
  and carrying one into the other would be misleading.

Verified on the emulator: the grid renders the `on_ground` layout with Navigate,
Stop_Navigating, Direct_Line, Waypoint and the three network buttons live; switching to
`on_air` relabels `C4` to **Flight Path** and `C5` to **ROUTE**; Navigate then reports
"Navigating on ROUTE."; and the mode survives a force-stop and restart.

`Waypoint` offers the values of the waypoints layer named in Settings, and says so
plainly when that layer is not loaded rather than opening an empty list.

### 9.12 ROUTE: the air-call succession (done)

`C5 = TRAIL` becomes **ROUTE** when `Navigation_mode = on_air`. ROUTE sequences the air
calls so the app always knows the next one to fly to.

**The order comes from the call names.** The convention is

```
101A, 101, 101B, 102A, 102, 102B, 103A, 103, 103B, 104A, ...
```

which needs two sort keys, not one:

- the **number**, which must be parsed rather than sorted as text. Sorted as strings,
  `1, 10, 100, 101...` scrambles the whole flight.
- the **suffix rank**, because the bare name sits *between* A and B. Text sorting gives
  `101, 101A, 101B`, which is wrong. The rank is explicitly A, then bare, then B; any
  other letter sorts after those alphabetically, so an unexpected suffix still gives a
  stable sequence rather than scrambling it.

**Flights are separated by distance, not by the welded graph.** A new flight starts when
consecutive calls are more than 20 km apart. Measured on the real layer the gaps are
bimodal: 2-6 km as the aircraft repositions to begin another line, then two jumps of
42.7 km and 57.7 km between genuinely separate flights. 20 km sits in the empty gap
between those populations, so it does not rest on a knife edge.

An earlier version split flights by connected component of the welded path. That was
wrong and was removed: the path geometry is only metres accurate in places and fragments
readily, so letting it decide the sequence would invent breaks that are not there. The
geometry supplies leg lines; the names supply the order.

**`Air_Calls_YR_4` is the layer that carries `CALL_ID`.** The older `air_calls.dbf` has
no name field at all - `OBJECTID` runs 1..291 and `F001A` is a cycling label repeating
every 216 values - so it cannot express the A/bare/B convention. `Air_Calls_YR_4` has
873 records, `CALL_ID` unique, exactly 291 numbers with an A, a bare and a B call each,
which is the convention above.

**File order in that layer is not the flying order**, which is the argument for deriving
the order rather than reading it. It runs `001-195`, then `217B` on its own, then
`218-291`, then `196-217`, leaving number 217's three calls split with `217B` stranded
66 records early. Sorting by the rule restores `217A, 217, 217B`.

Measured gaps: `A -> bare` median 94 m (max 166 m), `bare -> B` median 95 m (max 175 m),
and `B -> next A` median 2387 m (max 8747 m). So within a flight-line triple the calls
are about 95 m apart, comfortably inside the weld, while the aircraft repositions 2.4-8.7
km between triples. All 873 calls lie within **11.4 m** of the raw unwelded track.

**The flight path is welded at 150 m**, via `NetworkBuilder.forFlightPath()`,
for drawing the leg between two calls. This is a separate tolerance from the road
network's 5 m cap and gets its own justification rather than reusing
`allowSupervisedGaps`: a flight path is a GPS track whose successive fixes scatter by
tens of metres, so a 30 m weld reconnects ground the aircraft demonstrably crossed. The
road case is the opposite - a wide weld there could build a road over a collapsed
bridge - so the road limit is unchanged and a plain `NetworkBuilder(30.0)` still refuses.

**A wide weld costs geometric fidelity, and that cost is now measured and reported.**

Welding merges two points into one node, and the node has to sit somewhere between them.
Each edge is then a chord between welded nodes rather than the true track. At the 5 m
road tolerance this is invisible. At 150 m it is not: the worst node moves **110.7 m**
from the points it replaced, and calls measure up to **140.5 m** from the welded
network, even though every one of them is within 11.4 m of the real track.

Two bugs were found here by comparing against the raw unwelded geometry, and both are
fixed:

- A welded node sat wherever the *first* of its points happened to be, so it inherited
  the full tolerance as displacement rather than half of it. Nodes now sit at the centre
  of the points folded into them.
- Clusters **chained**: A welds B, B welds C, so a "150 m" weld reached 300 m spans and
  displaced nodes by 170 m. Joining now requires the new point to be within tolerance of
  where the node currently sits, which bounds the spread.

`BuildReport.nodeDisplacementM` reports the cost of whichever tolerance was used, so a
wide weld can never quietly move the track.

**Measured on the real data:**

```
path features=1195 vertices=2671, calls=873 from Air_Calls_YR_4
weld=30 m  -> regions=572   unplaced=0
weld=150 m -> regions=314   unplaced=0   path length 792.5 km
weld=300 m -> regions=147   unplaced=21
worst call offset=140.5 m   worst node displacement=110.7 m
flights=1   001A..291B, 873 calls
resume 101A -> 101, and 101 -> 101B
within-triple legs=582, of which 16 lie beyond the 150 m weld
```

At 150 m the path resolves into 314 pieces rather than 572, and no calls are lost. 300 m
starts losing calls, so it is not a safe direction to push. All calls sequence into a
single flight: the largest repositioning between calls is 8.7 km, well under the 20 km
flight-break threshold.

**16 of the 582 within-triple legs are longer than the weld distance** (up to 175 m), so
those legs have no welded path to draw and must fall back to a direct line. Widening the
weld fixes connectivity but costs geometry, so this is a genuine trade rather than a
setting with a right answer - see question 16.

*Not yet built:* the ROUTE UI, and drawing legs along the welded path.

### 9.11 Verify_Layers (done)

A **Verify layers** button runs a read-only health check over the project folder and
shows the result in a scrollable dialog, in the same shape Forest Point Collector uses
so the two apps read alike:

```
access.shp [line, UTM 10N] OK
roads.shp  [line, UTM 10N] OK
tracks.shp [line, WGS84] WARN - no features

18 layers: 17 OK, 1 warnings, 0 skipped.
16 layer(s) UTM 10N; 2 layer(s) WGS84
16 layers in UTM 10N cover 170.7 x 242.3 km (333207,5451730 to 503862,5694026 m).
  1 outlier(s): xgv_mp 0.98% of area.  2 further layer(s) in WGS84, extent not compared.
```

Per layer it checks sidecars (`.shx`, `.dbf`, `.prj` individually), header
readability, geometry type, CRS recognition, zone, and whether the layer has any
features at all. Across the project it reports which coordinate systems are in play and
whether the layers cover roughly the same ground, flagging a layer that is a tiny
fraction of the project area as a likely stray extract.

It runs on a background thread because a block with tens of thousands of features can
take a while, and a frozen UI is worse in the field than a slow report.

**Extents are only compared within one coordinate system.** Unioning geographic layers
(degrees) with projected ones (metres) produced a nonsense 504 × 5694 km extent and
17 false outliers; the first version of this code did exactly that, and running it
against the real folder is what caught it.

Verified against the real `FOR_NAV` folder: 18 layers, 17 OK, and it correctly caught
that `tracks.shp` is empty.

### 9.9 Create network from the app (done)

A **Make network** button builds a navigable network from every visible line layer and
writes it into the project folder as `network.shp/.shx/.dbf/.prj`, then reports what
it produced in the overlay: layers, lines, nodes, segments, regions, endpoints welded,
junctions found, longest connected region in km, files written, and elapsed time.

Travel class is inferred from the layer name (`bike` → BIKE, `trail` → TRAIL, `road` →
ROAD, `access`/`track` → ACCESS) using the agreed defaults, including trail at 3 km/h.

The report is deliberately as important as the layer: a network that welded across a
gap or fragmented the block has to be *visible*, not trusted. This is the app-side
half of `Verify_Layers`; a dedicated screen with per-region browsing is still to come.

### 9.10 Main page rebuilt as the specified 4x6 grid (done)

An interim single horizontal row was built and did not scale: by eight buttons
"Follow: on" and "Up: course" were wrapping onto two lines. `forest_navigator_ODS.ods`
in fact specifies **four across and six rows down — 24 commands**, and that grid is
now what the app renders, in the sheet's own order, with the three later additions on
a seventh row.

Commands that are not built yet are shown **disabled and dimmed rather than hidden**,
so the page matches the design and it is obvious what remains to do. Currently live:
Start map, Close map, Centre me, Pan, Zoom +, Zoom −, Map up, Auto zoom, Network.

Buttons refresh through `refreshCommands()` so the state-dependent labels (Map up,
Auto zoom) always show what is actually happening; verified on the emulator cycling
course → waypoint → north.

Zoom_IN/Zoom_OUT step a conventional scale ladder rather than a fixed factor, so
"half the current scale" behaves sensibly across the whole 1:500 – 1:4,000,000 range.

### 9.1 Not yet built

- Defects 7, 8, 9 in section 5 remain unfixed (settings round-trip, unused
  `mapLoading`, bbox-only hit-testing).
- `Verify_Layers` has no dedicated screen yet. The `BuildReport` numbers are shown in
  the overlay after a build, but per-region browsing and layer-by-layer checks are not
  built.
- The main page is the specified 4x6 grid (9.10). Fifteen of the 24 specified
  commands are still stubs — notably Settings, Layer settings, Navigate, Direct_Line,
  Waypoint, Go to…, ROAD/ACCESS/TRAIL, Record track, editing and Add_Point/MOB.
- No settings screen; `LayerStyle.Settings` remains dead.
- No raster support.
- `MapCanvasView` wired to `MapTransform` with rotation support; Pan and waypoint
  marker restored; `ZoomScale` extracted and tested. 71 tests total.

---

## 10. Staged build order

Rotation comes first because every later feature depends on the transform, and
retrofitting it means re-testing the whole app.

1. **Wire `MapTransform` into `MapCanvasView`** — bearing, isotropic scale, corrected
   hit-testing. Unblocks all three orientation modes on screen.
2. **Fix defects 3–10**, in particular the two that make existing commands
   non-functional (pan, waypoint marker).
3. **Settings screen** — wire the existing `LayerStyle.Settings` model to UI, plus
   Save Map As, Restore defaults, and the raster preparation entry.
4. ~~**Multi-zone**~~ **DONE** — see 9.8.
5. **Network writer + Verify Layers** — write a built network as a shapefile and show
   the `BuildReport` numbers.
6. **Navigation UI** — road following with off-route detection and re-route;
   multi-waypoint list.
7. **Helicopter mode A** (direct line) and **mode B** (flight path via ordered air
   calls), pending section 7.1.
8. **Raster** — pyramid prep, tile cache, hillshade.
9. **Track-based repair** — deferred; likely desktop.

---

## 11. Open questions

| # | Question | Blocking |
|---|---|---|
| 12 | `xgv_mp` is flagged as an outlier at 0.98% of project area. Is the 1 % threshold right, or is a small "current block" layer legitimate? | Outlier sensitivity |
| 13 | Three networks ROAD/ACCESS/TRAIL only: which source layers feed each by default? | Network builder UI |
| 14 | The real `air_calls.dbf` is named by `OBJECTID` (1..291, contiguous) and has no `101A/101/101B` names; `F001A` is a cycling label repeating every 216 values. Which field holds the call name, or should a name column be added? | ROUTE display |
| 15 | `flight_path` fragments at any weld: 314 pieces at 150 m, 147 at 300 m. Are those fragments real breaks between flown lines, or should the layer be repaired so each flight is continuous? | Leg geometry |
| 17 | All 291 transects lie on the flight path, but only 119 of 290 transits do. Should the undrawn transits be shown as plain lines on the plan, or should the transit legs be added to `flight_path` if they were flown? | Flight plan drawing |
| 16 | At a 150 m weld the worst node moves 110.7 m off the true track and 16 within-triple legs still exceed it. Is a leg drawn up to ~110 m off the GPS track acceptable, or should the weld stay near 30 m and accept 572 fragments? | Leg geometry |
| 1 | Raster prep: is a desktop prep step acceptable, or must the app build the pyramid on device? | Raster design |
| 2 | Better source imagery for road-level detail? 5 m caps out near 1:25,000. | Navigation usability |
| 3 | BIKE speed held at 12 km/h pending information; a DEM may inform speed and class assignment. Others confirmed. | Routing quality |
| 4 | Confirm candidate network sources: `access`, `roads`, `tracks`, singly and combined. `transect` excluded by the user. | Network builder |
| 5 | Moving-speed threshold for heading (proposed 1.0 m/s with hysteresis). | Orientation |
| 6 | Confirm the heading window (proposed ~100 m). | Orientation |
| 7 | ~~Largest region appears to shrink with tolerance.~~ **Resolved** — bad metric, not a bug; see 6.3 findings 2 and 3. | Closed |
| 8 | Should `Verify_Layers` also cross-check CRS, Z values, and mixed geometry types? | Verify Layers scope |
| 9 | Terrain awareness — revisit at field testing. | Deferred by agreement |
| 10 | ~~Scale calibration.~~ **Closed** — the 400–600 m figure was a starting reference, not a requirement. Table stands; per-device adaptation moves to Settings. | Closed |

---

## 12. Change log

- **Initial document.** Consolidates the three ODS specifications, findings from the
  real `FOR_NAV` data, ten defects found in the existing code, architecture decisions,
  and the navigation core as built and tested (58 tests).
- **Trail speed corrected to 3 km/h** at the user's request.
- **Main page rebuilt** as the specified 4x6 grid of 24 commands, ODS order, with
  unimplemented ones visibly disabled.
- **Navigation mode and button relabelling**: `Navigation_mode` in Settings selects
  `on_ground` or `on_air`. The default layout is the current one, which is the
  `on_ground` layout. With `on_air`, `C4` changes from ROAD to **Flight Path** and
  `C5` from TRAIL to **ROUTE**. `B3 Navigate` starts navigation on the ACCESS network,
  `B4 Stop_Navigating` stops it and leaves the map centred on the GPS position with
  course-up orientation, `C3 Direct_Line` switches the active navigation to a direct
  line, and `D4 Waypoint` picks a waypoint from the waypoints layer named in Settings.
- **FlightRoute**: derived air-call succession, tested on both synthetic paths and the
  real `flight_path`/`air_calls` layers. Per-feature ordering works; cross-feature
  ordering needs the path noded into a graph.
- **Verify_Layers** implemented as a read-only report matching the Forest Point
  Collector format, with mixed-CRS and extent-consistency checks. Verified against the
  real folder; running it caught a bug in its own extent arithmetic.
- **Multi-zone support**: any UTM zone recognised, foreign-zone layers reprojected,
  working zone derived from the first layer. 10 new app tests.
- **Create network button**: builds from visible line layers, writes the shapefile, and
  reports the build statistics in the overlay.
- **Network layer writer** built and proven on the real data; 106 tests total.
- **dBASE record-count truncation fixed** — a pre-existing bug also present in
  `ShpPointCap`, which silently lost labels for layers over 65,535 features
  (`delineation.shp` is affected).
- **On-device run**: app installs, launches and renders without crashing. Real layer
  loading still needs a hands-on run; rotation-dependent culling moved into
  testable core code in the meantime.
- **Speed-adaptive scale** implemented from the user's band table with hysteresis,
  dwell and damped stepping; default 1:20,000; Auto zoom toggle. Values treated as
  sensible defaults rather than a calibration; per-device adaptation deferred to
  Settings (open question 10 closed).
- **Panning reworked** for blocks larger than the screen: drag always pans, drag
  suspends following, and panning is clamped to the data extent. Default scale set to
  the 1:1,000,000 block overview.
- **Canvas rewired** to `MapTransform`: rotation, isotropic scale, rotation-aware
  culling and panning, turning north arrow, Pan and waypoint marker restored,
  `MapActivity` driving the orientation. `ZoomScale` extracted for testing. Not yet
  verified on a device.
- **Unsupervised tolerance capped at 5 m** at the user's direction: wider welding can
  invent a road across a collapsed bridge or landslide. The builder now refuses it
  unless the supervised repair flow opts in.
- **Q7 resolved.** The apparent shrinkage of the largest region was a misleading
  metric (node count) rather than a topology fault. `BuildReport` now reports
  `maxRegionEdgeCount` and `maxRegionLengthKm`. A genuine over-welding fault was found
  and documented at tolerances above ~20 m.
