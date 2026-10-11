# Wear OS Compass: Design

Date: 2026-10-11
Status: awaiting review

## Purpose

A standalone compass for Wear OS that also navigates back to saved places.
The wearer logs the current GPS position as a waypoint with one tap, and later selects a waypoint to see its direction and distance on the compass dial.
The app is free, MIT licensed, and published under the infinite8looper developer account.

Success means:

- The dial matches `notes/mockup.png` and moves smoothly, with no visible stutter or jitter, on a Pixel Watch.
- The heading agrees with a reference compass once the watch is calibrated.
- A waypoint marker points at the saved place and the distance shown is correct.
- Logging a waypoint takes one tap.

## Decisions made with the owner

| Topic | Decision |
|-|-|
| Test device | Pixel Watch (generation not yet stated), plus the two round Wear OS emulators already installed |
| Waypoint interaction | Cycle in place on the compass screen, and a separate list page for editing |
| North reference | User setting, true or magnetic, defaulting to true |
| Power | Screen stays awake and GPS updates run only while a waypoint is selected |
| Heading source | System fused rotation-vector sensor with light smoothing |
| Drawing | One Compose Canvas |
| Location | Google Play services fused location |
| Storage | Room for waypoints, DataStore for settings |
| Package | `com.infinite8looper.compass`, min SDK 33, target SDK 35 |

## Out of scope

Phone companion app, cloud sync or accounts, maps, track recording, tiles, complications, ambient mode, and any network access.

## Screens

### 1. Compass (start screen)

Fullscreen on a black background, following the mockup:

- A dial that rotates so that its north always points to real north.
  It has fine ticks every 2°, heavier ticks every 30°, degree labels (0, 30, ... 330) outside the tick ring, and N, E, S, W inside it.
- A red tick on the dial at north.
- A fixed blue triangle at 12 o'clock marking the direction the top of the watch points.
- The heading in the center as a whole number of degrees.
- Under the heading, when a waypoint is selected: a dot in its color, its name, and the distance to it.
- On the ring, when a waypoint is selected: a marker in its color at the bearing to that waypoint.
- A `+` button at the bottom that logs a waypoint.
- A small label `MAG` when the dial is showing magnetic north (see North reference).
- A settings entry point at the top edge, small enough not to compete with the dial.

Gestures on this screen:

- Turning the crown, or swiping up or down, steps through the selection: none, then each waypoint, newest first.
- Tapping the waypoint name opens the waypoints list. With no waypoints saved, the same spot shows a hint to tap `+`.
- The system's swipe-right gesture leaves the app, as usual.

The heading is the direction of the watch's 12 o'clock edge projected onto the horizontal plane.
When the watch is tilted so steeply that this projection is unstable, the screen shows a "hold flat" hint and the dial keeps its last stable value.

### 2. Waypoints list

A scrolling, curved list. Each row shows the color dot, the name, and the distance if a location fix exists.
Tapping a row selects that waypoint and returns to the compass.
Each row has an edit control that opens the edit screen.
The first item is "None", which clears the selection.

### 3. Edit waypoint

- Name: opens the system text input (voice or keyboard).
- Color: a fixed palette of eight colors. The palette leaves out the blue of the heading triangle and the red of the north tick so a marker is never mistaken for either.
- Move here: replaces the saved position with the current position, after confirmation.
  This is my reading of "edit" in the request beyond rename and recolor; it can be dropped.
- Delete: after confirmation. Deleting the selected waypoint clears the selection.

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

1. Tapping `+` asks for the location permission if it has not been granted.
2. The app requests a fresh high-accuracy fix and shows the current accuracy while it waits.
3. It saves as soon as a fix with accuracy of 15 m or better arrives.
4. After 20 seconds without one, it saves the best fresh fix it has and shows that fix's accuracy, or, if it has no fresh fix at all, says that it could not get a position.
5. It never saves a cached or stale position.

New waypoints are named "Waypoint N" and take the next unused palette color, so logging needs no typing.
A newly logged waypoint is not selected automatically.

If the permission is denied, the compass still works, and `+` explains that waypoints need location access.

### Navigating

- While a waypoint is selected and the compass screen is in the foreground, the screen stays awake and the position updates about every 5 seconds.
- With no waypoint selected, the screen follows the system timeout and location is used only for logging.
- The selection is stored, so it survives closing the app.
- Distance is shown in meters below 1 km and kilometers with one decimal above, or in feet below 1000 ft and miles with one decimal above.
- When the distance is under 10 m, the text reads "< 10 m" (or "< 30 ft"), since the bearing is not meaningful at that range.
- If position updates stop arriving, the distance is marked as out of date instead of being shown as current.

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
| `Navigation` | Pure functions: bearing, distance, declination correction, angle smoothing, distance formatting | Nothing from Android except the declination lookup, which sits behind one function |
| `WaypointStore` | Create, read, update, delete waypoints | Room |
| `SettingsStore` | North reference, units, selected waypoint, last known position | DataStore |
| `CompassViewModel` | Combines the above into one screen state | The units above |
| One file per screen | Drawing and gestures | Compose for Wear OS |

A waypoint holds: id, name, latitude, longitude, accuracy at the time of logging, creation time, and palette color index.

## Errors and edge cases

- No rotation-vector sensor: the manifest requires a compass sensor so Play does not offer the app to such watches; if it is still missing at run time, the app says the device is unsupported.
- Location permission denied or location turned off: compass works; waypoint features explain what is needed.
- No fix indoors: logging reports failure as described above; navigating marks the distance as out of date.
- Empty waypoint list, duplicate names, very long names (truncated in display), and deleting the selected waypoint are all handled without error.

## Testing

No mocks, per the owner's standing rules.

- `Navigation`: unit tests against published reference values for bearing and distance, wraparound cases for smoothing, and unit formatting boundaries.
- `WaypointStore` and `SettingsStore`: instrumented tests on the emulator that write and read real data, including an empty database, duplicate names and deletion of the selected waypoint.
- Screens: emulator screenshots at both round sizes, exported and checked by eye against the mockup.
- Location on the emulator: positions fed through the emulator's own location controls, to check the logging and navigating flows end to end.
- Sensors and GPS: on the Pixel Watch, outdoors, against a reference compass and a known landmark. The emulator's sensors are simulated and cannot verify smoothness or accuracy.

## To verify at the start of implementation

These are stated from memory and are checked before code depends on them:

- That the Pixel Watch reserves the crown press and side button for the system, so logging must be an on-screen button. If a button turns out to be available to apps, it becomes an additional way to log.
- The Pixel Watch generation and its Wear OS version, to confirm min SDK 33 covers it.
- Current stable versions of Compose for Wear OS, Room, DataStore and Play services location, each confirmed by a real build.

## Release

- The app is marked standalone so it installs without a phone app.
- The build produces a signed app bundle for Play.
- The privacy statement needs to say that location is used only on the watch and is never transmitted. Wording for the Play listing and for `infinite8looper.github.io/privacy.html` is drafted for the owner's approval and not published without it.
- Uploading to Play and publishing are done only on the owner's explicit go-ahead.
