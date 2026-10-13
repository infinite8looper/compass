# Wear compass v3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix what the first run on a Pixel Watch 3 showed: saving without the side button, instant saves, smooth screens, usable calibration, three dial vibrations, a colors setting, coordinates together, a full-size icon, and the means to settle the heading drift on the watch.

**Architecture:** As v2. Every new decision is a pure function in `nav/` (no Android imports) with unit tests written first; `sensors/`, `data/` and `ui/` call them. No per-frame allocation or recomposition on the compass.

**Tech Stack:** Kotlin 2.4.21, AGP 9.4.1, JDK 17, Wear Compose Material 3 1.7.1, Room 2.8.5, DataStore, play-services-location. compileSdk 37, minSdk 33.

**Spec:** `docs/superpowers/specs/2026-10-13-wear-compass-v3-watch-report.md`. The diagnosis each task starts from: `.superpowers/sdd/2026-10-11-wear-compass-v2/watch-report-diagnosis.md` (git-ignored working file; read the section for your task).

## Global Constraints

- Tests first, and seen failing. Never weaken or delete a test to pass; fix the code. A changed rule changes its tests in the same commit, with the spec sentence that changed it.
- No mocks, fakes, test location providers or injected failures. Real Room, real DataStore, the emulator's sensor and geo consoles, adb system settings.
- Emulators only: every device script and Gradle device task refuses a non-emulator. Never address the owner's watch. One emulator at a time; `scripts/all-suites.sh` for the suites on API 33, 34, 35 and 37.
- UI is verified by pictures looked at, on both round sizes (`Wear_OS_Large_Round`, `Wear_OS_Small_Round`).
- `README.md`, `docs/TESTING.md`, `docs/screenshots/` and the spec are corrected as facts in the same task; no promise, policy, privacy or support wording. Words shown to wearers that the spec lists as proposed are built as proposed and listed in the report.
- No signing with any key but the local debug key, no keystore in the repo, no push, no publishing.
- Logs carry the exception class only. No secrets or personal data in commits.
- Commits end with the attribution lines the dispatch gives.
- Each task ends with: unit tests, lint (0 errors), debug build, and the device suites on all four emulators, on the final code.

## Review Focus

- A double tap that lands during a swipe, a long press or on the `Calibrate` tap area: exactly one action, the intended one.
- A save refined after the wearer has renamed, moved or deleted the waypoint: the wearer's change wins, and a deleted waypoint never returns.
- A position in hand that is 9.9 seconds old and 300 m off indoors: it is written with its true accuracy and the poor-fix note, not presented as good.
- A watch with no vibration primitives, no assignable button, no one-handed framework or no color scheme: every feature falls back and nothing crashes or goes silent without saying so.
- Location running while the app is on screen: it stops when the app leaves the screen, in every path (swipe to settings, Home, ambient, screen off).

---

### Task 1: Smooth screens, and a build for the watch

**Files:** `ui/CompassApp.kt`, `nav/Panes.kt`, `ui/Instrument.kt`, `ui/SettingsScreen.kt`, `ui/WaypointsScreen.kt`, `ui/CompassScreen.kt`, `sensors/HeadingSource.kt`, `app/build.gradle.kts`, `scripts/frame-times.sh`, `docs/TESTING.md`.

- [ ] Record the baseline first: frame times for ten swipes round the ring and a full scroll of settings, on `Wear_OS_Large_Round`, debug build, kept in the report.
- [ ] The three screens are built once and stay: a turn of the ring composes no screen, and the settings and waypoints lists keep their scroll position across a full turn. Keep the ring (it wraps in both directions) and every rule of `nav/Panes.kt` that the tests pin. Instrumented test first: scroll settings, go round the ring, the list is where it was; and a composition count for a screen does not rise across a turn.
- [ ] No row and no list is drawn through an offscreen buffer during a scroll: the fade at a row's tick and the list's top and bottom fades are drawn over the content. Pictures before and after must match to the eye on both sizes.
- [ ] Text styles on the compass are remembered; nothing is rebuilt per recomposition that does not change.
- [ ] Heading and magnetometer events are delivered on a thread of the app's own (a `HandlerThread` given to `registerListener`), stopped with the source; what reaches the frame loop is still allocation-free. The existing `HeadingSourceTest` cases hold.
- [ ] A build type `watch`: `initWith(release)` (minified, resources shrunk, not debuggable), `signingConfig` the debug one, same application id, `matchingFallbacks`, and a `BuildConfig` boolean `DIAGNOSTICS` that is true for `debug` and `watch` and false for `release`. `./gradlew assembleWatch` builds. Start it on an emulator and run through the three screens, edit and calibration by hand (pictures): a minified build has never run before, so look for anything R8 removed.
- [ ] Frame times again on the same emulator, debug and `watch` builds, same recipe; the table goes in the report and `docs/TESTING.md` says how to repeat it. State plainly that emulator numbers do not predict the watch.

