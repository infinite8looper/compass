# Screenshots

Every picture here is a capture from an emulator, added in commits dated from 2026-10-13 to 2026-10-16, from the debug build unless its row names another: `calibrate-release` is from the release build, `check-watch-build` and `check-watch-build-inputs` from the watch build. Nothing was drawn or edited. A picture marked "frames" is several captures joined side by side, first on the left.

- `<state>-large.png` is from `Wear_OS_Large_Round` (API 35, 454 x 454) and `<state>-small.png` from `Wear_OS_Small_Round` (API 35, 384 x 384). They were made with `scripts/screenshot-matrix.sh`, a section at a time (`scripts/screenshot-matrix.sh large trail`); the section named in each row is the argument that makes that row's pictures again. The `settings` pictures are from a later build than the rest, and the `coords` pictures from a later one still (v3 Task 8, 2026-10-15), with the rows standing as `Show coordinates` puts them now.
- The position fed is 43.7022, -72.2896 (`scripts/geo-feed.sh`). Waypoints are put in the app's database with `scripts/seed-targets.sh <bearing>:<place on the color ring>:<meters>`. "Three targets" is `60:137.5:400 200:275:1500 300:52.5:5000`. A heading is set with the emulator's magnetic-field sensor and corrected until the compass shows it.
- The library gives a color scheme only on API 35 and newer, and there only while the system setting `dynamic_color_theme_enabled` is 1 (put to 1 through `adb` for the pictures that show a scheme: no image does it from the watch face; see the `accent-` pictures below); below API 35 it gives none whatever the setting, and `Standard` reads none. So the accent is blue and north and the error color are red in every picture but the `accent-` and `colors-watchface-` ones, which have the API 35 and API 37 images' schemes with the setting put to 1. The pictures taken before the `Colors` setting (all but the `colors-` ones) show the waypoints' colors on the v2 ring of the blue and the red; with `Standard`, the default since then, a waypoint's place is a hue on the full wheel, as the `colors-standard-` pictures show.
- The emulators report only high magnetometer accuracy and a position accuracy of 5 m; what that leaves unseen is listed at the end.

## Compass

| State | File | How it was produced |
|-|-|-|
| Before any position is known: magnetic north, `MAG` | `compass-mag` | `permission`: `pm clear`, start, heading 40 |
| Clean, true north | `compass-clean` | `compass`: targets seeded and a fix received, then `seed-targets.sh none` |
| Three targets, one focused, miles | `compass-targets` | `compass`: three targets, heading 290 |
| Facing the focused target: the heavy line | `compass-facing` | `compass`: heading 300, the bearing of the focused target |
| Magnetic north and meters, facing | `compass-targets-mag-metric` | `compass`: `Feet and miles` and `True north` switched off in settings |
| Eight waypoints 45 apart on the color ring | `compass-ring8` | `compass`: `seed-targets.sh 0:0:900 45:45:900 ... 315:315:900`, heading 20 |
| Thirty targets (retaken in v3 Task 10: the places new waypoints are given since v3 Task 5, none within 25 degrees of north's red or the pointer's blue) | `compass-many` | `compass`: `seed-targets.sh many` |
| `Arrived at target` | `compass-arrived` | `compass`: `seed-targets.sh 0:0:4` (4 m away) |
| `No target` | `compass-no-target` | `compass`: three waypoints, the waypoints header tapped to clear all |
| A distance marked `(old)` | `compass-stale` | `compass`: `settings put secure location_mode 0`, 25 s |
| A target with location off | `compass-location-off` | `compass`: the app stopped and started with location off |
| `Locating…` | `compass-locating` | `compass`: location on again, the app started, 1.2 s |
| The three ticks that show which screen is in view | `compass-pane-ticks` | `panes`: a drag toward waypoints let go early; taken as the row comes to rest |
| Hold-flat, no target (4 frames, 0.45 s apart) | `holdflat-frames` | `moving`: `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 emu sensor set acceleration 0:9.81:0` |
| Hold-flat with a target (4 frames) | `holdflat-target-frames` | `moving`: the same with three targets |
| A long name sliding (4 frames, 1.7 s apart) | `name-sliding-compass` | `moving`: the focused waypoint renamed in the database to a 60-character name |

