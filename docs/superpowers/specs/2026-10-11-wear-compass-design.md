# Wear OS Compass: Design

Date: 2026-10-11
Status: awaiting review

## Purpose

A standalone compass for Wear OS that also navigates back to saved places.
The wearer logs the current GPS position as a waypoint with one press of the watch's side button, and sets any number of waypoints as targets to see their directions and distances on the compass dial.
The app is free, MIT licensed, and published under the infinite8looper developer account.

Success means:

- The dial matches `notes/mockup.png` and moves smoothly, with no visible stutter or jitter, on a Pixel Watch.
- The heading agrees with a reference compass once the watch is calibrated.
- Each target's marker points at its saved place and the distance shown is correct.
- Logging a waypoint takes one press of the side button.

## Decisions made with the owner

| Topic | Decision |
|-|-|
| Test device | Pixel Watch (generation not yet stated), plus the two round Wear OS emulators already installed |
| Waypoint interaction | Any number of waypoints can be targets at once, all shown on the dial. Cycle the focused target in place on the compass screen; a separate list page for editing |
| Colors | Generated hues at constant saturation and brightness, so there is no limit on the number of waypoints |
| North reference | User setting, true or magnetic, defaulting to true |
| Power | Screen stays awake and GPS updates run only while at least one waypoint is a target |
| Heading source | System fused rotation-vector sensor with light smoothing |
| Drawing | One Compose Canvas |
| Location | Google Play services fused location |
| Storage | Room for waypoints, DataStore for settings; the system backs both up when the wearer has backup turned on |
| Package | `com.infinite8looper.compass`, min SDK 33, target SDK 35 |

### Supported versions

Min SDK 33, target SDK 35. The whole test suite (26 unit tests, 51 instrumented tests) was run on 2026-10-11 on three Wear OS emulators, all arm64 with a large round 454 px screen. None of it has been run on a physical watch.

| API level | System image | AVD | Result |
|-|-|-|-|
| 33 (Android 13, Wear OS 4) | `system-images;android-33;android-wear;arm64-v8a` | `Wear_OS_API33` | All pass, none skipped |
| 34 (Android 14, Wear OS 5) | `system-images;android-34;android-wear;arm64-v8a` | `Wear_OS_API34` | All pass, none skipped |
| 35 (Android 15, Wear OS 5.1) | `system-images;android-35-ext15;android-wear;arm64-v8a` | `Wear_OS_Large_Round` | All pass, none skipped |

Each emulator reported its level through `ro.build.version.sdk` (33, 34, 35). The build fingerprints recorded were `google/sdk_gwear_arm64/emu64a:13/TWR7.230913.001.E7/11551413:userdebug/dev-keys` for API 33 and `google/sdk_gwear_arm64/emu64a:14/AW2A.240422.007.E8/12230326:userdebug/dev-keys` for API 34; the API 35 fingerprint was not recorded.

Checked by hand on API 33 and on API 34, each step with a screenshot: the compass laid flat at magnetic heading 90, logging a waypoint with the side button, the waypoints list, the edit screen, renaming through the system keyboard, the color ring turned with the crown, deleting with confirmation, the settings screen and the calibration screen. After the delete, the app's own database files on each level held neither the waypoint's name nor its earlier name.

What the tests and these checks showed about the database: `PRAGMA secure_delete = ON` set through `execPerConnectionSQL` reads back as 1, and the rollback journal is truncated, on all three levels.

What differed by level:

- No difference in what the app did was found in the steps checked. Layout, colors and shapes matched the API 35 screenshots.
- Text is drawn a few pixels narrower on API 33 and 34 than on API 35 (on the settings screen, "Off: magnetic north" ends at x=296 on 33 and 34 and at about x=300 on 35). The cause was not looked into.
- The API 33 image twice put the watch into ambient mode about 0.2 s after the app was started through `adb`, both times after more than a minute without any input (the system log says "Going to sleep due to timeout"). The first side-button press then only woke the watch; the second logged a waypoint. This was not seen on the API 34 or 35 images. The image's rule for this was not established.
- On API 33 the system keyboard first showed its own prompt asking for access to contacts, which was declined.
- Emulator tooling only: `adb shell input rotaryencoder` does not exist on the API 33 image, so the crown was turned there by writing to the rotary input device as root (`adb root`, then `sendevent` with `EV_REL`/`REL_WHEEL` on the device named `virtio_input_rotary`).

Not checked:

- Saving a color on API 34: the ring was turned with the crown there, but the screen was left with Back instead of Done. On API 33 it was left with Done, and the saved color was not looked at afterwards on either level.
- A backup pass through the backup agent on API 33 and 34. The agent's conditions are covered there by the instrumented tests of the database, not by a run of the system's backup.
- Swiping to move the focus, the "All" and "None" items, the target switch, the settings switches, and the hold-flat hint beyond its showing at first launch, by hand on API 33 and 34.
- A before-and-after comparison on API 35, where the suite was run only after the changes of this round.
- The error lines that system processes of the API 33 image write to the log.
- Anything on a physical watch, on any level.

