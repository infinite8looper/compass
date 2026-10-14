# Testing

The tests use real files, real sensors and real location (fed to the emulator); there are no mocks. Everything here was run on emulators. A debug build (commit becfa3e) was run by the owner on a Pixel Watch 3 (API 37) on 2026-10-13.

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
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest   # the emulator's serial, from `adb devices`
scripts/test-counts.sh device
pkill -f geo-feed.sh
```

- The position feed is needed by `LocationSourceTest`, by the `CompassViewModelTest` tests whose comments say so, by `InstantSaveTest`, `LocationOnScreenTest`, `TrueNorthTest` and `PressVibrationTest`, and by the tests that save a position through the real activity (`DialInputsTest`, one test of `AccessibilityTreeTest`, and `SideButtonTest` on a watch that describes a button). Android delivers location only to an app that is on screen, so those tests bring the real activity to the front themselves. `scripts/geo-feed.sh <lon> <lat> [seconds]` sends `adb emu geo fix` once a second for that many seconds (120 if left out); the tests expect 43.7022, -72.2896.
- The build refuses to run the instrumented tests anywhere but on an emulator (`app/build.gradle.kts`): every `connected...AndroidTest`, `connectedCheck`, `deviceCheck` and `uninstall...` task fails before it does anything unless `ANDROID_SERIAL` is an emulator's serial (`emulator-NNNN`) and `adb devices` lists no device that is not an emulator. Without that, Gradle would run the tests on every device adb lists and wipe the app's data on each. Seen on 2026-10-13: with no `ANDROID_SERIAL`, with `ANDROID_SERIAL=localhost:5555`, and with `ANDROID_SERIAL=emulator-5554` while the emulator was also attached as `localhost:5555`, `connectedDebugAndroidTest` ended in `refusing: ...` and `BUILD FAILED` in under a second, and so did `uninstallDebug` with no `ANDROID_SERIAL`; with one emulator and its serial the tests ran. `installDebug` is not held back. `scripts/all-suites.sh` sets the serial and makes its own check as well.
- While the instrumented tests run, the screen is all white for about half a second each time a test closes the activity. That is `InstrumentationActivityInvoker$EmptyActivity`, a helper activity of the test library in the test package (`com.infinite8looper.compass.test`), which has a light theme; the app's own window was not in front in those frames (screen recording and `dumpsys window`, API 35, 2026-10-13). No white frame was recorded on a cold launch, a font size change, sleep and wake, Home and back, or a change of pose.
- The run applies `animationsDisabled = true` (set in `app/build.gradle.kts`): a screen that keeps animating, such as the hold-flat hint that loops while an emulator is upright, never lets the main thread go idle. If a run is cut short the animation scales can stay at 0; `scripts/emulator.sh` puts any scale at 0 back to 1 the next time it runs.
- One class: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.infinite8looper.compass.SideButtonTest`.
- More than one class: `'-Pandroid.testInstrumentationRunnerArguments.tests_regex=.*(DialInputsTest|SideButtonTest).*'`. A list of classes separated by commas in the `class` argument ran only the first one (2026-10-13).
- `InstantSaveTest` saves from a position in hand that came from the geo console. `geo fix` and `geo nmea` take no accuracy. The API 33, 34 and 35 images give every position an accuracy of 5 m, so a more accurate position never comes from them; the API 37 image gives accuracies that vary (3.0 to 3.8 m seen on 2026-10-14), so there the view model's own updates refine saves as well. The test hands the view model a position more accurate than any of them (under 1 m) through `offerRefinement`, the call the view model's own location updates make for every position in the ten seconds after a press, and reads the rows back from the app's database, the bytes of its files included after a delete.
- `LocationOnScreenTest` follows the real activity's view model while the ring is turned by touch, a row is opened, calibration is opened, Home is pressed and the screen is put to sleep (`input keyevent KEYCODE_SLEEP`), and watches its live position come and go. `PressVibrationTest` reads `dumpsys vibrator_manager`, as `scripts/haptics.sh` does, after a press; the system records a vibration when it has ended, so the test waits 1.5 s before it reads. On an instant save the press's vibration is cut short by the confirming one about 40 ms later (`cancelled_superseded`, API 35).
- `DialInputsTest` sends real touches to the real activity: one tap, two taps close together and too far apart in time and in place, a long press, a vertical and a sideways swipe, each alone and right after a tap. Because the run has the system's animations off, the ring of screens does not slide in these tests, so a double tap that lands during a slide is not made there; that it saves nothing is the rule `doubleTapSaves` in `nav/Inputs.kt`, checked by `InputsTest`. The `Calibrate` prompt needs a magnetometer that reports low accuracy, which no emulator does: on the emulators the test of it checks instead that with no prompt showing, two taps at that place are an ordinary double tap.
- What a watch says of its buttons decides which case `SideButtonTest` and `WatchInputsDeviceTest` check (`sensors/WatchButtons.kt`). The emulator images report no button, so `SideButtonTest` checks there that the side keys do nothing, and `WatchInputsDeviceTest` fails if an image ever reports otherwise. The handling of the keys on a watch that has a button is checked apart from any screen by `SideButtonKeysTest`, which gives real key events to the app's handler, and by `TrailTest`.
- `RingScreensTest` reads the counters the three screens keep of how often each was built and composed (`ui/ScreenCompositions.kt`), and turns the ring with touches it injects and with Back. It switches the breadcrumb trail and the coordinates off, so that no position fix changes a screen while the counts are compared.
- `scripts/test-counts.sh device [folder]` reads `app/build/outputs/androidTest-results/connected/`, or the given folder, such as a copy kept from an earlier run.