## Saving from the compass

| State | File | How it was produced |
|-|-|-|
| The system's permission dialog | `permission-dialog` | `permission`: the first double tap on the dial |
| `Needs access to` / `precise location` on the compass | `log-needs-access` | `permission`: `Use precise location` switched off in the dialog, `While using app` |
| The same on the waypoints band | `waypoints-needs-access` | `permission`: `Log here`, then `Keep approximate location` |
| The same under `Show coordinates` | `settings-needs-access` | `permission`: the row tapped with only approximate location granted |
| `Saving…` | `log-saving` | `log`: Home for 12 s, so the position in hand is over 10 s old, the app opened again and a press 0.6 s later, before a new position |
| `Saved Waypoint N` and the accuracy | `log-saved` | `log`: the same press, when the confirming green shows |
| `No GPS fix` / `Try again outdoors` | `log-no-gps-fix` | `log`: Home for 12 s and the app opened again (no position in hand), then 0.6 s later a double tap on the dial (two `input tap`) followed at once by Home, location off while the app is off screen, 23 s, location on, the app opened again (the picture is taken while it opens) |
| `Location is off` | `log-location-off` | `log`: location off, Home and back, a press |
| `Couldn't save` | `log-couldnt-save` | `log`: `run-as ... chmod 440 databases/waypoints.db` with the app stopped, start, a press; the file is put back to 660 |
| The status band on the waypoints screen | `waypoints-saved-band` | `waypoints`: `Log here` tapped |
| `True north` switched on without the permission: the system's dialog | `truenorth-dialog` | `truenorth`: wiped data, settings, `True north` off and tapped on again |
| The switch on after the dialog was declined | `truenorth-declined-settings` | `truenorth`: `Don’t allow` |
| The compass after it: magnetic, `MAG` | `truenorth-declined-compass` | `truenorth`: one swipe back to the compass |

## Coordinates

The `coords` pictures were taken again on 2026-10-15 (v3 Task 8), with the latitude and the longitude together under the row under the heading and the prompt and a status's second line at the top. `python3 -I scripts/readout-gaps.py` gives the gaps between their lines (docs/TESTING.md). The font scale at 1.24 gave the same readout, pixel for pixel in the measured lines (the readout's text sizes are set from the dial's radius).

| State | File | How it was produced |
|-|-|-|
| No target | `coords-clean` | `coords`: `Show coordinates` on, no waypoints, heading 127 |
| Old: `(old)` after the latitude | `coords-clean-old` | `coords`: location off, 25 s |
| Before the first fix, nothing focused: `Locating…` in the longitude's place | `coords-locating` | `coords`: location on again, the app started, 1.2 s |
| The same with the pane ticks under `Locating…` | `coords-locating-ticks` | `coords`: a drag toward waypoints let go early, at once |
| With a focused target | `coords-target` | `coords`: three targets |
| The same with the pane ticks under the longitude | `coords-pane-ticks` | `coords`: a drag toward waypoints let go early |
| `Saved Waypoint 1` under the heading and its accuracy (`±16 ft`) at the top | `coords-saved` | `coords`: a double tap on the dial |
| `Saving…` with no position (the app was off screen for 12 s): `Locating…` in the longitude's place, no second line | `coords-saving` | `coords`: Home, 12 s, the app started, a double tap, 0.9 s |
| `No GPS fix` with no position: `Try again outdoors` right under it, in the latitude's row | `coords-no-gps-fix` | `coords`: a double tap, Home and location off at once, 23 s, location on, the app started |
| `No GPS fix` while an old position shows: `Try again outdoors` at the top, drawn smaller in the narrower slot there, the coordinates with ` (old)` | `coords-no-gps-fix-old` | `coords`: location off 12 s with the app on screen, then on for a double tap and off again, 18 s |
| A target with location off: `Location is off` under the heading, no coordinates | `coords-location-off` | `coords`: location off, the app stopped and started |
| Hold-flat with coordinates on: the two lines left out, `Arrived at target` over `Hold flat` | `coords-holdflat-arrived` | `coords`: `seed-targets.sh 0:0:4`, `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 emu sensor set acceleration 0:9.81:0` |
| The same with `No target` | `coords-holdflat-no-target` | `coords`: three waypoints, the waypoints header tapped to clear all, the same pose |
| `Saved Crumb 1` with its accuracy at the top, over `MAG` and `Trail 1` | `coords-crumb-saved` | `coords`: `True north` off, the trail started from its row, a double tap |
| `MAG` and `Trail 1` over the name, with the distance and the coordinates | `coords-trail-mag` | `coords`: the same, after the status |
| `Hold flat` over `MAG` and `Trail 1` | `coords-holdflat-mag` | `coords`: the same, `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 emu sensor set acceleration 0:9.81:0` |
| `Needs access to` under the heading and `precise location` right under it, in the latitude's row (no coordinate row shows without the permission) | `coords-needs-access` | `coords`: approximate location granted and the precise permission revoked with `pm`, the app started, a double tap answered `Keep approximate location` |