## Out of scope

Phone companion app, cloud sync or accounts of the app's own, maps, track recording, tiles, complications, ambient mode, and any network access by the app itself. (The system's backup of the app's data, described under Behavior, is done by the system.)

## Screens

### 1. Compass (start screen)

Fullscreen on a black background, following the mockup:

- A dial that rotates so that its north always points to real north.
  It has fine ticks every 2°, heavier ticks every 30°, degree labels (0, 30, ... 330) outside the tick ring, and N, E, S, W inside it.
- A red tick on the dial at north.
- A fixed blue triangle at 12 o'clock marking the direction the top of the watch points.
- The heading in the center as a whole number of degrees.
- On the ring: one marker per target, in that waypoint's color, at the bearing to it. All markers update together.
  Markers have their own shape, distinct from the north tick and the heading triangle, so a red or blue marker is not mistaken for either.
- Around the heading: the focused target's color dot and name above it, and the distance below it. The focused target's marker is drawn larger than the others.
- No on-screen button: a press of the watch's side button logs a waypoint.
- A small label `MAG` when the dial is showing magnetic north (see North reference).

Gestures on this screen:

- Turning the crown, or swiping up or down, moves the focus through the targets, newest first. It changes which target's name and distance are shown, not which waypoints are targets.
- Tapping the name area opens the waypoints list. With no targets it reads "No target"; with no waypoints saved the compass shows no waypoint text and works as a plain compass. Tapping the center still opens the waypoints list, which holds the item that logs a waypoint and the settings item.
- The system's swipe-right gesture leaves the app, as usual.

The heading is the direction of the watch's 12 o'clock edge projected onto the horizontal plane.
When the watch is tilted so steeply that this projection is unstable, the heading is not taken:

- The dial keeps its last stable value and is dimmed.
- In place of the heading number, the center shows a looping animation of a watch seen from the side, turning from upright to level between two level marks, with the words "Hold flat" below. One loop takes about 2 seconds and rests on the level position for about half a second before it starts again.
- The focused target's name and distance stay where they are. The drawing is larger when no name or distance is shown.
- The state begins when the tilt passes 70° and ends only when it drops below 60°, so a watch held near either limit does not switch back and forth.
- When the system setting that removes animations is on, the watch is shown still, in the level position.
- When the watch is level again, the dial returns to full brightness and the heading number comes back.

### 2. Waypoints list

A scrolling, curved list. Each row shows the color dot, the name, the distance if a location fix exists, and whether the waypoint is a target.
Each row has a switch that sets it as a target; tapping the rest of the row opens the edit screen.
The first item logs a waypoint at the current position.
Below it, two items set all waypoints as targets or clear all targets.
The last item opens settings.
With no waypoints saved, the list shows the first item, a line saying there are none yet, and the settings item.

### 3. Edit waypoint

- Name: opens the system text input (voice or keyboard).
- Set target: toggles whether this waypoint is a target.
- Color: a hue ring turned with the crown or by touch. Only the hue changes; saturation and brightness are fixed.
- Delete: after confirmation. If the deleted waypoint had the focus, the focus moves to the next target.

A waypoint's saved position cannot be changed; to correct one, delete it and log a new one.

### 4. Calibration

An app cannot force the magnetometer to recalibrate; the system does it as the watch is moved. This screen guides that:

- An animation of the figure-8 motion.
- The live accuracy level reported by the system (unreliable, low, medium, high), which the wearer watches improve.
- A warning when the measured field strength is outside the normal range for Earth's field (about 25 to 65 microtesla), which means a magnet, charger or steel object is nearby.

It opens from settings.
The compass screen also shows a small prompt that leads here when the reported accuracy is low or unreliable.

### 5. Settings

- North: true or magnetic.
- Units: metric or imperial, defaulting from the watch's locale.
- Tick feedback (default on): a faint vibration as the heading crosses the dial's minor tick marks.
- Show coordinates (default off): shows the current GPS position on the compass screen. Turning it on asks for location access with the system dialog if it has not been granted; if the wearer denies it, the switch stays off and a line under it says "Needs location access". If access is already granted it turns on without asking, and turning it off never asks for anything.
- Back up waypoints (default on): includes the saved waypoints and settings in the watch's system backup (when the watch can encrypt it) and in device-to-device transfer. See "Backup and transfer" under Behavior for what turning it off does and does not do.
- Calibrate.
- About: version and the MIT license.

## Behavior

### North reference

