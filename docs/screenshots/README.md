# Screenshots

Every picture here is a capture from an emulator, taken on 2026-10-13 from the debug build. Nothing was drawn or edited. A picture marked "frames" is several captures joined side by side, first on the left.

- `<state>-large.png` is from `Wear_OS_Large_Round` (API 35, 454 x 454) and `<state>-small.png` from `Wear_OS_Small_Round` (API 35, 384 x 384). Both were made by `scripts/screenshot-matrix.sh large` and `scripts/screenshot-matrix.sh small`; the section named in each row is the argument that makes that row's pictures again (`scripts/screenshot-matrix.sh large trail`).
- The position fed is 43.7022, -72.2896 (`scripts/geo-feed.sh`). Waypoints are put in the app's database with `scripts/seed-targets.sh <bearing>:<place on the color ring>:<meters>`. "Three targets" is `60:137.5:400 200:275:1500 300:52.5:5000`. A heading is set with the emulator's magnetic-field sensor and corrected until the compass shows it.
- The emulators give no color scheme, so the accent is blue and north and the error color are red, except in the two `accent-` pictures.
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
| Thirty targets | `compass-many` | `compass`: `seed-targets.sh many` |
| `Arrived at target` | `compass-arrived` | `compass`: `seed-targets.sh 0:0:4` (4 m away) |
| `No target` | `compass-no-target` | `compass`: three waypoints, the waypoints header tapped to clear all |
| A distance marked `(old)` | `compass-stale` | `compass`: `settings put secure location_mode 0`, 25 s |
| A target with location off | `compass-location-off` | `compass`: the app stopped and started with location off |
| `Locating…` | `compass-locating` | `compass`: location on again, the app started, 1.2 s |
| The three ticks that show which screen is in view | `compass-pane-ticks` | `panes`: a drag toward waypoints let go early; taken as the row comes to rest |
| Hold-flat, no target (4 frames, 0.45 s apart) | `holdflat-frames` | `moving`: `adb emu sensor set acceleration 0:9.81:0` |
| Hold-flat with a target (4 frames) | `holdflat-target-frames` | `moving`: the same with three targets |
| A long name sliding (4 frames, 1.7 s apart) | `name-sliding-compass` | `moving`: the focused waypoint renamed in the database to a 60-character name |

## Logging with one press

| State | File | How it was produced |
|-|-|-|
| The system's permission dialog | `permission-dialog` | `permission`: first press of the side button (`input keyevent KEYCODE_STEM_1`) |
| `Needs access to` / `precise location` on the compass | `log-needs-access` | `permission`: `Use precise location` switched off in the dialog, `While using app` |
| The same on the waypoints band | `waypoints-needs-access` | `permission`: `Log here`, then `Keep approximate location` |
| The same under `Show coordinates` | `settings-needs-access` | `permission`: the row tapped with only approximate location granted |
| `Saving…` | `log-saving` | `log`: a press 1.2 s after the app starts, before the first fix |
| `Saved Waypoint N` and the accuracy | `log-saved` | `log`: the same press, when the confirming green shows |
| `No GPS fix` / `Try again outdoors` | `log-no-gps-fix` | `log`: `input keyevent KEYCODE_STEM_1 KEYCODE_HOME`, location off while the app is off screen, 23 s, location on, the app opened again (the picture is taken while it opens) |
| `Location is off` | `log-location-off` | `log`: location off, Home and back, a press |
| `Couldn't save` | `log-couldnt-save` | `log`: `run-as ... chmod 440 databases/waypoints.db` with the app stopped, start, a press; the file is put back to 660 |
| The status band on the waypoints screen | `waypoints-saved-band` | `waypoints`: `Log here` tapped |

## Coordinates

| State | File | How it was produced |
|-|-|-|
| No target | `coords-clean` | `coords`: `Show coordinates` on, no waypoints, heading 127 |
| Old: `(old)` after the latitude | `coords-clean-old` | `coords`: location off, 25 s |
| With a focused target | `coords-target` | `coords`: three targets |
| Before the first fix | `coords-locating` | `coords`: location off and on, the app started, 1.2 s |

## Breadcrumb trail

| State | File | How it was produced |
|-|-|-|
| `Saved Crumb 1` | `trail-crumb-saved` | `trail`: `Breadcrumb trail` on, a double press (two `KEYCODE_STEM_1`), a press |
| Laying, `Trail 3`, a crumb tick near 180 | `trail-laying` | `trail`: crumbs dropped at 43.7022, 43.70274 and 43.70328 north, then the feed moved to 43.7042 |
| The trail's row while laying | `trail-row-laying` | `trail`: the waypoints screen |
| Retracing, `Crumb 3` | `trail-retracing` | `trail`: a double press, heading 160 |
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
| Thirty, top of the list | `waypoints-many` | `waypoints`: `seed-targets.sh many` |
| Thirty, end of the list | `waypoints-many-end` | `waypoints`: twelve swipes up |
| Old distances | `waypoints-old` | `waypoints`: location off, 25 s |
| A long name sliding (3 frames, 2.2 s apart) | `name-sliding-list` | `moving` |

## Edit

| State | File | How it was produced |
|-|-|-|
| A short name | `edit-short` | `edit`: a long press on the row `Camp` |
| The color being dragged, finger down | `edit-recolor` | `edit`: `input motionevent DOWN` on the ring near 4 o'clock, `MOVE` in steps to past 1 o'clock |
| `Hold to delete` half way | `edit-hold` | `edit`: a touch held for 0.8 s, taken at 0.75 s, let go before it deletes |
| A long name sliding (3 frames) | `name-sliding-edit` | `moving` |

## Settings and calibration

| State | File | How it was produced |
|-|-|-|
| Top of the list | `settings-top` | `settings`: after `pm clear` (the defaults in the United States locale) |
| Middle | `settings-middle` | `settings`: scrolled to `Blind navigation` |
| End | `settings-end` | `settings`: scrolled to `Calibrate` |
| Every switch off (3 frames: top, middle, end) | `settings-all-off` | `settings`: each row tapped |
| Calibration | `calibrate` | `calibration`: `Calibrate` tapped |
| Interference | `calibrate-interference` | `calibration`: `adb emu sensor set magnetic-field 0:80:-45` (92 microtesla) |

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
| A color scheme, seed `2F8BFF` | `accent-a-large.png` | `Wear_OS_Newest` (API 37): see below |
| A color scheme, seed `00AA55` | `accent-b-large.png` | `Wear_OS_Newest`: see below |

The two `accent-` pictures need the system's color scheme switched on through `adb`, since no image does it from the watch face:

```
adb shell settings put global dynamic_color_theme_enabled 1
adb shell settings put secure theme_customization_overlay_packages '{"android.theme.customization.system_palette":"00AA55","android.theme.customization.accent_color":"00AA55","android.theme.customization.color_source":"preset"}'
```

## Not shown, because an emulator cannot produce it

- The `Calibrate` prompt on the compass, and the calibration frame at less than full (accuracy below high).
- The accuracy note after a crumb's distance (`9 m ±20 m`) and `Saving…` with an accuracy that is not yet good enough: the emulator's position accuracy is always 5 m.
- A color scheme that follows the watch face.
- Anything felt: the tick tap, the two blind navigation pulses, the confirming tap.
