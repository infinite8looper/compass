# compass

A compass for Wear OS that also saves GPS positions as waypoints and shows the direction and distance to each one on the dial.

![The compass screen on the Wear_OS_Large_Round emulator, with waypoints as lines on the ring and the focused one named in the middle](docs/screenshots/compass-targets-large.png)

The picture is the compass screen on an emulator with three waypoints, a simulated position, and the heading at 290 degrees. The screens and states an emulator can produce are pictured in [docs/screenshots](docs/screenshots/README.md), which also lists the ones it cannot. Everything in this file was seen on emulators. A debug build (commit becfa3e) was run by the owner on a Pixel Watch 3 (API 37) on 2026-10-13.

## The three screens

Settings, the compass and the waypoints are three screens side by side in one row that wraps around. The app opens on the compass. A swipe left from the compass shows the waypoints, a swipe right shows settings, and a second swipe the same way reaches the other one. Turning the row leaves a list where it was scrolled to. Three ticks show which screen is in view: at the bottom edge on settings and waypoints, and on the compass for about a second around a swipe.

| Where | Gesture | What happens |
|-|-|-|
| Any of the three | Crown press | Leaves the app (the system's crown press) |
| Any of the three | Back | Moves one screen toward settings (compass to settings, settings to waypoints, waypoints to compass); never leaves the app |
| Compass | Double tap | Saves the current position as a waypoint (or drops a crumb while a trail is being laid) |
| Compass | Double pinch, on a watch that has the one-handed gestures (Wear OS 7 and newer) | The same as a double tap |
| Compass | Side button, single press, on a watch with an assignable button | The same as a double tap |
| Compass | Side button, double press, on a watch with an assignable button | With `Breadcrumb trail` on: starts a trail, or switches between laying and retracing |
| Compass | Swipe up or down, or turn the crown | Moves the focus among the targets |
| Compass | Long press | Opens the waypoints screen |
| Compass | Tap | Nothing |
| Waypoints | Side button, on a watch with an assignable button | As on the compass |
| Waypoints | Tap `Log here` | Saves a waypoint |
| Waypoints | Tap a row | Sets or clears that waypoint as a target |
| Waypoints | Long press a row | Opens its edit screen |
| Waypoints | Tap the header | Sets all waypoints as targets, or clears all if all are |
| Waypoints | Tap the `Trail` row | With `Breadcrumb trail` on: starts a trail, or switches between laying and retracing |
| Waypoints | Hold the `Trail` row | Clears the trail (`Hold to clear`) |
| Waypoints | Tap `Drop crumb` (shown while a trail is being laid) | Drops a crumb, as a double tap on the compass does then |
| Edit | Drag along the color ring, or turn the crown | Changes the waypoint's color |
| Edit | Tap the name | Opens the system's text input to rename it |
| Edit | Tap the `Target` tick | Sets or clears it as a target |
| Edit | Hold `Hold to delete` for about a second | Deletes the waypoint and closes the screen |
| Edit, calibration, check | Right swipe, or Back | Returns to the screen it was opened from |
| Settings | Tap a row | Switches it |

A double tap is two taps within the system's double-tap time (timed by when the touches happened, not by when the app handles them), no further apart than the system's double-tap distance, with the compass at rest in view at both. One tap does nothing and gives no vibration. A tap followed by a held touch is a long press, and a tap followed by a swipe is a swipe. While the `Calibrate` prompt shows, taps on its area open calibration and save nothing.

The double pinch is the primary action of the one-handed gestures in the Wear Compose library (`Modifier.oneHandedGesture`). The app registers it on API 37 and above, and only while the compass is at rest in view. It was not tried: no emulator has the gesture service (`~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell cmd IWearGestureService gesture DoublePinch` answers `Can't find service` on the API 35 and the API 37 images), and the library's documentation says it does nothing where the system lacks the framework. When the gesture arrives the library gives a vibration of its own.

The side button is handled only on a watch that describes a button an app can use: `WearableButtons` reports more than one button (or no count) and describes one of the keys `KEYCODE_STEM_1`, `KEYCODE_STEM_2` and `KEYCODE_STEM_3`. This is asked once per start of the app. On a watch that describes none, the app looks at no key event, nothing waits for a second press, and the `Breadcrumb trail` row in settings does not mention a press. The emulator images at API 33, 34, 35 and 37 report no button at all (a count of 0), so on them the side keys do nothing; `KEYCODE_STEM_1` sent with `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell input keyevent` is ignored. On a watch that does describe one, the side button does nothing on settings, edit and calibration, and with `Breadcrumb trail` on a single press is acted on 300 ms after it is let go, when no second press has followed.

A screen reader is given saving as an action of the dial named `Save waypoint here`.

## Compass

- The dial turns so its north points to north: fine ticks every 2 degrees, heavier ticks every 30, degree labels outside the ticks, N, E, S and W inside, a north tick, and a fixed pointer at 12 o'clock. The heading is shown as a whole number in the middle. `MAG` shows when the dial is on magnetic north.
- True or magnetic north is a setting. True north uses the declination for the last known position; with no position ever known, the dial shows magnetic north and `MAG` until the first position comes. With true north selected, the location permission held and no position ever stored, the app asks for a position at once, on any of the three screens. Switching `True north` on without the permission opens the system's permission dialog, once for that switch; if it is declined the setting stays on and the dial stays magnetic with `MAG`.
- When the watch is tilted steeply (it begins past 70 degrees and ends below 60), the heading is not taken: the dial dims and a looping drawing of a watch turning level appears with `Hold flat`.
- The `Calibrate` prompt appears when the magnetometer reports low or unreliable accuracy, or when the rotation vector's estimated heading error is above 30 degrees (`LARGE_HEADING_ERROR_DEG`), and opens the calibration screen. Once shown for a large error, it goes only when the error is below 25 degrees (`largeHeadingError`), so an error near 30 degrees does not put it on and off. The emulator images give no such estimate, so there it comes from the level alone.
- With `Show coordinates` on, the latitude (for example `43.7022° N`) and the longitude (`72.2896° W`) are two lines together in the lower half of the readout, under the distance; the `Calibrate` and `Hold flat` prompts and a status's second line (such as `±16 ft` after a save) move to the top of the readout, above `MAG`. While neither coordinate row shows anything (after a press that failed with no position known, or without the permission), a status's second line stands right under its first instead, as with `Needs access to` / `precise location`. They are not shown in the hold-flat state or without the location permission. Before a position is known the latitude is absent and the longitude's place shows `Locating…`, or `Location is off` with location switched off, unless the distance's row or a logging result already says why there is no position.
- A waypoint name that does not fit slides back and forth in its place. With the system's animations removed it ends in an ellipsis.
- The heading sensor runs while the compass is on screen or calibration or the check screen is open, and stops about 2 seconds after.
- The heading comes from the fused rotation vector (the gyroscope steered by the magnetometer), as before. The app can also work it out from the magnetometer and gravity alone (the geomagnetic rotation vector where the watch has one, which every emulator image does, else `SensorManager.getRotationMatrix` from the accelerometer and the magnetometer), and as a blend that follows the fused heading from moment to moment and is steered toward the magnetic one with a time constant of 5 seconds (`HeadingBlend` in `nav/Navigation.kt`). While the fused heading drifts at a steady rate, the blend lags the magnetic heading by that rate times the time constant: 3 degrees at the 0.6 degrees a second `HeadingBlendTest` uses, where it stays within 5 degrees of the true heading through thirty turns each way; a faster drift gives a larger lag. Which one the compass uses can be switched for the running app, with the first row of the check screen (`CompassViewModel.setHeadingKind`); each start is on the fused one, and the switch is not stored. The sensors the magnetic heading needs are registered only while it or the blend is in use, or while the check screen is open, which shows all three. On a watch with neither the geomagnetic rotation vector nor the accelerometer (for that method), the compass stays on the fused heading when the magnetic one or the blend is asked for (`headingKindInUse`), and the check screen's row says `Fused` with `Magnetic unavailable` or `Blend unavailable` under it.
- With `Tick feedback` on, the dial gives one of three vibrations as the pointer passes over something: a light one for a fine tick, a deeper one for a heavier tick (every 30 degrees, N, E, S and W among them), and two knocks, in strength between the two, for the direction of the focused target. With `Blind navigation` on, the target's direction gives none of its own: the blind navigation pulse is its vibration then. The target's direction also gives none while the position is old (a fix more than 20 seconds old), as blind navigation is silent then, and only the pointer's turning passes it: a new position that moves the direction past a still pointer gives nothing. One frame gives one vibration, the target's before a heavier tick's before a fine tick's. None plays while the dial is catching up with a new heading or within 400 ms of a blind navigation pulse (150 ms of a soft one). A tick plays at most once every 40 ms, and not while the target's vibration lasts (at least 150 ms), and the target's does not play again while it lasts; the target's plays even just after a tick, which it cuts short. Where the target's vibration is played as `CONFIRM`, the app takes it to last 300 ms, since it cannot ask a haptic feedback constant's length. That is the length of `CONFIRM` the system recorded on the emulator images (`dumpsys vibrator_manager`, the app's entries for the constant, 2026-10-16): 301 to 310 ms on API 33 (7 entries), 303 to 315 ms on API 35 (`Wear_OS_Large_Round`, 10) and 301 to 308 ms on API 37 (27), from the start of the two thuds to their end. Where the watch's vibrator supports the composition primitives (asked with `Vibrator.areAllPrimitivesSupported`, once, off the main thread), the three are played as primitives: `TICK` at 0.5, `THUD` at 1.0, and two `CLICK`s at 0.7, 50 ms apart; where it does not, as the system's haptic feedback `SEGMENT_FREQUENT_TICK` (`CLOCK_TICK` on API 33), `GESTURE_START` and `CONFIRM`. On the emulator images these constants play the texture tick (the tick effect on API 37), the click effect and two thuds, which differ from each other and from the press's; a watch's system can map them to other effects. `CONFIRM` is also the constant of a save's confirming vibration and of blind navigation's lock pulse, so where the primitives are not supported the target's vibration is the same as those. The table is in `ui/Vibrations.kt`, with the save's, blind navigation's and the long press's vibrations. The haptic feedback constants follow the watch's touch-vibration setting; the primitives are played with the touch usage, and whether that setting silences them was not checked. How different they feel on a watch was not tested.