A bearing between two GPS positions is relative to true north, and the magnetometer reports magnetic north.
The app converts between them with the declination from Android's built-in World Magnetic Model for the last known position.

- Waypoint markers are always placed correctly whichever north the dial shows.
- With the setting on true north, the dial shows true north once any position is known. The last known position is stored, so this normally holds from launch.
- If no position has ever been known, the dial shows magnetic north and the `MAG` label, even with the setting on true.

### Logging a waypoint

1. Pressing the side button asks for the location permission if it has not been granted.
2. The app requests a fresh high-accuracy fix and shows the current accuracy while it waits.
3. It saves as soon as a fix with accuracy of 15 m or better arrives.
4. After 20 seconds without one, it saves the best fresh fix it has and shows that fix's accuracy, or, if it has no fresh fix at all, says that it could not get a position.
5. It never saves a cached or stale position.
6. If location is switched off in the system settings, the app does not search. It says that location is off and to turn it on in Settings, with an OK button.
7. If the waypoint cannot be stored, it says so instead of reporting a save.

New waypoints are named "Waypoint N" and are given a color automatically, so logging needs no typing.
A newly logged waypoint becomes a target, so its marker appears on the dial at once. The focus does not move to it.

### Colors

Every waypoint color has the same saturation and brightness, chosen to read well on black; only the hue differs.
The hue for each new waypoint steps round the color wheel by the golden angle (about 137.5 degrees) from the previous one.
This keeps consecutive waypoints far apart in hue, never repeats exactly, and has no upper limit on the number of waypoints.
The wearer can change any waypoint's hue on the edit screen.

If the permission is denied, the compass still works, and pressing the side button explains that waypoints need location access.

### Backup and transfer

The saved waypoints (the `waypoints.db` database) and the settings (the DataStore file `settings.preferences_pb`) are included in the system's cloud backup and in device-to-device transfer, so they come back on a replacement or reset watch. Nothing else is included; in particular the last known position is kept in a separate file that is not part of the backup. The system does the backup and the transfer; the app has no network permission and makes no network requests of its own.

- The "Back up waypoints" switch in Settings controls both, and is on by default. The app's backup agent checks the switch each time the system asks for a backup, and with the switch off it hands over nothing. A fresh install with no settings file yet counts as on.
- The agent hands data over only when it reads that the switch is on. If the settings file cannot be read, or the read does not finish in 5 seconds, it hands over nothing. If the settings file is found damaged it is reset to defaults with the backup switch off, so the switch shows off until the wearer turns it on again.
- Cloud backup of the app's data happens only when the watch can encrypt the backup end to end (a screen lock is set). Direct device-to-device transfer does not depend on that.
- Device-to-device transfer goes through the same agent code as cloud backup, by design; it could not be exercised on the emulator.
- What turning the switch off does to data already backed up was observed only on the emulator's local backup transport: a backup pass made with the switch off was rejected, and afterward a restore brought back nothing. Google's backup service was not tested, so what it does with an earlier backup in that case is not known.
- Changing the switch tells the system the app's data has changed; with the switch back on, the data is included from the next backup pass.
- A waypoint the wearer has deleted, and the old name of one that was renamed, are not included in later backups. The database is set to overwrite deleted and replaced content with zeros on every connection, and to keep no write-ahead log but a rollback journal that is emptied after every change. Where the platform keeps the database in write-ahead-log mode anyway, the log is written into the database file and emptied at the start of each backup pass.
- Before a database file is ever backed up it is rebuilt once, so that it holds nothing left over from rows deleted or changed earlier (by an older build, or on the watch the file was restored or transferred from). The rebuild is recorded by a random number written both into the database file and into a file that is never backed up; a file without a matching record, such as one that has just been restored, is rebuilt again. The rebuild runs in the background when the app starts and, if still needed, at the start of a backup pass. If it fails the app keeps working and it is tried again later.
- The agent copies the database only with the database locked against changes, and only if the file is on record as rebuilt, deleted content is being overwritten, and the journal and log files next to the database hold no data. It reads the "Back up waypoints" switch again once the lock is held. If any of this cannot be confirmed, or the database cannot be opened, that backup pass is skipped and nothing is handed over. If there is no database file, the agent does not create one.
- A skipped pass hands over nothing, and the app has no means to change or remove an earlier backup, so an earlier backup stays whatever the backup service makes of it and can still hold a waypoint deleted since it was made.
- After a restore or transfer the watch has no stored last position, so true north waits for the first position fix.
- A restore brings back the waypoints with their names, positions, colors and target flags, and the settings; the next waypoint logged continues the numbering instead of reusing a number.

### Navigating

