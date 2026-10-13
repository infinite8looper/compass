# Testing

The tests use real files, real sensors and real location (fed to the emulator); there are no mocks. Everything here was run on emulators. No physical watch has been used.

## Read this before running the instrumented tests

The instrumented tests act on the app's real data on the device they run on. They delete every waypoint and crumb in the app's database, change its settings, and Gradle uninstalls the app when the run ends, which removes the app's data. A watch holding real waypoints loses them. Run them on emulators.

## Setup

- JDK 17 (`JAVA_HOME` at it; on this machine `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`), and the Android SDK named in `local.properties`.
- `adb` is in `~/Library/Android/sdk/platform-tools/`; the scripts use it by that path.
- macOS has no `timeout` command; every script that waits has its own deadline.
- Emulators (AVDs, all arm64):

| AVD | API | System image |
|-|-|-|
| `Wear_OS_API33` | 33 (Wear OS 4) | `system-images;android-33;android-wear;arm64-v8a` |
| `Wear_OS_API34` | 34 (Wear OS 5) | `system-images;android-34;android-wear;arm64-v8a` |
| `Wear_OS_Large_Round` | 35 (Wear OS 5.1) | `system-images;android-35-ext15;android-wear;arm64-v8a` |
| `Wear_OS_Small_Round` | 35 | same as the large one, with a smaller screen |
| `Wear_OS_Newest` | 37 (Wear OS 7.0) | `system-images;android-37.0;android-wear-signed;arm64-v8a` |

- Keep exactly one emulator attached. The scripts and Gradle's connected tests assume a single device. `scripts/switch-emulator.sh <avd>` stops the attached one, boots the named one (`scripts/emulator.sh` does the booting and waiting, up to 5 minutes) and installs the debug app if it has been built.

## Unit tests

```
./gradlew testDebugUnitTest
scripts/test-counts.sh unit
```

They cover the pure functions in `nav/` and need no device. The second command prints each class's count and the total from the result XML, and exits 1 if a test failed or there are no results.

## Instrumented tests, one emulator

```
scripts/switch-emulator.sh Wear_OS_Large_Round
scripts/geo-feed.sh -72.2896 43.7022 3000 > /dev/null 2>&1 &
./gradlew connectedDebugAndroidTest
scripts/test-counts.sh device
pkill -f geo-feed.sh
```