### Task 2: Saving without the side button, and `Breadcrumb trail` on by default

**Files:** `nav/Controls.kt` or a new `nav/Inputs.kt`, `ui/CompassScreen.kt`, `ui/LogHere.kt`, `ui/SettingsScreen.kt`, `nav/Wording.kt`, `data/SettingsStore.kt`, `CompassViewModel.kt`, tests.

- [ ] A double tap on the compass dial, in view, makes the call the side button's single press makes (waypoint, or crumb while laying). Single tap: nothing. Long press, vertical swipe, sideways swipe and the `Calibrate` tap area behave as before; show each beside a double tap in an instrumented test with real input injection.
- [ ] On API 37 and above the dial carries the one-handed primary action mapped to the same call (`rememberOneHandedGestureConfiguration`, `Modifier.oneHandedGesture`). Read the library's source for the exact API before writing. Try `adb shell cmd IWearGestureService gesture DoublePinch` on the API 37 emulator; if the emulator has the service, test with it; if not, say so and leave this to the watch.
- [ ] `WearableButtons.getButtonCount` / `getButtonInfo` decide once whether the watch has an assignable button. Without one: no key handling, no 300 ms wait anywhere, and the settings row for the trail has no words about a press. With one: v2 behavior. Find what the emulators report and test the branch they give for real; the other branch is tested through the pure decision in `nav/`.
- [ ] The dial has an accessibility custom action that saves; `AccessibilityTreeTest` covers it.
- [ ] `Breadcrumb trail` defaults to on in all five places the diagnosis names, including an install that never stored the key and a restore with no value. Tests that pinned off are changed with the spec sentence.
- [ ] README's table of controls and the settings description follow.

### Task 3: Instant save, location while on screen, true north gets a position

**Files:** `nav/Navigation.kt`, `CompassViewModel.kt`, `sensors/LocationSource.kt`, `ui/LogHere.kt`, `ui/SettingsScreen.kt`, `data/` as needed, tests.

- [ ] Pure decision in `nav/`, unit tests first: given the position in hand (age, accuracy) and the press time, save now or wait; given a later position, refine or not; the 10 second limits; refinement ends on edit, move or delete.
- [ ] A vibration at the press, before anything else, for every route (tap, pinch, button, `Log here`, `Drop crumb`).
- [ ] With a position in hand not older than 10 seconds: the row is written at once with that accuracy and the status says saved at once; a more accurate position within 10 seconds of the press replaces position and accuracy in place. Instrumented tests with the emulator's geo console and the real database, including: rename during the 10 seconds (the name stays, the position may still improve), delete during it (stays deleted, content overwritten as `DeletedWaypointTest` requires), a second save during it.
- [ ] With none in hand: v2 path unchanged, its tests untouched.
- [ ] Location runs while the compass or the waypoints screen is in view and the permission is held; stops in every other state. Test each exit named in the Review Focus.
- [ ] True north: with the permission and no stored position, one is requested at once and `MAG` goes when the declination is known; selecting `True` without the permission opens the system dialog once. `MAG` shows exactly while the dial is magnetic.
- [ ] One log line per save in `DIAGNOSTICS` builds: milliseconds from press to written, the age and accuracy used, whether refined. No coordinates in the log.

### Task 4: Three vibrations for the dial

**Files:** `nav/Navigation.kt` (or `nav/Haptics.kt`), `ui/CompassScreen.kt`, a small `sensors/` or `ui/` player, `AndroidManifest.xml`, tests, README's permissions paragraph.

- [ ] Pure function, unit tests first: from the previous and present shown heading, the target's bearing (or none) and the settings, which one kind of crossing happened (none, thin, thick, target), the strongest winning; the existing rate limits kept.
- [ ] A player that maps the three kinds to effects from one table: composition primitives when `areAllPrimitivesSupported` says so (with `VIBRATE` declared), else the `HapticFeedbackConstants` path. No allocation per frame: effects are built once.
- [ ] The target's crossing plays with `Blind navigation` off; with it on, v2's lock pulse stands alone.
- [ ] What the emulators report for primitive support, recorded with `scripts/haptics.sh` or `dumpsys vibrator_manager`, and which branch therefore ran in the tests.
- [ ] README states the new permission as a fact.