- Any number of waypoints, up to all of them, can be targets at the same time. One position update refreshes every target's bearing and distance.
- While at least one waypoint is a target and the compass screen is in the foreground, the screen stays awake and the position updates about every 5 seconds.
- With no targets, the screen follows the system timeout and location is used only for logging.
- Which waypoints are targets, and which has the focus, are stored, so both survive closing the app.
- Distance is shown in meters below 1 km and kilometers with one decimal above, or in feet below 1000 ft and miles with one decimal above.
- When the focused target is under 10 m away (30 ft in imperial), the distance text is replaced by "Arrived at target", since the bearing is not meaningful at that range.
- If position updates stop arriving, the distance is marked as out of date instead of being shown as current.

### Tick feedback

- With the Tick feedback setting on, the watch gives a very faint tap each time the heading shown on the dial crosses one of the fine ticks (every 2°).
- The taps follow the dial as it is drawn, which is smoothed, not the raw sensor value.
- A heading that rests on a tick, or wavers around it, gives no taps: the heading has to pass a tick by about 0.3° before that tick counts, and has to come back the same amount past it for it to count again.
- There is at most one tap every 40 ms. During a fast turn the taps that would come sooner are dropped, not saved up, and several ticks crossed in one display frame give one tap.
- No taps are given while the setting is off, while the "hold flat" hint shows, while the compass is not the screen in front, before the first heading has arrived, or on a watch with no compass sensor.
- The tap is the lightest tick the system offers for frequent feedback, and it follows the system's own setting for touch feedback. The app does not ask for the vibration permission.

### Smoothness

- The sensor is read at the game rate (about 50 Hz) and the dial is drawn once per display frame.
- Smoothing handles the 359° to 0° wraparound, so the dial never spins the long way round.
- The smoothing strength is tuned on the Pixel Watch, trading lag against jitter.
- The dial's static parts are not recomputed each frame; only the rotation changes.

## Code structure

| Unit | Responsibility | Depends on |
|-|-|-|
| `HeadingSource` | Turns sensor events into a stream of heading, tilt, accuracy level and field strength | Android sensors |
| `LocationSource` | One-shot fresh fix; periodic updates while navigating | Play services location |
| `Navigation` | Pure functions: bearing, distance, declination correction, angle smoothing, distance formatting, hue assignment | Nothing from Android except the declination lookup, which sits behind one function |
| `WaypointStore` | Create, read, update, delete waypoints | Room |
| `SettingsStore` | North reference, units, tick feedback, show coordinates, backup switch, focused target, last known position | DataStore |
| `CompassViewModel` | Combines the above into one screen state | The units above |
| One file per screen | Drawing and gestures | Compose for Wear OS |

A waypoint holds: id, name, latitude, longitude, accuracy at the time of logging, creation time, hue, and whether it is a target.

## Errors and edge cases

- No rotation-vector sensor: the manifest requires a compass sensor so Play does not offer the app to such watches; if it is still missing at run time, the app says the device is unsupported.
- Location permission denied or location turned off: compass works; waypoint features explain what is needed.
- No fix indoors: logging reports failure as described above; navigating marks the distance as out of date.
- Empty waypoint list, duplicate names, very long names (truncated in display), deleting the focused target, and many targets with markers that overlap on the ring are all handled without error.

## Testing

No mocks, per the owner's standing rules.

- `Navigation`: unit tests against published reference values for bearing and distance, wraparound cases for smoothing, unit formatting boundaries including the arrival threshold, and hue assignment over a large number of waypoints.
- `WaypointStore` and `SettingsStore`: instrumented tests on the emulator that write and read real data, including an empty database, duplicate names, all waypoints set as targets, and deletion of the focused target.
- Screens: emulator screenshots at both round sizes, exported and checked by eye against the mockup, including a dial with many targets.
- Location on the emulator: positions fed through the emulator's own location controls, to check the logging and navigating flows end to end.
- Sensors and GPS: on the Pixel Watch, outdoors, against a reference compass and a known landmark. The emulator's sensors are simulated and cannot verify smoothness or accuracy.

## To verify at the start of implementation

These are stated from memory and are checked before code depends on them:

- Whether the Pixel Watch delivers its side button to apps. Logging uses the side button; if the watch does not deliver it, logging is reached from the waypoints list instead.
- The Pixel Watch generation and its Wear OS version, to confirm min SDK 33 covers it.
- Current stable versions of Compose for Wear OS, Room, DataStore and Play services location, each confirmed by a real build.

## Release

- The app is marked standalone so it installs without a phone app.
- The build produces a signed app bundle for Play.
- The privacy statement has to match these facts: the app itself makes no network requests and requests no network permission; saved waypoints and settings are included in the watch's system backup and device-to-device transfer when the wearer has backup turned on and the "Back up waypoints" switch is on. Wording for the Play listing and for `infinite8looper.github.io/privacy.html` is drafted for the owner's approval and not published without it.
- Uploading to Play and publishing are done only on the owner's explicit go-ahead.