## Breadcrumb trail

| State | File | How it was produced |
|-|-|-|
| `Saved Crumb 1` | `trail-crumb-saved` | `trail`: `Breadcrumb trail` on, the `Trail` row tapped on the waypoints screen, a double tap on the dial |
| Laying, `Trail 3`, a crumb tick near 180 | `trail-laying` | `trail`: crumbs dropped at 43.7022, 43.70274 and 43.70328 north, then the feed moved to 43.7042 |
| The trail's row while laying, the `Drop crumb` row coming in under it | `trail-row-laying` | by hand, not by the script: `Breadcrumb trail` on, three crumbs put in the database, the `Trail` row tapped |
| The `Drop crumb` row | `trail-drop-crumb` | the same, the list scrolled up by one row |
| `Saved Crumb 4` after a tap on `Drop crumb` | `trail-drop-crumb-saved` | the same: `Drop crumb` tapped, taken 1.6 s later |
| Retracing, `Crumb 3` | `trail-retracing` | `trail`: the `Trail` row tapped again, heading 160 |
| Facing the crumb | `trail-facing` | `trail`: heading 180 |
| The trail's row while retracing | `trail-row-retracing` | `trail`: the waypoints screen |
| `Hold to clear`, the ring draining | `trail-hold-to-clear` | `trail`: a touch held on the row for 1.3 s, taken at 1.25 s, let go before it clears |
| `Crumb 1` | `trail-crumb-1` | `trail`: the feed moved onto crumb 3, then onto crumb 2 |
| `Trail complete` | `trail-complete` | `trail`: the feed moved onto crumb 1 |

## Waypoints

