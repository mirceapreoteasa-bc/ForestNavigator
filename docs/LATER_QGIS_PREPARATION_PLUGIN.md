# Later: a QGIS plugin that prepares a FOR_NAV folder

**Status: deliberately deferred until the app works well. Do not start this early.**

## Why it is deferred

The point of the plugin is to *remove the need for app-side workarounds*. Those
workarounds only become known once the app has been used on real folders and its
bottlenecks have actually been observed. Building the plugin first would mean
guessing which limits matter, and encoding the guesses into a tool the user is
then asked to follow — which is how a good-practices manual ends up giving advice
that quietly does not help.

So: finish the app, hit the real limits, and *then* write the plugin from that
evidence.

## What it should become

An actionable good-practices manual, delivered as a QGIS plugin, that walks a user
through preparing a survey folder so the phone app stays fast and free of
hitching. It should:

- choose a project CRS and extent, and create the empty template layers
- reproject and clip every layer to that one CRS
- simplify line work and enforce a node budget
- write rasters as **tiled** GeoTIFFs (see below), and report the result
- audit an existing folder and say, in measured numbers, what is over budget

## Shared limits, one source of truth

The app and the plugin must agree on every number. The advice must never contradict
what the app enforces, so these belong in one place and are quoted by both:

| Limit | Current measured value |
|---|---|
| Access network nodes | `access.shp` builds to **25,298 nodes / 24,624 segments** |
| Suggested node budget | **50,000** nodes per routable layer |
| Vector target CRS | one UTM zone for the whole project (zone 10 for FOR_NAV) |
| Deliberate exceptions | `points` and `tracks` stay WGS84 degrees for point-collector interop |
| Raster layout | tiled, deflated GeoTIFF |
| Raster size | a budget to be set after measurement on real phone hardware |

## What is known about raster size, and what is not

Disk size is **not** the constraint. The decoder reads by tile, so peak memory is
one tile plus one screen-sized bitmap — a 354 MB raster opens fine.

The constraint is **I/O time on re-decode**, and it is layout-dependent:

- `osm4.tif` is uncompressed, 10 m, 11566x7663, **one row per strip** (7,663
  strips). One screen needs ~137 strip reads, and they arrive over a SAF stream.
- Tiling is what makes panning cheap: only the tiles on screen are read. A
  256-pixel RGBA tile is ~256 KB, against ~37 MB for a full-width strip.

Uncompressed also means no decompression CPU, so the two levers trade against
each other: compression shrinks the file, tiling makes it quick to read. Both are
worth having.

**Unmeasured:** decode time and memory on the real BV5200 over an SD card or USB
connection. Every figure above is from the emulator or from the host. Set the
raster budget only after that is measured.

## Raster formats the app understands

- Compression: none (1), Deflate (8, 32946), LZW (5)
- Layout: **striped and internally tiled**
- JPEG-in-TIFF (6, 7, 34712) is **not** decoded. The app names the raster and
  gives the conversion command rather than silently omitting it:

  ```
  gdal_translate -co TILED=YES -co COMPRESS=DEFLATE in.tif out.tif
  ```