## Instrumented tests, every emulator

```
scripts/all-suites.sh                      # API 33, API 34, Wear OS 7.0 (API 37), then API 35
scripts/all-suites.sh Wear_OS_API34        # only the named emulators
```

For each emulator it switches to it, lays the virtual watch flat at a magnetic heading of 40 (`scripts/set-heading.sh`), wakes the screen and checks that it is awake, starts the position feed, runs `connectedDebugAndroidTest` with a 40-minute deadline, stops the feed, keeps a copy of the results, and prints per-class counts and totals, then the sum over all. Output is in `app/build/all-suites/` (`suite-<avd>.log`, `results-<avd>/`). Before the suite it reads the pose back from the emulator (`adb emu sensor get acceleration`) and does not run the suite on an emulator that is not flat. The last emulator is left running with the debug app installed again, and each emulator the suite ran on is left lying flat at the heading of 40, not upright. The exit status is 1 if a run failed, a test failed, or a test was skipped.

## The backup agent, by the system's own backup

```
scripts/backup-off-check.sh        # API 33, 34 or 35: it needs adb root and sqlite3 on the device
```

The instrumented tests cannot run the system's backup of the app: a backup ends the app's process, which is the test's. This script runs the system's backup and restore from the host (`bmgr`) against the debug app as built, with the system's local transport, and reads the stored archive's entries with the device's `tar`. The transport is told what kind of pass it is through its own parameter (not encrypted, `is_encrypted=true`, `is_device_transfer=true`); under the last two the backup rules let the database and the settings file through, so that is where "the marker alone" is judged. Its cases are listed at the top of the script: the switch on in each kind of pass; the switch turned off through the question, in each kind; the app installed again from a transfer and from a backup (the switch off, no question, no database); the switch tapped on, a backup, an install (the waypoint back, the switch on); a switch owed off by a restore, with the settings writable and with the settings folder made unwritable; and the app's files folder made unwritable. It wipes the app's data, and puts the Backup Manager, the transport and the transport's parameter back as they were when it ends, and has a trap that does the same when it is stopped by INT, TERM or HUP (an interrupted run was not tried). Exit status 0 if every requirement held.

## Emulator-only behavior

