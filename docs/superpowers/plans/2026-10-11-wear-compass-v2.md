# Wear OS Compass v2 Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the compass app's interaction model and secondary screens as approved by the owner: line and arrow markers, one-press logging, three wrapping screens, a single instrument-style visual language, a breadcrumb trail and blind navigation; then finish the coordinates readout, scrolling names, documentation and a full emulator pass.

**Architecture:** The existing layers stay (pure functions in `nav/`, Room and DataStore in `data/`, sensor and location Flows in `sensors/`, one `CompassViewModel`, Compose for Wear OS in `ui/`). New decisions that can be made without Android go into pure functions with unit tests. Screen-level state that outlives a screen (a save in progress, the trail) moves into the ViewModel. A small set of shared drawing parts gives every secondary screen the same look.

**Tech Stack:** As built: Kotlin 2.4.21, Android Gradle Plugin 9.4.1, Gradle 9.8.1, KSP 2.3.12, Compose BOM 2026.09.00, Wear Compose 1.7.1, Room 2.8.5, DataStore 1.2.1, Play services location 21.4.0, JDK 17; compileSdk 37, minSdk 33, targetSdk 35.

**Spec:** `docs/superpowers/specs/2026-10-11-wear-compass-v2-redesign.md`, which amends `docs/superpowers/specs/2026-10-11-wear-compass-design.md`. Read both; the v2 document wins where they differ.

**How this plan differs from the first one:** tasks state requirements, interfaces and tests, not finished code. The codebase has moved well past what a plan author can transcribe, and its implementers have repeatedly had to adapt transcribed code to the current libraries. Read the existing code before designing.

## Global Constraints