- The position feed is needed by `LocationSourceTest`, by the `CompassViewModelTest` tests whose comments say so, and by the `SideButtonTest` tests that save a position. Android delivers location only to an app that is on screen, so those tests bring the real activity to the front themselves. `scripts/geo-feed.sh <lon> <lat> [seconds]` sends `adb emu geo fix` once a second for that many seconds (120 if left out); the tests expect 43.7022, -72.2896.
- The run applies `animationsDisabled = true` (set in `app/build.gradle.kts`): a screen that keeps animating, such as the hold-flat hint that loops while an emulator is upright, never lets the main thread go idle. If a run is cut short the animation scales can stay at 0; `scripts/emulator.sh` puts any scale at 0 back to 1 the next time it runs.
- One class: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.infinite8looper.compass.SideButtonTest`.
- `scripts/test-counts.sh device [folder]` reads `app/build/outputs/androidTest-results/connected/`, or the given folder, such as a copy kept from an earlier run.

## Instrumented tests, every emulator

```
scripts/all-suites.sh                      # API 33, API 34, Wear OS 7.0 (API 37), then API 35
scripts/all-suites.sh Wear_OS_API34        # only the named emulators
```

For each emulator it switches to it, starts the position feed, runs `connectedDebugAndroidTest` with a 40-minute deadline, stops the feed, keeps a copy of the results, and prints per-class counts and totals, then the sum over all. Output is in `app/build/all-suites/` (`suite-<avd>.log`, `results-<avd>/`). The last emulator is left running with the debug app installed again. The exit status is 1 if a run failed or a test failed.

## Emulator-only behavior

- `DeletedWaypointTest` has tests that set the device-wide debug property `debug.sqlite.journalmode` to check the database under the platform's other journal mode; they are skipped anywhere that is not an emulator (the test recognizes one by `Build.HARDWARE` being `ranchu` or `goldfish`, or `Build.PRODUCT` starting with `sdk_`).
- `WatchColorsDeviceTest` reads what the watch gives: with the system setting `dynamic_color_theme_enabled` at 1 on API 35 or newer one branch of each test runs, anywhere else the other.
- `scripts/seed-targets.sh` and `scripts/raw-swipe.sh` refuse to run on anything but an emulator; `scripts/set-heading.sh` and `scripts/geo-feed.sh` use `adb emu` commands, which exist only for emulators.

## What differs by image

- API 33: no `adb shell input rotaryencoder`; `scripts/crown.sh` writes the crown's events as root (`adb root`, then `sendevent`) and says so; run `adb unroot` afterwards. The image sometimes goes to ambient shortly after launch; `adb shell input keyevent KEYCODE_WAKEUP` wakes it.
- Wear OS 7.0 (API 37, `Wear_OS_Newest`): a signed image, so there is no `adb root` and no `sqlite3` on the device. `scripts/seed-targets.sh` changes a copy of the database file with this machine's `sqlite3` and puts it back; `scripts/raw-swipe.sh` sends the touch through the emulator's console instead of the device.
- `adb shell input swipe` can drop the rest of a swipe when the app is slow on one sample; `scripts/raw-swipe.sh` writes raw touch events and is the one to use when the outcome of a swipe matters.

## Driving the app by hand

- Heading: `scripts/set-heading.sh <degrees>` lays the virtual watch flat and sets a magnetic heading; the reading takes about 20 seconds to settle, and with true north on, the dial shows the heading corrected by the declination. The default pose is upright, which is the hold-flat state.
- Side button: `adb shell input keyevent KEYCODE_STEM_1`. Crown: `scripts/crown.sh <clicks>`.
- Waypoints: `scripts/seed-targets.sh <bearing>:<color-place>[:<meters>] ...` puts waypoints in the app's database with the app stopped (`many` and `none` are also accepted). Start the app once first so the database exists.
- Taps by text: `scripts/tap-text.sh <text>`. Haptics the system recorded: `scripts/haptics.sh mark` then `scripts/haptics.sh since <mark>`.
- Fused location is delivered only while the app is in the foreground, and needs `scripts/geo-feed.sh` running. The emulator cannot lower the reported sensor accuracy and has no vibration motor.

## Screenshots

```
scripts/screenshot.sh <name>        # writes docs/screenshots/<name>.png from the attached emulator
scripts/burst.sh <out.png> 6        # six screenshots side by side, for something that moves
scripts/record.sh <out.mp4> 10      # a 10-second screen recording; not kept in the repository
```

Screenshots are taken on `Wear_OS_Large_Round` and `Wear_OS_Small_Round` and read by eye. `scripts/screenshot.sh` checks that the bytes are a PNG before it replaces a file.

```
scripts/screenshot-matrix.sh large            # every screen and state, into docs/screenshots/<state>-large.png (about 25 minutes)
scripts/screenshot-matrix.sh small trail      # one section, on whichever emulator is attached
scripts/ui-dump.sh                            # what the screen gives accessibility services: texts, descriptions, flags
scripts/frame-times.sh <label>                # frame times while the heading sweeps a full circle
```

`scripts/screenshot-matrix.sh` wipes the app's data and drives the app through its states; its header lists the sections, and [docs/screenshots/README.md](screenshots/README.md) lists each picture and how it was made. A status that shows for two or three seconds is caught by taking pictures until its color appears, so every picture still has to be looked at.

## Counts at the time of writing

347 unit tests. 131 instrumented tests, run on each of `Wear_OS_API33`, `Wear_OS_API34`, `Wear_OS_Newest` and `Wear_OS_Large_Round` by `scripts/all-suites.sh` on 2026-10-13: 131 passed, 0 failed and 0 skipped on each, 524 in all. Lint (`./gradlew lintDebug`) reports 0 errors and one warning, `StaticFieldLeak` on the settings store's singleton.
