# Store listing pictures

The pictures for the Google Play store listing of `Compass: Waypoints & Trail`. The screenshots are captures from the
`Wear_OS_Large_Round` emulator (454 x 454, API 35) running the debug build with the `Standard` colors, taken on
2026-10-16 by `scripts/screenshot-matrix.sh large play trail calibration` (written to a scratch folder with
`MATRIX_OUT`). `scripts/play-assets.py` made every file here from those captures and from the launcher icon's
resource files: each screenshot is the capture laid on black (no transparent pixel, 8 bits per channel, no alpha) and
otherwise unchanged; no device frame. Where a position is used, it is 43.7022,-72.2896; no coordinates are on screen.

| File | Size | Pixels | Format | What it shows |
|-|-|-|-|-|
| `screenshot-1-compass.png` | 66.9 KB | 454 x 454 | PNG, RGB, no alpha | The compass facing 290 with two targets on the ring; `Waypoint 1` (yellow, at 300, `0.7 mi`) is the focused one, in the readout; `Waypoint 2` (green) at 60 |
| `screenshot-2-waypoints.png` | 25.0 KB | 454 x 454 | PNG, RGB, no alpha | The waypoints list: the `Trail` row (`Off`), `Waypoint 1` (`0.7 mi`) and `Waypoint 2` (`0.2 mi`), both targets |
| `screenshot-3-trail.png` | 66.5 KB | 454 x 454 | PNG, RGB, no alpha | The compass retracing a trail: `Crumb 3`, `103 m`, facing 160 |
| `screenshot-4-calibration.png` | 36.8 KB | 454 x 454 | PNG, RGB, no alpha | Calibration with seven segments of the ring lit: `Covered 29%`, `High`, `Field: 49 µT`. The `Check` row at its bottom is the debug build's; the release build has no such row |
| `screenshot-5-settings.png` | 35.6 KB | 454 x 454 | PNG, RGB, no alpha | The settings at `Feet and miles`, `Tick feedback` and `Blind navigation`, all on |
| `screenshot-6-settings-colors.png` | 36.8 KB | 454 x 454 | PNG, RGB, no alpha | The settings at `Show coordinates` (off), `Colors` (`Standard`) and `Back up waypoints` (on); taken with the emulator's color-scheme setting (`dynamic_color_theme_enabled`) on, without which the row reads `Not available on this watch` from API 35 |
| `icon-512.png` | 58.9 KB | 512 x 512 | PNG, RGBA (32-bit) | The launcher icon as its round mask shows it: the disc in `#153D70` (`ic_launcher_background`) filling the square edge to edge, the white ring and the red and white needle of `ic_launcher_foreground.xml`; transparent outside the disc |
| `feature-graphic.png` | 129.8 KB | 1024 x 500 | PNG, RGB, no alpha | Black, the icon (150 px) and `Compass: Waypoints & Trail` in Roboto at the left, `screenshot-1-compass.png` (440 px) at the right |

To make them again: run `scripts/screenshot-matrix.sh large play trail calibration` on the large round with
`MATRIX_OUT` naming an empty folder, pull the emulator's font (`adb pull /system/fonts/Roboto-Regular.ttf`), then
`python3 -I scripts/play-assets.py <that folder> <the font>`.