## Saving a waypoint

A double tap on the compass saves the current position without leaving the screen, and so does `Log here` on the waypoints screen. Every press that saves (the double tap, the dial's accessibility action, a side button where there is one, `Log here`, `Drop crumb`) is answered with a vibration (the system's `VIRTUAL_KEY` haptic feedback) before anything else; the double pinch is not, since the gesture library plays its own. The status under the heading then reads `Saved Waypoint N` in green with its accuracy and a confirming tap (the system's `CONFIRM`), which waits until 250 ms after the press's vibration is asked for so as not to cut it short (the press's length and the time the system takes to start it, with room), or a failure in the error color: `No GPS fix`, `Location is off`, `Needs access to precise location`, or `Couldn't save`.

- With a position in hand that is not older than 10 seconds at the press (the latest position the app's own location updates gave, or, without one that recent, the location provider's last known position, asked for at the press and waited for at most 500 ms), that position is written at once with the accuracy it states, however poor, and the status says saved. On the API 35 emulator the row was written 6 to 50 ms after the press. For 10 seconds after the press, while the compass or the waypoints screen is on screen, the app listens for positions once a second (Home, the screen going off, settings or a screen opened from a row end that at once), and a position stating a smaller error replaces the waypoint's position and accuracy in place, once or more. The status keeps the accuracy that was first written. It stops for a waypoint that has been deleted, or whose position has been changed, and never writes a deleted one back. A new name or color stays.
- With no such position in hand the status reads `Saving…` with the accuracy, a fix with accuracy of 15 m or better taken after the press is saved, and after 20 seconds the best fix taken after the press is saved, or `No GPS fix` is shown when there is none.
- In the debug and watch builds each save writes one line to the log (tag `CompassSave`): milliseconds from the press to the row written, the age and accuracy of the position used, and whether a more accurate one replaced it. No coordinates.
- The first press asks for precise and approximate location; if only approximate is granted, nothing is saved and `Needs access to precise location` shows.
- New waypoints are named `Waypoint N`, are targets, and get a color automatically.

## Targets

- Any number of waypoints can be targets at once. Each target is a line across the ring at its bearing. The focused target (shown by name, with its color's dot) has its full color, a casing, and an arrow like the pointer's; the others are soft tints at 60 percent opacity.
- A waypoint keeps one stored number from 0 to 360, its place, and its color depends on the `Colors` setting. With `Standard` (the default) the place is a hue on the full color wheel, at the saturation waypoints had in v2 (0.65) and full brightness; the pointer is blue (`#2F8BFF`), north and the error color are red (`#FF3B30`), and nothing is read from the watch for them. New waypoints are given places from a numbering 137.508 degrees apart (the golden angle) that starts at 108.3, the hue furthest from both the red and the blue (a green). A place within 25 degrees of the red or the blue is passed over, and of the next eight places left the one furthest from the waypoints already there is given, so a new waypoint is never within 25 degrees of north's red or the pointer's blue and is not put beside an older one where there is room. A waypoint keeps the place it was given, and a color set by hand is kept as set. With `Watch face` the place is a color on a ring that fades between three anchors at least 60 degrees of hue apart: the accent (the pointer), the north color, and the error color where it is that far from both, otherwise a color made in the widest gap of hue between the other two. The ring is read turned so that the first new waypoint's place (108.3) is the third anchor, the accent at 228.3 and north at 348.3: a new waypoint starts away from both, and since the places skipped for the red and the blue hold the accent's and north's places, no new waypoint is read within 10 degrees of place of either. With `Standard` that first place is a yellow-green/green (`#7AFF59`). These come from the system's color scheme, as do the ink and the grays (see the setting below).
- The distance is under the heading, in meters and kilometers or feet and miles. A target under 10 m (30 ft) away shows `Arrived at target` and has no line or arrow; it counts as left again only beyond 12 m (36 ft). A distance from a fix more than 20 seconds old is marked `(old)`.
- When the heading is within 2 degrees of the focused target (held until it is more than 3 degrees off), its line is drawn thicker at 12 o'clock.
- While a target exists, the location permission is held and the app is on screen, position updates come about every 5 seconds, on any of the three screens. The screen stays awake while a target exists and the compass is in view, with or without a position.
- With the location permission held, position updates also run about every 5 seconds while the compass or the waypoints screen is on screen (at rest, or passing in a swipe), with or without a target, so that a press nearly always finds a position in hand. They stop on settings, under the edit and calibration screens, at Home and with the screen off. This does not keep the screen awake. It costs battery while the app is open; how much was not measured.
- Targets and the focus are stored and survive closing the app.

## Blind navigation

On by default. When the heading comes within 2 degrees of the focused target there is one strong haptic pulse, then a soft one about once a second while the wearer keeps facing it. It is silent in the hold-flat state, while the compass is not the screen in view, with no focused target, and while the position is old (a fix more than 20 seconds old). The pulses are the system's haptic feedback calls (`CONFIRM` and `CONTEXT_CLICK`), which need no permission; they follow the watch's touch-vibration setting. Their feel on a watch was not tested.

## Breadcrumb trail

On by default (`Breadcrumb trail` in settings), also for an install that never stored the switch and after a restore that brought no value for it. A switch that was turned off stays off. With it on:

1. A tap on the `Trail` row of the waypoints screen with no trail starts one; the compass shows `Trail` and the crumb count.
2. While laying, each double tap on the compass drops a crumb at the current position, with the same fix rules as saving a waypoint, and so does a tap on the `Drop crumb` row, which the waypoints screen shows under the `Trail` row while a trail is being laid. Waypoints can still be saved from `Log here`.
3. A tap on the `Trail` row switches to retracing: the newest crumb is shown like a focused target (`Crumb N`) with its direction and distance.
4. A crumb is deleted and the next newest takes over when a fix of 15 m or better is within 10 m (30 ft) of it while the compass is the screen at rest in view and the app is in front. After the last, `Trail complete` shows and the trail is off.
5. A tap on the `Trail` row while retracing returns to laying with the remaining crumbs.

On a watch with an assignable side button, a double press does what a tap on the `Trail` row does, and a single press what a double tap does.

Crumbs are stored separately from waypoints, are never listed with them except as the one `Trail` row, and survive closing the app. While a trail is retraced the waypoint focus is suspended and returns afterwards.

## Settings

| Row | Default | Smaller line |
|-|-|-|
| True north | on | `Off: magnetic north` |
| Feet and miles | from the watch's locale | `Off: meters and km` |
| Tick feedback | on | `Faint tap at each tick mark` |
| Blind navigation | on | `Pulses when facing the target` |
| Breadcrumb trail | on | `Trail row on waypoints`; on a watch with an assignable side button, `Double press starts a trail` |
| Show coordinates | off | `GPS position on the compass` |
| Colors | `Standard` | `Standard` or `Watch face`, the choice as it stands; `Not available on this watch` where the system gives no color scheme |
| Back up waypoints | on | `With this watch's backup` |

`Colors` is a row, not a switch: one tap gives the other choice, and a screen reader says what the tap does (`Use watch face colors` or `Use standard colors`). It is backed up with the other settings, and a value that cannot be read is `Standard`. With `Watch face`, on API 35 and newer while the system's dynamic color setting (`dynamic_color_theme_enabled`) is 1, the Wear Material 3 library gives the system's color scheme: the pointer and the words that can be tapped take its primary, north its tertiary and the error color its error color, after the contrast checks of `nav/WatchColors.kt` (each reads on black at 4.5 to 1; a primary with less than 0.30 of saturation but at least 0.10, below which it is taken for a gray and not used, is deepened to 0.30 and kept; north is kept 60 degrees of hue from the pointer and at least 0.5 of saturation). The ink, the secondary text, the faint ticks and the dial's numbers take the hue and saturation of the scheme's `onSurface`, `onSurfaceVariant` and `outline`, the saturation held under 0.10, at the least brightness at which each has at least the contrast with black of the app's own color for that role; a dark hue that cannot read that well at full brightness gives up saturation instead. The background stays black, and the confirming green and the breadcrumb color do not change. Where the library gives no scheme, the row says `Not available on this watch`, takes no tap, and the colors are those of `Standard`; a stored `Watch face` is kept for when a scheme is given. No emulator image switches the system setting on by itself, and on the emulators the watch face's own colors did not change the scheme. The app looks at whether a scheme is given, and at its colors, when it comes on screen and when the setting is changed: a change of the system's scheme while the app is on screen is followed the next time it comes on screen.

`Calibrate` shows the accuracy the magnetometer last reported (`High`, `Medium`, `Low`, `Unreliable`) and opens the calibration screen: the words `Turn your wrist slowly in every direction`; a ring of 24 segments with `Covered` and the share of them lit (`50%`) to its left and, when the rotation vector gives one, `Error` and the estimated heading error in whole degrees (`±12°`) to its right; the accuracy level; the field strength in microtesla; a frame that fills by quarters as accuracy improves; and a warning with the frame filled in the error color while the field is below 25 or above 65 microtesla. Each segment of the ring is a region of the directions the magnetometer's field can point in, in the watch's own frame (`nav/Calibration.kt`): three rings (the field through the back of the watch outermost, across its face, out of its face innermost) of eight sectors, placed by the direction of the field across the face. A segment lights when the field has pointed there since the screen opened, whatever the field's strength: a field of a strength the earth's cannot have lights its segment too, with the warning and the red frame. A screen reader reads `Covered` and the share as one, and announces a change of the share. The ring shows the motion covered, not the accuracy. The screen is kept on for two minutes from its opening or from the last segment that lit, whichever is later. The last line shows the version and `MIT License`.

In the debug and watch builds the calibration screen has a row at the bottom, `Check`, that opens the check screen; the release build has neither the row nor the screen (`checkRoute` in `nav/Check.kt`; R8 leaves none of the check screen's code or words in the release APK: its classes are absent from the release build's R8 mapping and its words from the release `classes.dex`). The check screen is a list: `Heading source` (`Fused`, `Magnetic` or `Blend`; a tap switches the compass to the next for the running app); the three headings (`Fused heading`, `Magnetic heading`, `Blend heading`), the estimated heading error, the level and the field strength, live; under `Watch colors`, the six colors of the scheme the library gives (primary, tertiary, error, on surface, on surface variant, outline, each with a dot of it), or `None`; under `Inputs`, the count of buttons and the side keys `WearableButtons` reports, whether one is a button the app acts on, whether the system lists the Wear SDK library (`wear-sdk`), whether the app can reach it, and whether the SDK says the watch detects the one-handed gestures (`com.google.wear.feature.GESTURE_DETECTION`, the question the Wear Compose library asks before it registers the double pinch); under `Vibrations`, whether the vibrator supports each of the eight composition primitives, and `Play thin line`, `Play thick line` and `Play target`, which play the dial's three vibrations as the dial plays them and say whether they play as `Primitives` or as a `Constant` here. On the API 35 emulators the Wear SDK is listed and reached and gesture detection is not reported. While it is open the check screen keeps the display on, for two minutes from its opening or from the last touch or turn of the crown on it, whichever is later; calibration's two minutes count again on the return to it, and its lit segments are kept across the visit.

The text of the settings, the waypoints list, the edit screen, the calibration screen and the check screen follows the system's font size (on the emulators the setting has six steps, from 0.94 to 1.24; a scale outside 0.85 to 1.24 is drawn at the nearer end). The compass's text does not: it is sized from the dial.

A tap on `Back up waypoints` while it is on opens a question (`Turn off backup?`, with `Keep on` and `Turn off`); Back and a right swipe leave it on.

Turning on `Show coordinates` without location access asks for it; if precise location is not granted the switch stays off and `Needs access to precise location` shows under it.

## Icon

The launcher icon is a disc of `#153D70`, the app's blue (`#2F8BFF`, hue 213.5) at HSV value 0.44, that fills the launcher's round mask, with a white ring whose outer edge is 66 dp across (the adaptive icon's safe zone) and a needle whose point is the compass's north red (`#FF3B30`) and whose tail is white. On the disc white has a contrast of 10.9 to 1 and the red 3.06 to 1; `#153D70` has 1.93 to 1 with the launcher's black. The monochrome layer has the same shapes. The start screen is black and shows the icon with its disc (`windowSplashScreenIconBackgroundColor`), 48 dp across on the emulators.

## Backup

The waypoint database (`waypoints.db`, which also holds the crumbs) and the settings file are the only data of the wearer's included in the system's backup and in device-to-device transfer; while the switch is off because the wearer turned it off, a backup or a transfer is handed one marker file instead, which holds the words `Back up waypoints is off.` and nothing else; when that backup or transfer is restored the switch is off. The last known position is kept in a separate file that is not included. The system does the backing up and the transferring. The `Back up waypoints` switch is checked each time the system asks for a backup; with it off the app hands over neither of them. The database and the settings file are handed to a cloud backup only when the watch can encrypt it end to end, which needs a screen lock; the marker file and device-to-device transfer do not depend on that. When a waypoint is deleted, the app overwrites its content in the database file. A waypoint deleted on the watch is removed from the app's storage. A backup made before the deletion can still contain it until a newer backup replaces it. A backup is restored when the app is installed: after it is uninstalled and installed again, or when a new or reset watch is set up. The app cannot restore a backup while it is running. A restore replaces the waypoint list with the one in the backup; waypoints logged after that backup are not kept. If the switch is turned off and the app is uninstalled before the system has made another backup, installing it again restores the earlier backup: its waypoints are back and the switch is on. Only the emulator's local backup transport was exercised, also for a pass that says it is a device-to-device transfer; Google's backup service and a transfer between two devices were not.

## Versions

Minimum API level 33 (Wear OS 4), target 35, compiled against 37. The manifest requires a watch with a compass sensor; GPS hardware is not required. It asks for precise and approximate location, which the wearer is asked to allow, and `VIBRATE`, which the system grants without asking and which the dial's vibrations need where they are played as composition primitives. Test runs: emulators at API 33, 34, 35 and 37. The tests and the scripts have run on emulators only. A debug build (commit becfa3e) was run by the owner on a Pixel Watch 3 (API 37) on 2026-10-13.

## Building

Needs JDK 17 and the Android SDK (`local.properties` with `sdk.dir`, which Android Studio writes). From the repository root:

```
./gradlew assembleDebug
./gradlew installDebug
```

`installDebug` installs on the single attached device or emulator. The release build type shrinks the app and is signed from `keystore.properties` when that file exists (the file and keystores are ignored by git).

```
./gradlew assembleWatch
```

The `watch` build type is for trying the app on a watch. It is made as the release build is (minified with R8, resources shrunk, not debuggable) and signed with the machine's debug key, the key the debug build is signed with, so it needs no keystore. It has the debug build's application id, and `~/Library/Android/sdk/platform-tools/adb -s <serial> install -r app/build/outputs/apk/watch/app-watch.apk`, with the serial of the one device it is for (`~/Library/Android/sdk/platform-tools/adb devices` lists them), replaces an installed debug build in place. `BuildConfig.DIAGNOSTICS` is true in the debug and watch builds and false in the release build.

## Tests

Unit and instrumented tests, and the scripts that run them on the emulators, are described in [docs/TESTING.md](docs/TESTING.md). What the emulators cannot show, and how to check each on a watch, is listed in [docs/WATCH-CHECKLIST.md](docs/WATCH-CHECKLIST.md). The design is in [docs/superpowers/specs/2026-10-11-wear-compass-v2-redesign.md](docs/superpowers/specs/2026-10-11-wear-compass-v2-redesign.md) and [the first spec](docs/superpowers/specs/2026-10-11-wear-compass-design.md).

## License

MIT; see [LICENSE](LICENSE).