### Task 5: The `Colors` setting

**Files:** `data/SettingsStore.kt`, `nav/WatchColors.kt`, `nav/ColorRing.kt`, `ui/Instrument.kt`, `ui/SettingsScreen.kt`, `ui/EditScreen.kt`, tests, screenshots.

- [ ] Setting `Colors` with `Standard` (default) and `Watch face`, stored, backed up with the other settings, read fail-safe.
- [ ] `Standard`: north red, pointer and the wearer's position blue, the full wheel for waypoints, nothing read from the watch. Read the v2 spec for what the waypoint colors do today and make `Standard` the full wheel.
- [ ] `Watch face`: a pale primary is deepened and kept; more roles follow the scheme (ink and grays where the scheme gives them) while every contrast rule in `nav/WatchColors.kt` still holds (unit tests over the real schemes recorded in `WatchColorsDeviceTest`, plus the pale `#D9E2FF` case).
- [ ] No scheme from the watch: the choice is not offered and the row says so. Set and clear `dynamic_color_theme_enabled` on the API 35 and 37 emulators through `adb shell settings` (the real path) to show both.
- [ ] Pictures of the compass, waypoints and settings in both modes on both sizes.

### Task 6: Calibration that can be done, and the heading sources

**Files:** `nav/Calibration.kt` (new), `nav/Navigation.kt`, `sensors/HeadingSource.kt`, `ui/CalibrationScreen.kt`, `ui/CompassApp.kt`, `ui/CompassScreen.kt`, tests.

- [ ] Pure coverage: the unit sphere of field directions in the watch's frame divided into regions; a set of regions visited; the fraction covered. Unit tests first, with vectors recorded from an emulator's sensor console.
- [ ] The screen: the ring of segments in place of the figure 8, the magnetometer's level word, the estimated heading error in degrees when the rotation vector gives `values[4]` (not when it is -1), the instruction. Screen kept on for two minutes from opening or from the last new segment.
- [ ] `Calibrate` on the compass also shows for a large estimated heading error; threshold in `nav/`, unit-tested.
- [ ] Heading sources: fused (today), magnetic (geomagnetic rotation vector if the watch has it, else accelerometer and magnetometer through `getRotationMatrix`), and a blend in pure `nav/` code (follows the fused, steered to the magnetic with a time constant), unit-tested including many full turns with a drifting fused input. The compass uses the fused source unless switched on the check screen.
- [ ] Emulator checks through the sensor console for each source.

### Task 7: The check screen

**Files:** `ui/CheckScreen.kt` (new), `nav/Panes.kt`, `ui/CalibrationScreen.kt`, `CompassViewModel.kt`, tests.

- [ ] A screen opened from a row on calibration, present only when `BuildConfig.DIAGNOSTICS` is true; a release build has no route to it (unit test on the route decision; show the release build's calibration screen without the row).
- [ ] Shows live what the spec lists; a row switches the heading source for the running app; taps play each of the three dial vibrations.
- [ ] In the app's own style, scrolling, readable on both sizes; pictures.

### Task 8: Coordinates together

**Files:** `nav/ReadoutRows.kt`, `ui/CompassScreen.kt`, tests, screenshots.

- [ ] Latitude and longitude as two adjacent lines in the lower half, four decimals, no smaller than today; the prompt and a status's second line move to the upper slot. `ReadoutRowsTest` first.
- [ ] The picture matrix (`scripts/screenshot-matrix.sh`) over every state on both sizes: nothing touches, nothing leaves the readout circle, nothing meets the pane ticks. Give the smallest gaps in pixels.
- [ ] If it cannot be met, build the best arrangement and put pictures of the alternatives in the report for the owner.

### Task 9: The icon

**Files:** `app/src/main/res/mipmap-anydpi/ic_launcher.xml`, `drawable/ic_launcher_foreground.xml`, `drawable/ic_launcher_monochrome.xml`, a background drawable or color.

- [ ] A colored disc that fills the mask, the mark enlarged to the edge of the 66 dp safe zone, the monochrome layer to match. The disc's color is the app's standard blue, deep enough for the white ring and the red needle to read; say the values.
- [ ] Pictures of the launcher on an emulator with the icon beside the system's, before and after, on both sizes, and the app's start (the quality guideline's black start screen is kept).

### Task 10: Documents and the watch checklist

- [ ] README, `docs/TESTING.md`, the screenshots index and the spec say what is built, as facts.
- [ ] A checklist for the owner's watch, one line per thing only the watch can show, in the order the spec's last section lists them, with what to look at on the check screen.
- [ ] The `watch` build made from the final commit.