- Package `com.infinite8looper.compass`. Min SDK 33, target SDK 35, compile SDK 37. The suite must pass on API 33, 34 and 35.
- No mocks or fakes in any test. Unit tests call real functions; instrumented tests use real files, real sensors and real location on the emulator.
- Never weaken or delete a test to make it pass. Tests for removed features are removed with the feature, and the report says which and why.
- Pure decisions first: anything that can be decided from numbers, times or lists is a pure function in `nav/` with unit tests written first and seen failing.
- The manifest requests only the two location permissions. No `VIBRATE`, no network.
- No log statement writes coordinates, names or setting values.
- Fixed strings, verbatim: `Arrived at target`, `MAG`, `No target`, `Hold flat`, `Waypoint N`, `Saving…`, `Saved Waypoint N`, `No GPS fix`, `Location is off`, `Needs access to precise location` (on two lines in the logging status: `Needs access to`, `precise location`), `Couldn't save`, `Log here`, `Hold to delete`, `Trail`, `Trail complete`, and the settings labels `True north`, `Feet and miles`, `Tick feedback`, `Blind navigation`, `Breadcrumb trail`, `Show coordinates`, `Back up waypoints`, `Calibrate`.
- Colors: ink `#E8EAED`, accent blue `#2F8BFF`, red `#FF3B30`, waypoint color `Color.hsv(hue, 0.65f, 1f)`, soft tint for a non-focused target `Color.hsv(hue, 0.22f, 0.92f)` at 60 percent alpha (tune by eye against the review page's "Soft tint" mockup and report the final values). Red is only north, failures and deleting; the `Hold flat` and `Calibrate` prompts are light gray. The accent is only the pointer and things that are on or actionable; every screen takes it from one named place so Task 11 can make it follow the watch.
- Smoothness: nothing may make the compass screen recompose per sensor event or per animation frame; animated values are read in the layout or draw phase. Show it with a temporary counter, removed before commit.
- Every tappable thing has a hit area of at least 48 dp. Everything fits both round emulator sizes (`Wear_OS_Large_Round`, `Wear_OS_Small_Round`).
- Backup guarantees hold: the committed `res/xml/data_extraction_rules.xml` keeps `disableIfNoEncryptionCapabilities="true"` on `<cloud-backup>`; deleted or renamed content never reaches a backup; the backup agent fails closed.
- Emulators: keep exactly one attached at a time. `adb` is at `~/Library/Android/sdk/platform-tools/adb`. `scripts/emulator.sh <avd>`, `scripts/set-heading.sh <deg>` (about 20 s to settle; the default pose is upright), `scripts/geo-feed.sh <lon> <lat> [seconds] &`. The side button is `adb shell input keyevent KEYCODE_STEM_1`. The crown is `adb shell input rotaryencoder scroll --axis SCROLL,<n>` on API 34 and 35. `connectedDebugAndroidTest` uninstalls the app and its tests wipe app data. macOS has no `timeout`.
- Screenshots kept in `docs/screenshots/` are named in each task; others are deleted after being read. Screenshots of removed screens are deleted with the screen.
- American English. Specs and docs state behavior and facts only; nothing that reads as policy or a promise on the owner's behalf.
- After each task: all unit tests and the instrumented suite pass on the API 35 emulator, `lintDebug` reports 0 errors, the task is committed, and the branch is pushed after review.

## Review Focus

Conditions the spec implies that are most likely to bite a wearer, each owned by a task:

1. A second button press while a save is already in progress, or a press on another screen: nothing is saved twice and nothing is saved from the wrong screen. Task 2.
2. A swipe that starts as horizontal and drifts, or a crown turn during a page change: the wearer ends on a whole screen with the crown and side button still working. Task 3.
3. Letting go of `Hold to delete` just before it completes, and dragging on the color wheel near where the hold area begins: nothing is deleted or recolored by accident. Task 4.
4. A database from the released-format version 1 opened by the new build: every waypoint survives the migration, and a backup made afterwards restores. Task 7.
5. Arriving at a crumb with a poor fix, or standing still on one: crumbs are consumed once each, in order, and a jittery position does not skip several. Task 7.

---

### Task 1: Line and arrow markers, and facing the target

**Files:**
- Modify: `app/src/main/java/com/infinite8looper/compass/nav/Navigation.kt`, `ui/CompassDial.kt`, `ui/CompassScreen.kt`
- Test: `app/src/test/java/com/infinite8looper/compass/nav/NavigationTest.kt`

**Interfaces:**
- Consumes: `TargetUi(dialAngleDeg, hue, focused)`, the shown heading state in `CompassScreen`, `shortestDelta`.
- Produces: `fun nextFacing(current: Boolean, shownHeadingDeg: Float, targetDialAngleDeg: Float?): Boolean` in `nav/` (enter within 2 degrees, leave beyond 3, false when the angle is null or either value is NaN); a facing state available in the compass screen's frame loop for later tasks (blind navigation) without recomposition.

- [ ] **Step 1: Write failing unit tests for `nextFacing`**: enters at 1.9, not at 2.1 from not-facing; stays at 2.9 once facing, leaves at 3.1; works across the 359/0 wrap in both directions; null angle and NaN give false.
- [ ] **Step 2: Implement `nextFacing`** and see the tests pass.
- [ ] **Step 3: Redraw the markers** per the spec's "Markers": every target a line across the ring; the focused target's line in its full color with a thin black casing, plus a triangle the same shape and size as the heading pointer, just inside the ring, pointing outward; every other target's line a soft tint of its own color at about 60 percent opacity with no casing; remove the dots. Keep the recorded-once rotating layers; markers still redraw only when the target list changes.
- [ ] **Step 4: Draw the facing state**: while facing, the focused target's line is drawn clearly thicker (about twice its normal weight), in the target's color; no ring or circle anywhere. Decide it in the frame loop with `nextFacing`; it must not recompose the screen per frame.
- [ ] **Step 5: Verify on both round sizes** with real rows and a real position feed: four targets in different directions with one focused; thirty targets; a target dead ahead (facing), at 2.5 degrees after having been facing (still thick), and at 4 degrees (normal weight); the focused arrow never hidden by another target's line. Compare with the review page's mockup "Lines and an arrow". Keep `targets-large.png`, `targets-small.png`, `many-targets-large.png` (replacing the old ones) and `facing-large.png`.
- [ ] **Step 6: Run the suites and lint, commit.**

### Task 2: One-press logging on the compass

**Files:**
- Modify: `CompassViewModel.kt`, `ui/CompassScreen.kt`, `ui/CompassApp.kt`, `sensors/LocationSource.kt` if needed
- Delete: `ui/LogScreen.kt`, the `log` route, `docs/screenshots/log-*.png`
- Test: `app/src/androidTest/java/com/infinite8looper/compass/CompassViewModelTest.kt`, `nav/NavigationTest.kt` for any pure part

**Interfaces:**
- Consumes: `acquireFix`, `LocationSource`, `logWaypoint`, the side-button key handling and the distance slot in `CompassScreen`.
- Produces: `sealed interface LogStatus { Idle; Saving(accuracyM: Float?); Saved(name: String, accuracyM: Float); Failed(reason: LogFailure) }` with `enum class LogFailure { NO_FIX, LOCATION_OFF, NO_PERMISSION, SAVE_FAILED }`; `CompassViewModel.logStatus: StateFlow<LogStatus>`; `CompassViewModel.logHere()` (starts a save unless one is in progress; safe to call from any screen that offers `Log here`); a pure `fun statusText(status: LogStatus, units: Units): Pair<String, String?>` giving the two lines to show.

- [ ] **Step 1: Write failing tests.** Unit: `statusText` for every state gives the spec's exact strings. Instrumented, with the real view model, stores and fed position: `logHere()` goes Saving then Saved and one row exists; two calls in quick succession save one row; with the permission check false it reports `NO_PERMISSION` and saves nothing; a fix older than the call is not used (construct this with real timestamps: a fix captured before `logHere()` must be rejected); the status returns to Idle by itself after the display time.
- [ ] **Step 2: Move the save into the view model** so it survives leaving the compass, with the same rules as before plus "never a fix from before the press". A failure of the database write gives `SAVE_FAILED`, never a crash, and `CancellationException` is not swallowed.
- [ ] **Step 3: Show it in the distance slot** per the spec: `Saving…` with accuracy; `Saved Waypoint N` with accuracy beneath for about 2 s and a light confirming tap; failures in red for about 3 s. The focused target's distance returns afterwards. With no waypoints and no save in progress the compass stays clean.
- [ ] **Step 4: Permission on first press**: request it from the compass screen with the real system dialog; if granted, continue the save without a second press; if denied, show `Needs location access`.
- [ ] **Step 5: Remove the logging screen and route**, their screenshots, and any test that existed only for them (say which).
- [ ] **Step 6: Verify on the emulator, both sizes**: a fresh install (permission dialog, grant, saved); a save with the feed running; `Location is off`; `No GPS fix` (press, then location off within a moment, as the earlier task did); a rapid double press saves once; a press on another screen does nothing. Confirm rows with sqlite. Keep `log-saving-large.png`, `log-saved-large.png`, `log-saved-small.png`, `log-failed-large.png`.
- [ ] **Step 7: Run the suites and lint, commit.**

### Task 3: Three wrapping screens

**Files:**
- Modify: `ui/CompassApp.kt`, `ui/CompassScreen.kt`, `MainActivity.kt` if needed
- Create: `ui/Instrument.kt` (shared drawing parts, started here with the pager-position ticks)

**Interfaces:**
- Consumes: `CompassScreen`, the existing `WaypointsScreen` and `SettingsScreen` (still in their old look until Tasks 4 and 5), routes for edit and calibration.
- Produces: a root that pages horizontally and wraps between Settings, Compass and Waypoints, starting on Compass; `@Composable fun PanePosition(index: Int)` (three ticks at the bottom, the current one blue) in `ui/Instrument.kt`; the compass screen's `onOpenList` replaced by a long-press that scrolls the pager to Waypoints.

- [ ] **Step 1: Read how Wear Compose 1.7.1 offers horizontal paging** (its `HorizontalPager` and how it interacts with swipe-to-dismiss and rotary input) and choose the construction that gives: wrap-around in both directions, whole-page snapping, no swipe-to-dismiss on the root, and the standard right-swipe back on pushed routes (edit, calibration). Say what you chose and why.
- [ ] **Step 2: Build it.** Swiping left from the compass shows waypoints, right shows settings, twice either way reaches the other. Long-press on the compass opens waypoints. Tapping the compass does nothing: remove the center tap area and the `onOpenList` tap.
- [ ] **Step 3: Input ownership.** The side button acts only when the compass is the settled page. The crown moves the target focus on the compass and scrolls the list on the other two. The vertical swipe on the compass still moves the focus and must not be stolen by, or steal from, the horizontal swipe. After any page change the newly settled page has the crown at once (the earlier focus bug must not return): verify with the crown and the side button after each of left, right, left-left, right-right, long-press, and returning from edit and from calibration.
- [ ] **Step 4: Leaving.** A right swipe never leaves the app from any of the three pages; the crown press (home) does. Verify.
- [ ] **Step 5: The per-frame loop and the hold-flat animation** run only while the compass is the visible page. Verify with frame statistics on each page.
- [ ] **Step 6: Verify on both sizes and on the API 33 emulator** (where the crown needs `adb root` and `sendevent`), reading screenshots mid-swipe and settled. Keep `panes-large.png` (three settled pages side by side, composed from three screenshots, or the three files `pane-settings-large.png`, `pane-compass-large.png`, `pane-waypoints-large.png`).
- [ ] **Step 7: Run the suites and lint, commit.**

### Task 4: Waypoints screen and one-screen edit, in the instrument look

**Files:**
- Modify: `ui/Instrument.kt`, `ui/WaypointsScreen.kt`, `ui/EditScreen.kt`, `ui/CompassApp.kt`, `CompassViewModel.kt`
- Delete: the `hue/{id}` route and `HueScreen`, `docs/screenshots/hue-*.png`, `edit-large.png`, `list-*.png` (replaced)
- Test: `nav/` unit tests for any pure part; `CompassViewModelTest` for new view-model behavior

**Interfaces:**
- Consumes: `NavUi`, `logHere()`, `setTarget`, `setAllTargets`, `rename`, `setHue`, `delete`, `PanePosition`.
- Produces in `ui/Instrument.kt`: `FrameRing` (the thin static ring of ticks, with optional scroll arc), `TickToggle` (long blue when on, short gray when off; a waypoint row uses its own color), a row layout with a 48 dp hit height, and the instrument colors as named values. Pure: `fun holdProgress(elapsedMs: Long, holdMs: Long = 1000): Float` clamped 0..1, and `fun headerText(targets: Int, total: Int): String` (`3 of 4 targets`, `No waypoints yet` and so on).

- [ ] **Step 1: Unit tests first** for the pure parts, including the boundaries of `holdProgress` and every case of `headerText` (0 of 0, 0 of n, n of n, 1 of 1).
- [ ] **Step 2: Shared parts** in `ui/Instrument.kt`, drawn per the spec's "Visual language". No Material pill buttons on these screens.
- [ ] **Step 3: Waypoints screen** per the spec: header (tap sets all, or clears all when all are set), `Log here`, one row per waypoint with its tick, name and distance (marked `(old)` when stale, as now); tap toggles the target; long-press opens edit; the frame shows scroll position; the crown scrolls.
- [ ] **Step 4: Edit as one screen** per the spec: the frame is the color wheel (drag along it or turn the crown; saved as it changes, with writes kept sensible while dragging); the name in the middle opens the system text input; the target tick toggles; `Hold to delete` deletes after about a second with the frame draining, and letting go cancels; no dialog; the screen leaves once when its waypoint is gone; a right swipe that does not start on the wheel goes back.
- [ ] **Step 5: Verify on both sizes with real data**: empty list; a few; thirty rows scrolling; all and none through the header; toggling; edit: recolor by drag and by crown and see the compass line change; rename (empty input keeps the old name); hold to delete, and let go at about 80 percent (nothing deleted); a hold that begins on the wheel does not delete; back gesture. Compare with the mockups "Waypoints" and "Edit". Keep `waypoints-large.png`, `waypoints-small.png`, `waypoints-empty-large.png`, `edit-large.png`, `edit-small.png`, `edit-deleting-large.png`.
- [ ] **Step 6: Run the suites and lint, commit.**

### Task 5: Settings and calibration in the instrument look, with the two new settings

**Files:**
- Modify: `data/SettingsStore.kt`, `CompassViewModel.kt`, `ui/SettingsScreen.kt`, `ui/CalibrationScreen.kt`
- Test: `SettingsStoreTest.kt`; `nav/` unit tests for any pure part

**Interfaces:**
- Consumes: the shared parts from Task 4.
- Produces: `Settings.blindNavigation: Boolean` (default TRUE, by the owner's instruction) and `Settings.breadcrumbTrail: Boolean` (default false) with setters, exposed through `NavUi` and the view model in the existing pattern; pure `fun accuracyQuarters(accuracy: Int?): Int` (0, 1, 3, 4 for unreliable or unknown, low, medium, high).

- [ ] **Step 1: Tests first**: defaults, round-trip and survival across reopening for both settings (real DataStore file); the corrupt-file test still asserts the backup switch is off and now also the two new defaults; `accuracyQuarters` for null and 0 to 3.
- [ ] **Step 2: Storage and view model** for the two settings. Neither feature is built in this task.
- [ ] **Step 3: Settings screen** in the instrument look, switches in the spec's order, one tap each, then Calibrate (showing the current accuracy word) and the version. The Show coordinates permission behavior is unchanged (asks when turned on; returns to off with `Needs location access` if denied).
- [ ] **Step 4: Calibration screen** per the spec: frame fills green by quarters; accuracy word large; field strength small; interference warning in red; it must not recompose at sensor rate (as fixed before).
- [ ] **Step 5: Verify on both sizes**: every switch toggles and persists across a force-stop; the list top, middle and end with nothing clipped; the coordinates permission paths with the real dialog; calibration normal and with the field set to 200 (interference). Compare with the mockups. Keep `settings-large.png`, `settings-end-large.png`, `settings-small.png`, `settings-end-small.png`, `settings-needs-access-large.png`, `calibrate-large.png`, `calibrate-small.png`, `calibrate-interference-large.png`, `calibrate-interference-small.png` (all replacing the old ones).
- [ ] **Step 6: Run the suites and lint, commit.**

### Task 6: Blind navigation

**Files:**
- Modify: `nav/Navigation.kt`, `ui/CompassScreen.kt`
- Test: `nav/NavigationTest.kt`

**Interfaces:**
- Consumes: `nextFacing` and the facing state from Task 1, `NavUi.blindNavigation`, the tick feedback's haptic path.
- Produces: a pure decision, for example `fun blindPulse(state: BlindState, facing: Boolean, nowMs: Long): Pair<BlindState, Pulse?>` with `enum class Pulse { LOCK, HEARTBEAT }`: `LOCK` once on becoming facing, `HEARTBEAT` every 1000 ms while still facing, nothing otherwise, and a fresh `LOCK` only after facing was lost.

- [ ] **Step 1: Unit tests first**, with concrete timelines: turn onto the target (one LOCK), hold for 3.5 s (three HEARTBEATs), turn away (nothing), return (LOCK again); jitter at the facing edge handled by Task 1's hysteresis gives no repeated LOCKs; long gaps between frames give at most one HEARTBEAT per frame.
- [ ] **Step 2: Implement and wire it** in the frame loop. The strong pulse is clearly stronger than a tick and the heartbeat is softer than the lock: choose from the platform's haptic feedback constants by API level (33, 34, 35), say which and why. No vibration permission.
- [ ] **Step 3: Silent** when the setting is off, when there is no focused target with a bearing, while hold-flat applies, and while the compass is not the settled page.
- [ ] **Step 4: Verify on the emulator** with a target and `scripts/set-heading.sh` (evidence from what the system records, or a temporary counter removed before commit): one LOCK on turning onto the target, heartbeats while held, none when turned away, none with the setting off; and on the API 33 emulator. The feel is a watch-checklist item; do not claim it.
- [ ] **Step 5: Run the suites and lint, commit.**

### Task 7: Breadcrumb trail

**Files:**
- Modify: `data/Waypoint.kt` (database version 2), `data/SettingsStore.kt`, `CompassViewModel.kt`, `nav/Navigation.kt`, `nav/Targets.kt`, `ui/CompassDial.kt`, `ui/CompassScreen.kt`, `ui/WaypointsScreen.kt`, `app/build.gradle.kts` (schema export), `data/SettingsBackupAgent.kt` only if the guarantees need it
- Create: `data/Crumb.kt` if separation helps, `app/schemas/` (exported schemas)
- Test: unit tests in `nav/`; `WaypointDatabaseTest`, `DeletedWaypointTest`, `CompassViewModelTest`, and a new migration test

**Interfaces:**
- Consumes: the side-button handling, `logHere()`'s fix rules, `hasArrived`, markers from Task 1, `NavUi.breadcrumbTrail`.
- Produces: pure `fun pressKind(...)` distinguishing a single from a double press with a 300 ms window (single reported only after the window passes when the trail setting is on; at once when it is off); `enum class TrailMode { OFF, LAYING, RETRACING }` and a pure transition function for double press, crumb dropped, and arrival; a crumbs table and DAO; `NavUi.trail` carrying the mode, the count and the crumb being walked to.

- [ ] **Step 1: Unit tests first** for the pure parts: press timing (one press; two within 300 ms; two at 301 ms; three quick presses; setting off means every press is single and immediate); every mode transition in the spec, including double press while retracing returning to laying; arrival consumes exactly one crumb, in newest-first order; a position that jitters across the 10 m line consumes one crumb, not two; arriving at the last crumb ends the trail; an empty trail cannot be retraced.
- [ ] **Step 2: Database version 2 with a real migration.** Turn on schema export, commit the version 1 and version 2 schemas, write the migration that adds the crumbs table, and test it with Room's migration test support against a real version 1 file containing waypoints: all waypoints survive unchanged. No destructive fallback. Confirm the sanitized-record and deleted-content guarantees still hold: extend `DeletedWaypointTest` so a deleted crumb's coordinates do not remain in any file's bytes (crumbs have no name; search for a distinctive coordinate encoding and say how).
- [ ] **Step 3: View model and storage** for the mode and the trail, surviving a restart. Location runs while laying or retracing with the compass in view; the screen stays awake while retracing.
- [ ] **Step 4: Compass** per the spec: `Trail` and the count while laying; crumbs as short muted ticks; while retracing, the current crumb drawn and labeled like a focused target, the waypoint focus suspended; `Trail complete` briefly at the end. The one-press status line from Task 2 shows a dropped crumb the same way it shows a saved waypoint.
- [ ] **Step 5: Waypoints screen**: the trail row, with the same actions as the double press (start, retrace, back to laying) and long-press to clear, so the feature works on a watch that does not deliver a double press.
- [ ] **Step 6: Verify on the emulator, both sizes**, moving the position feed: setting off (double press is two saves at most one of which happens per Task 2's rule; say what happens); setting on: start, drop five crumbs at five positions, retrace to each in turn by moving the feed, see each consumed in order, `Trail complete`; restart the app mid-trail; clear the trail. Do a backup and restore with a trail present (temporary build without the encryption attribute, as earlier tasks did, restored afterwards) and say what came back. Keep `trail-laying-large.png`, `trail-retracing-large.png`, `trail-retracing-small.png`, `trail-row-large.png`.
- [ ] **Step 7: Run the suites on API 33, 34 and 35, lint, commit.**

### Task 8: Coordinates readout and scrolling names

**Files:** as in the brief below.

This task's requirements are the brief at `.superpowers/sdd/2026-10-11-wear-compass/task-10C-brief.md`, copied into this plan's workspace by the controller, with these changes: the screens are the redesigned ones (the name appears on the compass center, the waypoint rows, and the edit screen's middle; there is no delete dialog and no separate color screen); coordinates must also coexist with the one-press status line, the trail labels and the hold-flat animation; names on the compass also scroll for `Saved Waypoint N` only if that line does not fit.

- [ ] **Step 1: Unit tests first** for the coordinate formatter and the marquee offset function, as the brief lists.
- [ ] **Step 2: Implement both**, as the brief describes, on the redesigned screens.
- [ ] **Step 3: Verify on both sizes** as the brief lists, plus the combinations above. Keep the screenshots the brief names, with `marquee-edit-large.png` in place of the dialog.
- [ ] **Step 4: Run the suites and lint, commit.**

### Task 9: Documentation

**Files:**
- Modify: `README.md`, `docs/superpowers/specs/2026-10-11-wear-compass-design.md` (a note at the top pointing to the v2 document, and removal of statements v2 makes false)
- Create: `docs/TESTING.md`, `scripts/test-totals.sh` (or `.py`)

- [ ] **Step 1: README**: what the app does and how to use it, as built: the three screens and how to move between them, one-press saving, targets, the trail, blind navigation, the settings, backup (what is included, the switch, that the system does the backing up, that cloud backup needs a screen lock), supported Android versions, building. A table of gestures. One screenshot. Facts only: no promises, no policies, no support or privacy statements.
- [ ] **Step 2: TESTING.md**: how to run the unit and instrumented suites on each emulator, what needs the position feed, which tests are emulator-only and why, that instrumented tests wipe app data and must not be run on a watch holding real waypoints, and how screenshots are taken.
- [ ] **Step 3: A totals script** that reads per-class counts from the unit and instrumented result XML and prints them with sums, so reports stop miscounting. Run it and use its output in the report.
- [ ] **Step 4: Check every command and link in both documents by running or opening it. Commit.**

### Task 10: End-to-end emulator pass and screenshot matrix

**Files:**
- Create: `docs/screenshots/README.md` (the index), the full set of screenshots, committed helper scripts under `scripts/` for what was repeatedly done by hand (switching emulators and running the suite, logging a waypoint at a position, driving and dumping the UI)

- [ ] **Step 1: Suites** on API 33, 34 and 35, with totals from the script; `lintDebug`; `assembleRelease`, then install the minified release build and smoke-test it (compass, save, list, edit, settings), fixing any keep-rule fault.
- [ ] **Step 2: The screenshot matrix.** Every screen in every state and mode, on both round sizes, each image read and judged against the spec and the mockups, with an index listing each state, its file and how it was produced. At least: compass (clean; `MAG`; true north; one focused target at distance; facing; a long name scrolling, two positions; `Arrived at target`; stale; `No target`; many targets; hold-flat with and without a target, several frames; coordinates on with a fix, locating and stale; saving, saved and each failure; metric and imperial; trail laying, retracing and complete), waypoints (empty, few, many, old distances, trail row), edit (short and long name, mid-recolor, mid-hold), settings (top, middle, end, each switch both ways, needs-access), calibration (normal, interference), the permission dialog, and the three panes. Animated states get several frames.
- [ ] **Step 3: Screen recordings** for the owner, left outside the repository in the session's review-media folder: the hold-flat loop; the dial sweeping; a swipe through the three screens; a one-press save; a hold-to-delete; a name scrolling.
- [ ] **Step 4: Re-check the loose observations** from earlier tasks now that nobody else is touching the emulator: the heading's settle time after `set-heading.sh`; the time for a distance to be marked old; a back swipe from the very edge on a pushed screen.
- [ ] **Step 5: Backup and restore** end to end on API 33, 34 and 35 with the local transport (temporary build without the encryption attribute, restored and verified afterwards): switch on restores waypoints and settings; switch off restores nothing; a deleted waypoint's name is in no stored or restored bytes.
- [ ] **Step 6: List what an emulator cannot show** in the index, as owed to the physical watch: low sensor accuracy and the calibrate prompt; whether single and double presses of the side button reach the app, and from a dimmed screen; every haptic's feel; real GPS behavior; the encryption requirement on Google's backup; device-to-device transfer.
- [ ] **Step 7: Commit.**

### Task 11: Accent and north colors from the watch face

Run this task after Task 5 and before Task 8. It is numbered 11 only because it was added after the plan was written.

**Files:**
- Modify: `ui/Instrument.kt` (the one named place the accent comes from), the app's theme setup in `ui/CompassApp.kt`, any screen that still names the blue directly
- Test: unit tests for any pure part

**Interfaces:**
- Consumes: the accent as defined in `ui/Instrument.kt` by Task 4.
- Produces: three named colors used everywhere in place of literals: the accent (scheme primary, else `#2F8BFF`), the north color (scheme tertiary, else `#FF3B30`), and the error color for failures and deleting (scheme error, else `#FF3B30`).

- [ ] **Step 1: Establish the facts before writing code.** Read the current Wear Compose Material 3 documentation for dynamic color (whether a dynamic color scheme function exists in 1.7.1, what it returns when the device has none, and from which Wear OS version a watch provides one). Nobody has verified this yet; the owner believes "the newer wearos supports that". Report what the documentation says with its URL. If the library does not offer it, check the platform's own dynamic color resources (`android.R.color.system_accent1_*`), and say which route is used.
- [ ] **Step 2: Get an emulator that provides a scheme.** Install the newest Wear OS system image available for this Mac's architecture (the owner has authorized installing what emulator testing needs), create an AVD, and find out how to change the watch face or its color so the scheme changes. Run the whole suite there too and report the result, since it is a new API level for the app.
- [ ] **Step 3: Implement.** The accent, the north color and the error color follow the watch where a scheme exists and fall back to the blue and the red elsewhere. The north line on the dial and every other use of north's color come from the north color. Check two things with pure, unit-tested functions: contrast (a color taken from a watch face may be dark or low in saturation; if it does not read against black at the size it is drawn, lighten it to a minimum contrast and say what rule you used), and separation (north and the pointer must be plainly different from each other; if the scheme's tertiary is too close to its primary in hue and lightness, pick another role from the scheme or shift it, and say what rule you used). Waypoints keep their colors, and the soft tint is unaffected.
- [ ] **Step 4: Verify** on the new emulator with at least two different watch-face colors (compass, waypoints, settings and edit, both read from screenshots, checking that north is still instantly findable on the dial), and on API 33, 34 and 35 that the blue and the red are unchanged. Keep `accent-a-large.png`, `accent-b-large.png`.
- [ ] **Step 5: Spec**: add the supported-versions facts found. Run the suites and lint, commit.