| State | File | How it was produced |
|-|-|-|
| No waypoints | `waypoints-empty` | `waypoints`: after `pm clear` |
| A few, one not a target, long names | `waypoints-few` | `waypoints`: three waypoints renamed, one row tapped |
| Thirty, top of the list (`waypoints-many-large` retaken in v3 Task 10, as `compass-many`; `waypoints-many-small` is the picture of 2026-10-14, commit 370d544: it shows only the top of the list, where no waypoint's color shows) | `waypoints-many` | `waypoints`: `seed-targets.sh many` |
| Thirty, end of the list (retaken in v3 Task 10: `Waypoint 1` green, `Waypoint 2` magenta) | `waypoints-many-end` | `waypoints`: twelve swipes up |
| Old distances | `waypoints-old` | `waypoints`: location off, 25 s |
| A long name sliding (3 frames, 2.2 s apart) | `name-sliding-list` | `moving` |

## Edit

| State | File | How it was produced |
|-|-|-|
| A short name | `edit-short` | `edit`: a long press on the row `Camp` |
| The color being dragged, finger down | `edit-recolor` | `edit`: `input motionevent DOWN` on the ring near 4 o'clock, `MOVE` in steps to past 1 o'clock |
| `Hold to delete` late in the hold, about a quarter of the ring left | `edit-hold` | `edit`: a touch held for 0.8 s of the one second, taken about 0.75 s in, let go before it deletes |
| A long name sliding (3 frames) | `name-sliding-edit` | `moving` |

## Settings and calibration

| State | File | How it was produced |
|-|-|-|
| Top of the list | `settings-top` | `settings`: after `pm clear` (the defaults in the United States locale) |
| Middle | `settings-middle` | `settings`: scrolled to `Blind navigation` |
| End (retaken in v3 Task 10; on the small round the script's search for the row now takes up to 24 steps) | `settings-end` | `settings`: scrolled to `Calibrate` |
| `Breadcrumb trail` and `Show coordinates` on | `settings-trail-coords-on` | `settings`: `Show coordinates` tapped (location already granted; the trail is on by default), scrolled to `Breadcrumb trail` |
| Every switch off (3 frames: top, middle, end) | `settings-all-off` | `settings`: each row tapped, and `Turn off` answered to the backup's question |
| Calibration: seven segments of the ring lit, `Covered 29%`, and the `Check` row at the bottom (2026-10-15, v3 Tasks 6 and 7; retaken in Task 7 fix round 1 with the ring at 0.21 of the radius) | `calibrate` | `calibration`: `Calibrate` tapped at heading 40, then the fields `0:20:-45 -20:0:-45 0:-20:-45 20:0:-45 45:20:0 0:20:45` set through the console |
| Interference (2026-10-15, retaken in v3 Task 6 fix round 1 and, with the larger ring, in Task 7 fix round 1) | `calibrate-interference` | `calibration`: then `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 emu sensor set magnetic-field 46:46:-65` (92 microtesla, through the back toward the upper right): that segment lights too, 33%, with the warning and the red frame |
| The release build's calibration: no `Check` row, the ring at 0.21 of the radius (2026-10-15, v3 Task 7 fix round 2) | `calibrate-release` (small only) | by hand: `./gradlew assembleRelease`, the unsigned APK aligned and signed with this machine's debug key in a scratch folder (`zipalign`, `apksigner`), installed in place of the debug build, `Calibrate` tapped; the debug build installed again after |
| The check screen, its top: `Heading source` and `Fused heading` (2026-10-15, v3 Task 7; retaken in fix round 1 with the row's new label, with `check-inputs` and `check-vibrations`) | `check-top` | `check`: `Check` tapped on calibration at heading 40 |
| The check screen in the watch build (minified, not debuggable): its top, and `Wear SDK reached` `Yes`, `One-handed gestures` `No` (2026-10-15, v3 Task 7 fix round 1) | `check-watch-build`, `check-watch-build-inputs` (small only) | by hand: `./gradlew assembleWatch`, `app-watch.apk` installed in place of the debug build, `Calibrate` and `Check` tapped, scrolled; the debug build installed again after |
| The check screen at `Watch colors`: the scheme's colors with their dots (large: `dynamic_color_theme_enabled` 1, the API 35 image's `#D9E2FF` primary), or `None` | `check-watch-colors`, `check-watch-colors-none` (large) | `check`: scrolled; the large one with the setting put to 1 with `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell settings put global` and deleted after, the `-none` one and the small one without it |
| The check screen at `Inputs` | `check-inputs` | `check`: scrolled |
| The check screen at `Vibrations` | `check-vibrations` | `check`: scrolled |
| The check screen's end: the `Play` rows, each saying `Primitives` | `check-end` | `check`: twelve swipes up |
| The question before backups are turned off | `backup-off-question` | by hand: `Back up waypoints` tapped while it is on |

## The largest font size

The system's font scale set to 1.24, the largest step of the Wear OS font size setting (`~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell settings put system font_scale 1.24`), by hand and not by the script. Three waypoints, one named `Trailhead parking lot north`, three crumbs, `Breadcrumb trail` on and a trail being laid.

| State | File | How it was produced |
|-|-|-|
| The compass at the default font scale | `font-default-compass` | font scale 1.0, heading 40 |
| The compass at 1.24: the readout is the same | `font-largest-compass` | font scale 1.24, the same state |
| Settings, top of the list | `font-largest-settings-top` | one Back from the compass |
| Settings, further down: lines that take a second line | `font-largest-settings-middle` | scrolled |
| Settings, further down still | `font-largest-settings-end` | scrolled |
| Waypoints with the `Drop crumb` row | `font-largest-waypoints` | a second Back, the list scrolled up by one row |
| Edit, a name on two lines | `font-largest-edit` | a long press on `Trailhead parking lot north` |
| Calibration, with the `Check` row (2026-10-15, v3 Tasks 6 and 7; retaken in Task 7 fix round 1 with the ring at 0.21 of the radius) | `font-largest-calibration` | `scripts/screenshot-matrix.sh <size> calibration` with the font scale at 1.24, into a scratch folder (`MATRIX_OUT`), and copied |
| Calibration with interference: the warning, drawn smaller to hold its three lines, clear of the frame and above `Check` (2026-10-15, retaken in v3 Task 7 fix round 1) | `font-largest-calibration-interference` | the same run: `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 emu sensor set magnetic-field 46:46:-65` |
| The check screen, its top and its end (2026-10-15, v3 Task 7) | `font-largest-check-top`, `font-largest-check-end` (small only) | `scripts/screenshot-matrix.sh small calibration check` with the font scale at 1.24, into a scratch folder (`MATRIX_OUT`), and copied |
| The question before backups are turned off | `font-largest-backup-off-question` | `Back up waypoints` tapped |

## The three screens

| State | File | How it was produced |
|-|-|-|
| Settings, compass, waypoints (3 frames) | `panes` | `panes`: three targets, heading 20 |
| The row part way through a swipe, finger down | `pane-swipe` | `panes`: `input motionevent DOWN` at 80 percent of the width, `MOVE` to 45 percent |

## Other emulators

| State | File | How it was produced |
|-|-|-|
| Compass on API 33 | `compass-api33.png` | `Wear_OS_API33`: three targets, heading 290 |
| Compass on API 34 | `compass-api34.png` | `Wear_OS_API34`: the same |
| A color scheme, seed `2F8BFF`, eight waypoints 45 apart on the ring | `accent-a-large.png` | `Wear_OS_Newest` (API 37): the `compass-ring8` scene, heading 20; see below |
| A color scheme, seed `00AA55`, the same scene | `accent-b-large.png` | `Wear_OS_Newest`: see below |
| The edit screen's ring under that scheme | `accent-b-edit-large.png` | `Wear_OS_Newest`: a long press on the row `Waypoint 8` (place 315) |

The `accent-` pictures need the system's color scheme switched on through `adb`, since no image does it from the watch face:

```
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell settings put global dynamic_color_theme_enabled 1
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell settings put secure theme_customization_overlay_packages '{"android.theme.customization.system_palette":"00AA55","android.theme.customization.accent_color":"00AA55","android.theme.customization.color_source":"preset","android.theme.customization.theme_style":"TONAL_SPOT"}'
```

The app follows when it next comes back on screen (Home, then open it again). `settings delete global dynamic_color_theme_enabled` switches the scheme off; this image also reads the setting as unset after every boot.

## Colors

The `colors-standard-` and `colors-watchface-` pictures of the large and small watch were taken again on 2026-10-14, after the accent's least saturation became 0.30, the ring took a third anchor apart from the accent and north, and new waypoints began at 108 degrees: eight targets seeded with `scripts/seed-targets.sh 65:245.8 110:23.3 155:160.8 200:298.4 245:75.9 290:213.4 335:350.9 20:108.3`, the places the app gives its first eight waypoints, with the first one's (108.3) given last so that it is the newest, `Waypoint 8`, and has the focus. The `colors-watchface-` large and small pictures were taken once more on 2026-10-14, after `Watch face` began reading the ring turned so that the first place (108.3) is its third anchor. Each has four frames: the compass, the settings at the `Colors` row, the waypoints list and the edit screen of `Waypoint 8`, opened with a long press.

The others were taken earlier on 2026-10-14, before those three changes, from the debug build, by hand (adb, `scripts/swipe.sh`, `scripts/tap-text.sh`): eight targets seeded with `scripts/seed-targets.sh 20:0 65:45 110:90 155:135 200:180 245:225 290:270 335:315` (each 45 degrees of place apart, 20 degrees of bearing off it), the four frames with the edit screen of `Waypoint 5` (place 180). In those the `Watch face` accent is the one deepened only to 0.15 (`#D8E1FF` on API 35) or not deepened (`#B7CFFF` with the seed `2F8BFF`), and the ring's third anchor is the error color.

In all of them the position was fed and the watch laid flat at a magnetic heading of 40; `dynamic_color_theme_enabled` was put to 1 or 0 with `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell settings put global`; the API 37 image had the seed `2F8BFF` written as in "Other emulators".

| State | File | How it was produced |
|-|-|-|
| `Standard` (4 frames) | `colors-standard-large`, `colors-standard-small` | API 35, the setting at 1: the colors do not depend on it. The first waypoint (`Waypoint 8`) is the wheel's yellow-green/green `#7AFF59` (hue 108), away from north's red and the pointer's blue. Seeded with the numbering's first eight places, three of which (23.3, 213.4, 350.9) new waypoints are no longer given (see the next row) |
| `Standard`, the places new waypoints are given (5 frames) | `colors-standard-places-large`, `colors-standard-places-small` | API 35, the setting unset, heading 0 set (the compass had settled at 346): eight targets seeded with `scripts/seed-targets.sh 65:298.36 110:180.91 155:43.40 200:245.83 245:75.86 290:148.45 335:265.90 20:108.32`, the places the app gives a new install's first eight waypoints since fix round 2 (none within 25 degrees of north's red or the pointer's blue), the first (108.3) given last so that it is `Waypoint 8` and has the focus. The compass, the waypoints list in three frames (all eight on the large watch; `Waypoint 3` falls between frames on the small one) and the edit screen of `Waypoint 8`, opened with a long press. The nearest to the pointer's blue is `Waypoint 4` (245.8), a violet; the nearest to north's red is `Waypoint 3` (43.4), an orange |
| `Watch face`, the API 35 image's scheme (4 frames) | `colors-watchface-large`, `colors-watchface-small` | API 35, the setting at 1, the row tapped to `Watch face`: the pale primary deepened to 0.30 (`#B2C4FF`); the ring of the accent, north (`#FD7EF3`) and a made third anchor (`#D4FF98`), since the error color `#F2B8B5` is 58 degrees of hue from north. The first waypoint (`Waypoint 8`, place 108.3) is the third anchor's yellow-green, not north's pink; on the edit ring north is near 12 o'clock (348.3) and the accent at 228.3 |
| `Watch face`, seed `2F8BFF` (4 frames) | `colors-watchface-api37-large` | `Wear_OS_Newest` (API 37), the setting at 1 (taken before the three changes above) |
| The top of the settings, `Standard` and `Watch face` (2 frames) | `colors-settings-top-large`, `colors-settings-top-small` | API 35: the fades at the list's ends and at the rows' ticks, over the black field, in both modes (taken before the three changes above) |
| No scheme: the compass with `Watch face` stored, and the row (2 frames) | `colors-not-available-large`, `colors-not-available-small`, `colors-not-available-api37-large` | API 35 and 37, the setting put to 0 with `Watch face` stored |

## Watch-test fix round 1 (2026-10-16)

The uncertain pointer: taken by `UncertainPointerTest` itself (`/data/local/tmp/uncertain-<name>.png`, pulled after `scripts/quick-tests.sh UncertainPointerTest` on `Wear_OS_Large_Round` and `Wear_OS_Small_Round`), the real readout and dial composed in the real activity from the screen state `buildTrail` and `buildTargets` make for the accuracy named, facing true north: the emulators give no position less accurate than 5 m. The gray scheme: two emulators made for it from the API 37 image (`wearos_large_round`, `wearos_small_round`) and deleted after, with `dynamic_color_theme_enabled` 1 and `theme_customization_overlay_packages` set to the owner's watch's (`system_palette` `DCD7CA`, `accent_color` `FF3E32`, `color_source` `home_wallpaper`, `theme_style` `CONTENT`), which gave the app the owner's scheme (`colors-watchface-gray-check-small`: tertiary `#FFC0B7`, error `#FFBFB0`, `onSurface` `#EDE8DA`); eight waypoints with the first eight places a new install gives (`seed-targets.sh 20:108.3 65:298.4 110:180.9 155:43.4 200:245.8 245:75.9 290:148.5 335:265.9`), heading 0, the position fed, and `Colors` tapped to `Watch face`; the settings put back afterwards and read back (unset). The compass, settings and waypoints pictures were taken again the same way in watch-test fix round 2 (2026-10-16), on two such emulators made again and deleted after; the check screen there showed the same scheme, and its picture is the one from round 1.

| State | File | How it was produced |
|-|-|-|
| A crumb 10 ft away with a 50 ft fix: no line, `10 ft ±50 ft` | `uncertain-crumb-near-large`, `uncertain-crumb-near-small` | `UncertainPointerTest`; the tick at 270 is the trail's other crumb, 400 m west |
| The same fix, the crumb 328 ft away: its line, `328 ft ±50 ft` | `uncertain-crumb-far-large`, `uncertain-crumb-far-small` | `UncertainPointerTest` |
| A waypoint 49 ft away with a 98 ft fix: no line, `49 ft ±98 ft` | `uncertain-waypoint-near-large`, `uncertain-waypoint-near-small` | `UncertainPointerTest` |
| The same fix, the waypoint 328 ft away: its line, `328 ft` | `uncertain-waypoint-far-large`, `uncertain-waypoint-far-small` | `UncertainPointerTest` |
| `Watch face` with the owner's gray scheme: the compass | `colors-watchface-gray-compass-large`, `colors-watchface-gray-compass-small` | the pointer `#FFC4B2`, north `#FF4D33` (the tick 14 degrees right of 0: the dial is true north; `#FF7865` until watch-test fix round 2), the eight waypoints in grays and gray-reds, the focused one's arrow (Waypoint 8, `#FF9F8A`) beside the pointer's |
| The settings, `Colors` at `Watch face` | `colors-watchface-gray-settings-large`, `colors-watchface-gray-settings-small` | the value and the switch ticks in the pointer's gray-red, the ink warm white |
| The waypoints list | `colors-watchface-gray-waypoints-large`, `colors-watchface-gray-waypoints-small` | two swipes up from the top; each row's tick in its waypoint's color |
| The check screen's `Watch colors` | `colors-watchface-gray-check-small` | settings, `Calibrate`, `Check`, scrolled to the scheme |

## Icon

Taken on 2026-10-16 (v3 Task 9) from the debug build, by hand: Home pressed twice from the watch face opens the launcher; the start was recorded with `scripts/record.sh` while `am start` opened the app, and one frame kept. The `before` pictures are of commit 87c6664's build.

| State | File | How it was produced |
|-|-|-|
| The launcher, the icon beside the system's, before: the mark on a black disc | `launcher-before-large`, `launcher-before-small` | API 35 |
| The launcher, after: the disc `#153D70` filling the mask, the ring at the edge of the 66 dp safe zone | `launcher-after-large`, `launcher-after-small` | API 35 (`launcher-after-large` retaken in v3 Task 10 with commit 34fe5b5's build: the picture of Task 9 under that name showed the watch face, not the launcher) |
| The start screen, after: the icon with its disc on black | `start-icon-large`, `start-icon-small` | API 35, a frame of the recording about 2 s after `am start` |
| The start screen, before: the mark alone on black | `start-icon-before-small` | API 35, the same, commit 87c6664's build |

## Not shown, because an emulator cannot produce it

- The `Calibrate` prompt on the compass, and the calibration frame at less than full (accuracy below high).
- The accuracy note after a crumb's distance (`9 m ±20 m`) and `Saving…` with an accuracy that is not yet good enough: the emulator's position accuracy is always 5 m.
- A color scheme that follows the watch face: the `Watch face` pictures have a scheme the system made from the API 35 image's fixed colors or from a seed written with `adb`.
- Anything felt: the tick tap, the two blind navigation pulses, the confirming tap.
- The side button. The emulator images describe no button (`WearableButtons`), so the app handles no side key on them; the settings row that speaks of a double press was not pictured.
- The double pinch (the one-handed primary gesture).
- Real GPS: the emulators give the fed position with no jitter, so arrival at the 10 m and 12 m distances and positions logged while moving were not seen as a watch gives them.
- The encryption requirement on Google's backup: only the emulator's local backup transport was used, with a build made without that requirement.
- Device-to-device transfer: not run.
- Another system font: the rows of the compass's readout stand 2 to 4 px apart in these pictures, in places worked out from this font's letter heights. A watch whose system font has taller letters or longer descenders was not seen.