- `DeletedWaypointTest` has tests that set the device-wide debug property `debug.sqlite.journalmode` to check the database under the platform's other journal mode; they are skipped anywhere that is not an emulator (the test recognizes one by `Build.HARDWARE` being `ranchu` or `goldfish`, or `Build.PRODUCT` starting with `sdk_`).
- `AccessibilityTreeTest.theHeadingIsDescribedWithItsNameAndUnit` needs the heading number on screen. A booted emulator's virtual watch is upright, where the compass shows the hold-flat hint and no heading; there the test is skipped (`assumeTrue`), Gradle's build still succeeds, and `scripts/test-counts.sh device` counts it as skipped and prints the reason. So a bare `./gradlew connectedDebugAndroidTest` on an upright emulator reports 1 skipped. `scripts/all-suites.sh` lays each emulator flat first (`scripts/set-heading.sh 40`), so there it runs, and a skipped test there makes the script exit 1; by hand, run `scripts/set-heading.sh 40` before the suite. `theTreeHoldsEitherTheHeadingOrTheHoldFlatHintAndNeverBoth` runs in either pose and checks the one it finds.
- `WatchColorsDeviceTest` reads what the watch gives: with the system setting `dynamic_color_theme_enabled` at 1 on API 35 or newer one branch of each test runs, anywhere else the other.
- Every script under `scripts/` that talks to a device sources `scripts/emulator-only.sh` first. It reads `adb devices`: if anything is listed whose serial is not `emulator-NNNN` (in any state), or more than one emulator, the script prints why and exits 1 without sending anything; with exactly one emulator it sets `ANDROID_SERIAL` to that serial, so every adb call of the script addresses that emulator only. `scripts/all-suites.sh` looks again right before each Gradle run and passes the serial to Gradle. Seen on 2026-10-13 with the API 35 emulator attached a second time as `localhost:5555` (`adb connect localhost:5555`, a serial that is not `emulator-NNNN`): all seventeen scripts refused; `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest` printed `Running tests on devices: Wear_OS_Large_Round(AVD) - 15` and wrote one result file, and the same command without it ran on both entries. No physical device was used for this. `scripts/seed-targets.sh`, `scripts/raw-swipe.sh` and `scripts/screenshot-matrix.sh` also keep their own check that the device is an emulator.

## What differs by image

- API 33 and Wear OS 7.0 (API 37): a change of pose sent through the emulator's console (`scripts/set-heading.sh`, `adb emu sensor set acceleration ...`) puts the screen to sleep (seen on API 33 as `mWakefulness=Dozing` right after the command; on API 37 as activities that stay stopped). Why these two images do it was not looked into. An app on a sleeping screen is stopped and gets no position, so the tests that use the real activity then fail (23 and 22 of 134 in one run). Send `adb shell input keyevent KEYCODE_WAKEUP` after changing the pose; `scripts/all-suites.sh` does. The API 34 and 35 images stay awake.

- API 33: no `adb shell input rotaryencoder`; `scripts/crown.sh` writes the crown's events as root (`adb root`, then `sendevent`) and says so; run `adb unroot` afterwards. The image sometimes goes to ambient shortly after launch; `adb shell input keyevent KEYCODE_WAKEUP` wakes it.
- Wear OS 7.0 (API 37, `Wear_OS_Newest`): a signed image, so there is no `adb root` and no `sqlite3` on the device. `scripts/seed-targets.sh` changes a copy of the database file with this machine's `sqlite3` and puts it back; `scripts/raw-swipe.sh` sends the touch through the emulator's console instead of the device.
- `adb shell input swipe` can drop the rest of a swipe when the app is slow on one sample; `scripts/raw-swipe.sh` writes raw touch events and is the one to use when the outcome of a swipe matters.

## Driving the app by hand

- Heading: `scripts/set-heading.sh <degrees>` lays the virtual watch flat and sets a magnetic heading; the reading takes about 20 seconds to settle, and with true north on, the dial shows the heading corrected by the declination. The default pose is upright, which is the hold-flat state.
- Saving from the compass: `scripts/double-tap.sh` (two `input tap` sent by one shell a tenth of a second apart, inside the double-tap time; it prints the gap there was). The side button's key, `adb shell input keyevent KEYCODE_STEM_1`, does nothing on the emulators: the images describe no button, so the app installs no key handling. Crown: `scripts/crown.sh <clicks>`.
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
scripts/frame-times.sh swipes <label>         # frame times during ten swipes round the ring of three screens
scripts/frame-times.sh settings <label>       # frame times while a list is scrolled to its end and back
```

`scripts/screenshot-matrix.sh` wipes the app's data and drives the app through its states; its header lists the sections, and [docs/screenshots/README.md](screenshots/README.md) lists each picture and how it was made. A status that shows for two or three seconds is caught by taking pictures until its color appears, so every picture still has to be looked at.

## Frame times

`scripts/frame-times.sh` reads the system's frame statistics for the app (`dumpsys gfxinfo`) around a movement it makes itself. It measures whichever build is installed under the app's id. To compare builds, on one emulator in one sitting, for each build:

```
./gradlew assembleDebug assembleWatch
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk     # or app/build/outputs/apk/watch/app-watch.apk
adb -s emulator-5554 shell pm clear com.infinite8looper.compass
scripts/set-heading.sh 40
adb -s emulator-5554 shell am start -n com.infinite8looper.compass/.MainActivity
# wait 20 s for the compass to settle
scripts/frame-times.sh swipes <label>      # twice: twenty swipes to the left end on the settings
scripts/frame-times.sh swipes <label>
scripts/frame-times.sh settings <label>    # twice
scripts/frame-times.sh settings <label>
```

Each `adb` line names the emulator by its serial (`emulator-5554` when it is the only one running; `adb devices` lists it), so it reaches that emulator and nothing else; the scripts refuse to run while a device that is not an emulator is attached.

Measured on `Wear_OS_Large_Round` on 2026-10-13, two runs of each, the system's percentiles of the frame time in ms and its share of janky frames. "Before" is the debug build of commit 5dc1765, where the three screens stood in a pager and the lists were drawn through offscreen buffers; "after" is commit 903e3cc.

| Sitting | Build | Movement | 50th | 90th | 99th | Janky |
|-|-|-|-|-|-|-|
| 1, emulator up for hours | before, debug | swipes | 22, 20 | 44, 48 | 57, 53 | 17.4 %, 20.0 % |
| 1 | before, debug | settings | 21, 20 | 38, 36 | 57, 53 | 18.8 %, 18.7 % |
| 1 | after (at 31eac23), debug | swipes | 18, 18 | 24, 31 | 36, 36 | 11.2 %, 8.6 % |
| 1 | after (at 31eac23), debug | settings | 19, 18 | 32, 32 | 48, 36 | 10.1 %, 8.4 % |
| 1 | after (at 31eac23), watch | swipes | 17, 17 | 20, 22 | 32, 34 | 9.1 %, 7.3 % |
| 1 | after (at 31eac23), watch | settings | 17, 18 | 22, 22 | 34, 34 | 6.5 %, 8.4 % |
| 2, emulator just booted | before, debug | swipes | 17, 17 | 19, 17 | 26, 19 | 3.9 %, 4.6 % |
| 2 | before, debug | settings | 17, 17 | 18, 18 | 21, 21 | 4.4 %, 3.7 % |
| 2 | after, debug | swipes | 17, 17 | 17, 17 | 19, 19 | 5.2 %, 2.9 % |
| 2 | after, debug | settings | 17, 17 | 17, 18 | 19, 20 | 4.9 %, 4.6 % |
| 2 | after, watch | swipes | 17, 17 | 17, 17 | 18, 18 | 3.1 %, 3.3 % |
| 2 | after, watch | settings | 17, 18 | 18, 17 | 22, 21 | 4.9 %, 4.6 % |

The two sittings do not agree. In the first, on an emulator that had been running for hours, the change shows; in the second, on the same emulator freshly booted, every build is at the emulator's own frame interval of about 17 ms and the builds cannot be told apart. So these numbers do not show that the change makes the screens smoother, and they do not predict what a watch does: the emulator runs on this computer's processor and draws through its graphics (the swap stage alone has a median of about 16 ms in every run). What the instrumented tests do show is that one suspected cause is gone: `RingScreensTest` counts, in the screen functions themselves, that a turn of the ring builds no screen and composes none.

## Counts at the time of writing

401 unit tests. 185 instrumented tests, run on each of `Wear_OS_API33`, `Wear_OS_API34`, `Wear_OS_Newest` and `Wear_OS_Large_Round` by `scripts/all-suites.sh` on 2026-10-14: 185 passed, 0 failed and 0 skipped on each, 740 in all. `RingScreensTest` had failed now and then before this: the system keeps a cache of the accessibility tree for a test, and a node's place read from it could stay what it was for seconds after a list had moved. Its nodes are now fetched again from the app (`AccessibilityNodeInfo.refresh`) before they are read, and a tree that cannot be read whole in three tries fails the test. `scripts/backup-off-check.sh` on the same day, API 33, 34 and 35: 65 requirements, all held, on each.
