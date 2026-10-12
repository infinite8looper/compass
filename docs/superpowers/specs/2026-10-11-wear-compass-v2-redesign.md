# Wear OS Compass: interaction redesign (v2)

Date: 2026-10-11
Status: approved by the owner ("Redesign looks great! Proceed") from mockups on the review page.
Amends: `2026-10-11-wear-compass-design.md`. Where the two differ, this document wins. Everything the first spec says that is not changed here still holds, including backup behavior, the supported versions and the testing rules.

## The owner's requests, verbatim

- "waypoint markers should be lines, not dots. The active waypoint should be marked in an arrow (same style as North, but in that waypoint's color)"
- "the process for adding waypoints is too clunky; should just be a single button press. Then to edit or rename, swipe left (or right twice) or long press the watch face to reveal waypoint settings menu. Swipe right (or left twice) to reveal full settings"
- "add a "breadcrumb" mode: double button press to activate, single button press to last down waypoints. Then at a double press, set targets to each waypoint in turn, in reverse chronological order (deleting each as it is reached and then activating the next target). Default: disabled."
- "add another setting to initiate a more powerful vibration when you are facing the current waypoint target (blind navigation mode)"
- "work with designer mode to clean up the menu and appearance of the alternative screens-- need to improve efficiency, appearance, theme consistency, color compliments, and usability. Minimize number of presses/actions are needed to perform each function."

Earlier requests that this redesign must carry forward:

- "If there are no waypoints, just show the compass without waypoints; should be useable as a standard compass without distraction"
- "Hold flat notification should have a nice looping animation showing the watch moving to the correct orientation"
- "the compass should do a very faint tap (subtle vibrate) whenever the orientation crosses one of the minor ticks"
- "Also add option to show current GPS coordinates on the main compass screen (off by default)", at a precision of about 10 m.
- "For names that don't fit without clipping, use marquee back and forth scrolling (in place) to reveal the full text over time"
- "Make sure to test screenshots of all modes and screens"

## Decisions the owner made by answering questions

- Breadcrumbs are a separate temporary trail. Named waypoints are never touched or deleted by it.
- Blind navigation: one strong pulse on turning onto the target, then a soft pulse about once a second while still facing it.
- The crown press is the only way to leave the app. Swipes are for moving between the three screens. (The owner's decision of 2026-10-12, under "Changed by the owner after approval", restates this and says what Back and the side button do.)

## Changed by the owner after approval

- "when the current direction and goal (target) direction align within 2 degrees, show a thicker line, not a circle (and vibrate/pulse if blind navigation is enabled)". This replaces the ring around the pointer and the 5 degree window in the mockups.
- "Also: enable blind navigation by default".
- On the hold-flat label: "red hold flat text should be another less jarring color... Light gray?" It is light gray.
- On the other targets: "I'm also wondering if all waypoints aside from the active one should be desaturated (near gray) and maybe even partially transparent". From four drawn options the owner chose the soft tint: a pale version of each waypoint's color at about 60 percent strength.
- On colors: "maybe the colors should be drawn using theme consistent coloring to match the watch face?" The owner chose: yes where the watch provides it, for the accent only.
- The `Calibrate` prompt on the compass is light gray too.
- "North should also be drawn in a themed color".
- 2026-10-12: "Back swipe should go to settings. Pressing the non-crown button should log a waypoint. Pressing the crown exits." As built: system Back on one of the three screens turns one screen toward settings, as a right swipe does (compass to settings, settings to waypoints, waypoints to the compass), and never closes the app. The side button logs a waypoint on the compass and on the waypoints screen. The crown's press, which is the system's, is the only way out of the app.

- 2026-10-12: "1. "Needs location access" --> "Needs access to precise location" 2. pause compass when not on the compass screen 3. what's the interference issue? take screenshots and point it out" and then "when strong magnet is nearby, change ring to red as suggested". As built: the words are `Needs access to precise location` wherever the app says it, on two lines (`Needs access to`, `precise location`) in the logging status and under `Show coordinates` in the settings; the heading sensor runs only while the compass is on screen or calibration is open; on calibration the whole frame is filled in the error color while interference is detected.

## Decisions made in the mockups and approved with them

- Arrow reading: every target is a line across the ring; the focused target adds an arrow shaped like the blue pointer, in the waypoint's color.
- Tapping the middle of the compass no longer opens the waypoint list.
- With the breadcrumb setting on, a single press waits about a third of a second to see whether a second press follows. With it off, a press acts at once.
- Editing a waypoint has no confirmation dialog. Holding Delete is the safeguard.

## Visual language for every screen

One instrument, one set of parts.

- Field: black.
- Ink (`#E8EAED`) for text and heavy ticks; a dim gray for secondary text; a darker gray for fine ticks and for anything switched off.
- Blue (`#2F8BFF`) for the heading pointer and for anything that is on or can be acted on: a switch that is on, the scroll position, the current screen's tick.
- Red (`#FF3B30`) for north, for failures (a save that failed, magnetic interference), and for deleting. Nothing else is red; the `Hold flat` and `Calibrate` prompts are light gray.
- Colors from the watch: on a watch whose system provides a color scheme taken from the watch face, the accent (the pointer and everything described as blue here) uses that scheme's primary color, and north uses a second color from the same scheme (its tertiary color), chosen so that north and the pointer are plainly different from each other. Failures and deleting use the scheme's error color. On a watch that provides no scheme, the accent is the blue above and north, failures and deleting are the red above. Waypoints keep their own colors on every watch.
- Wherever this document says "blue" or "red", read it as the accent, the north color or the error color as defined here.
- A waypoint's own color (`Color.hsv(hue, 0.65f, 1f)`) for that waypoint only: its line, its arrow, its row tick, its color wheel position.
- Breadcrumbs share one muted blue-gray so they never compete with waypoints.
- Every screen other than the compass is framed by a thin static ring of ticks at the edge of the display. The frame carries information where it can: scroll position as a blue arc, the color wheel on the edit screen, accuracy on the calibration screen, and three ticks at the bottom showing which of the three screens is in view.
- A switch is drawn as a tick at the right of its row: long and blue when on, a short gray stub when off. No pill-shaped buttons and no gray button backgrounds.
- One type family (the system's), light weight for large numerals, regular for labels, medium for a row that is on.
- Rows are text on black. Touch targets stay at least 48 dp tall even though the drawn marks are small.

## Compass

### Markers

- Every target is drawn as a line across the tick ring at its bearing, a little longer and heavier than a heavy tick.
- The focused target's line is in the waypoint's full color, with a thin black casing so it reads against the ticks.
- Every other target's line is a soft tint: a paler version of its own color (about 60 percent of the full color's saturation) at about 60 percent opacity, with no casing. Targets can still be told apart by color, but only the focused one is vivid.
- The focused target also has an arrow: a triangle of the same shape and size as the blue heading pointer, in the waypoint's color, just inside the ring at the target's bearing, pointing outward.
- The dot markers are gone.
- A target within the arrival distance (under 10 m, or under 30 ft with feet and miles) has no line and no arrow: the direction to a point the wearer is standing on means nothing, and the readout says `Arrived at target`. Such a target is not faced. A waypoint that has just been logged therefore shows its line only once the wearer has moved away from it.
- Breadcrumbs, when a trail exists, are short muted ticks inside the ring. While retracing, the crumb being walked to is drawn like a focused target, in the breadcrumb color.

### Facing the target

- "Facing" means the shown heading is within 2 degrees of the focused target's bearing. Once facing, it stays so until the difference exceeds 3 degrees, so sensor noise at the edge does not make it flicker.
- Facing is never reported while the hold-flat state applies, or while the dial is still catching up after the app opens, the screen wakes, hold-flat ends or the north reference changes. It resumes once the dial has caught up, which takes at least 0.4 seconds.
- When the focus moves to another target, that target starts from not facing: it has to be within 2 degrees to count as faced.
- While facing, the focused target's line is drawn clearly thicker. It sits at 12 o'clock under the blue pointer at that moment, so the wearer sees one heavy line in the target's color there. There is no ring or circle.

### Logging with one press

- A single press of the side button saves the current position as a waypoint without leaving the compass.
- While it works, the distance slot shows a short status: `Saving…`, with the current accuracy once a fix is arriving; then `Saved Waypoint N` with the accuracy on the line under it, for about two seconds, with a light confirming tap; or a failure in red for about three seconds: `No GPS fix`, `Location is off`, `Needs access to precise location` (on two lines), or `Couldn't save`.
- The first press on a fresh install shows the system's location permission dialog; if granted, the save continues without a second press.
- The rules for which fix is saved are unchanged (15 m or better at once; the best fresh fix after 20 seconds), with one tightening: a fix taken before the press is never saved.
- A second press while a save is in progress does nothing.
- `Saved Waypoint N` is in a soft green, the confirmation color. The accuracy under `Saving…` and `Saved Waypoint N` is small and dim, in the chosen units. `No GPS fix` has `Try again outdoors` under it in the same small dim text; `Needs access to precise location` is on two lines, both in the error color; the other failures are one line.
- With no targets the status appears alone under the heading and the compass is clean again when it clears.
- The separate logging screen is removed.

### Getting around

- Three screens sit side by side and wrap around: settings, compass, waypoints. From the compass, swiping left shows waypoints and swiping right shows settings; swiping the same way twice reaches the other one.
- Long-pressing the compass also opens waypoints.
- Tapping the compass does nothing.
- Swiping up or down, or turning the crown, on the compass still moves the focus among the targets.
- The side button acts while the compass or the waypoints screen is in view, and logs a waypoint on either. It does nothing on settings, on the edit screen or on calibration.
- Pressing the crown leaves the app. A right swipe does not, and neither does Back: on the three screens Back turns one screen toward settings, as a right swipe does.
- Screens opened from a row (edit a waypoint, calibration) go back with the usual right swipe, and with Back.

### Unchanged

The clean compass with no waypoints, `MAG`, the hold-flat animation, tick feedback, the keep-awake rule, and the calibrate prompt.

## Waypoints screen

- A header line states how many waypoints are targets, for example `3 of 4 targets`. Tapping it sets all as targets, or clears all if all already are.
- First row: `Log here`, which saves a waypoint exactly as the side button does. It exists because not every watch passes its side button to apps.
- The side button saves a waypoint from this screen too, while it is in view.
- One row per waypoint, newest first: its tick (long and in its color when it is a target, a short gray stub when not), its name, and its distance when a position is known.
- Tap a row: set or clear it as a target.
- Long-press a row: edit it.
- While a breadcrumb trail exists, a row for the trail shows its length, and long-pressing it clears the trail.
- With no waypoints: `Log here` and one quiet line saying there are none yet.

## Edit a waypoint (one screen)

- The frame is the color wheel. Dragging along it, or turning the crown, changes the waypoint's color at once; there is no separate color screen and no Done button. The change is saved as it is made.
- The name is in the middle. Tapping it opens the system's text input.
- A target tick above the name shows and toggles whether it is a target.
- `Hold to delete` at the bottom: holding it for about a second deletes the waypoint, with the frame draining as it is held so the wearer can let go to cancel. No dialog.
- A right swipe goes back, from the edge of the screen too: a touch that goes down on the color wheel and moves toward the middle is a swipe and changes no color. (This replaces "a right swipe that does not start on the color wheel goes back".)

## Settings screen

One tap per switch, in this order: True north; Feet and miles; Tick feedback; Blind navigation; Breadcrumb trail; Show coordinates; Back up waypoints; then Calibrate (showing the current accuracy level, opening calibration) and the version.

New settings: Blind navigation (on by default) and Breadcrumb trail (off by default).

## Calibration screen

The figure-8 guide stays. The frame fills in green by quarters as accuracy improves (none for unreliable, one quarter for low, three for medium, all four for high). The accuracy level is the large text; the field strength is small; the interference warning is red, and while it shows the whole frame is filled in red.

## Blind navigation

- A setting, on by default (the owner: "enable blind navigation by default").
- When on, and a focused target (or the crumb being retraced) has a bearing: one strong pulse at the moment the wearer turns to face it, then a soft pulse about once a second for as long as they keep facing it.
- Silent while hold-flat applies, while the compass is not the screen in view, and when there is no focused target.
- Uses the standard haptic feedback call, with no vibration permission.
- Tick feedback continues to work alongside it.

## Breadcrumb trail

- A setting, off by default. While it is off, a double press means nothing special and a single press saves a waypoint at once.
- While it is on:
  - A double press with no trail starts one. The compass shows `Trail` and the number of crumbs.
  - While laying a trail, each single press drops a crumb at the current position, using the same fix rules as saving a waypoint. Named waypoints can still be saved from `Log here`.
  - A double press while laying switches to retracing. The newest crumb becomes the thing being walked to: its direction and distance are shown as for a focused target, labeled with its place in the trail.
  - On arriving at a crumb (within 10 m, or 30 ft), it is deleted and the next newest takes over. After the last one, the compass shows `Trail complete` briefly and returns to normal.
  - A double press while retracing returns to laying, keeping the crumbs that remain.
  - While a trail is being retraced, the waypoint focus is suspended and returns afterwards. Waypoint targets stay drawn as lines.
- Crumbs are stored separately from waypoints, survive closing the app, and are never shown in the waypoint list except as the one trail row.
- Location runs while a trail is being laid or retraced and the compass is in view, and the screen stays awake while retracing, as it does for a target.
- Whether a given watch delivers a double press of its side button to apps is not known. If it does not, the trail is started and switched from the trail row on the waypoints screen instead; that row offers the same actions.

## Coordinates and long names

As already specified: an optional readout of the current position to four decimal places with hemisphere letters, and back-and-forth scrolling for names that do not fit. Both are built on the redesigned screens.

## Removed

The logging screen; the separate color screen; the delete confirmation dialog; the dot markers; opening the list by tapping the compass; leaving the app by swiping right; the All and None buttons (replaced by the header tap).

## Data

- The database gains a table for crumbs. This is the first schema change: it ships with a real migration from version 1, and the schema is exported from now on. Existing waypoints are kept. A destructive fallback is never used.
- Crumbs are in the same database file, so the existing backup rules and the deleted-content guarantees apply to them as they do to waypoints.

## To verify on the physical watch

- Whether a single press of the side button reaches the app, and whether a press from a dimmed screen acts or only wakes it.
- Whether a double press reaches the app as two presses close together.
- The feel of tick feedback, of the lock pulse and of the heartbeat.
- Whether the watch gives apps a color scheme from its watch face (see "Colors from the watch, as built"): no emulator image tested does so by itself, so the accent, north and error colors have been seen following a scheme only on an emulator whose scheme was set through `adb`.
- Whether a right swipe leaves the edit and calibration screens on a watch with Wear OS 6 or later as it does on the Wear OS 7.0 emulator image.
- Everything already listed in the first spec.

## Decided during the build, reported to the owner

- While the logging status has a second line, that line takes the place of the words `Hold flat` or `Calibrate`, and the calibrate prompt's tap area goes with its word. The hold-flat drawing keeps looping, and the words return when the status clears.
- A press while a result is waiting or showing starts a new save.
- A status line that does not fit its slot at full size is drawn smaller, down to three quarters, before it is cut short. `Needs access to precise location` is too long for one line of the status at three quarters, so it is on the status's two lines, `Needs access to` over `precise location`, both in the error color: the first at the size of any status and the second at the size of the smaller line. Both are whole on both screen sizes, on the compass and on the waypoints screen's band, whose height (0.58 of the radius, the same as for `No GPS fix` with its second line) and place are unchanged: over a list with rows it covers the header, a drag that starts on it scrolls the list, and it stays where it is. In the settings the same two lines stand under `Show coordinates`, in the error color at the size of a row's smaller line; they are whole with the row in the middle of the screen, a quarter of the screen above it and a quarter below, on both screen sizes. The row is taller by that line while it shows, and the rows around it keep their places in the list.
- The save continues when the wearer moves to another screen. A result is shown for about two seconds for a save and about three for a failure, on the compass or on the waypoints screen, and that time counts only while one of those two is at rest in view. A result that arrives while neither is waits, and is shown for its full time on whichever of the two is in view next; a showing that is cut short starts again there. The confirming tap is given once.
- The screen stays on while a save is in progress, with or without targets.
- The confirming tap is given once, when the saved result is first shown.
- The first press asks for precise and approximate location together. If only approximate location is granted, nothing is saved, the compass shows `Needs access to precise location`, and the next press asks again.
- `Log here` on the waypoints screen starts the same save and stays on the waypoints screen. While a save is in progress or its result is showing, the status stands at the top of the waypoints screen, over the list and not part of it, so it is there wherever the list is scrolled to: the same words in the same colors as on the compass, with the second line small under them (dim when it is a note such as the accuracy or `Try again outdoors`, and in the error color when it is the second half of `Needs access to precise location`), on a black band that covers the rows passing under it. This holds for a save started from either screen. A touch that goes down on the band is not a tap or a long press on the row under it, and a drag that starts on the band still scrolls the list. The header keeps showing the count, and takes no tap while a status shows.
- The three screens: an app that is started afresh opens on the compass. An app that was left with the crown and is still running comes back on the screen it was left on.
- Back, on a watch that has it, turns one screen toward settings from each of the three screens and never leaves the app. Backs made close together each turn one screen.
- A screen opened from a row comes back to the screen it was opened from. If the system has stopped the app in the meantime and restores that screen, going back from it shows the compass.
- A swipe ends on a whole screen and turns one screen. A second swipe made before the first has come to rest, or a finger that catches the screens while they slide, carries on to the next screen. While the screens are being moved (a finger has them, or they are still sliding) no screen counts as in view: the compass acts on no crown turn, button press, vertical swipe or long press, gives no tick and no confirming tap, and does not count the time of a logging result. The words of a waiting result are drawn on the compass as it passes; its time and its confirming tap start when the screens have come to rest on the compass.
- A touch on the compass becomes one gesture: a sideways swipe turns to the next screen, a vertical swipe moves the focus, and a long press (the system's long-press time) opens waypoints with a light tap. A swipe at an angle goes whichever way the finger first travels far enough. A long press that starts on the calibrate prompt's tap area opens nothing.
- The compass acts on the crown and the side button only while it is at rest in view, and the waypoints screen acts on the side button only while it is at rest in view. A press reaches one screen, the one with the focus. While the screens are moving, a turn or press is dropped. The crown scrolls the list on the waypoints and settings screens.
- While another screen is in view the compass does not listen to the sensor, draws no frames, gives no tick, and does not count the time of a logging result. When it comes back the dial catches up and the ticks stay quiet until it has.
- The screen is kept on for targets only while the compass is in view. It is kept on during a save on any screen, the ones opened from a row included.
- Location for targets runs on all three screens while the app is on screen, as it did on the separate screens before: the waypoints screen shows distances from it.
- The three ticks that show which screen is in view are at the bottom edge on the waypoints and settings screens. On the compass they appear only around a swipe, from the moment the compass starts to move until about a second after it has come to rest, at the bottom of the readout (under the lowest line of text, inside the circle the letters sweep), where nothing on the turning dial passes.
- The window's own swipe-to-dismiss is switched off for the app, so no right swipe on the three screens can close it.
- The waypoints header reads `N of M targets`, with `target` when there is one waypoint (`1 of 1 target`, `0 of 1 target`), and `No waypoints yet` when there are none. With no waypoints the header is the quiet line and a tap on it does nothing.
- A waypoint's row has its name, its distance under the name when a position is known, and its tick at the right. A target's name is in ink at medium weight; the name of a waypoint that is not a target is dim. The header is dim and `Log here` is in the accent color.
- The rows of the waypoints screen keep their full size wherever they are, so a row is 48 dp tall at the edge as in the middle. The two ends of a row follow the round edge: a row above or below the middle is drawn shorter. Rows fade out toward the top and the bottom of the frame and are cut off inside it. The screen opens with `Log here` in the middle, the header above it and the newest waypoint below.
- The scroll position is an arc in the accent color on the frame at 3 o'clock, as long as the part of the list that is on screen. It is not drawn when the whole list fits.
- On the waypoints screen the three ticks that show which screen is in view stand in the frame's own band at 6 o'clock, and the frame's ticks leave a gap for them. The settings screen has the same frame, with its three ticks in the same place.
- A tap or a long press on the waypoints screen acts only while that screen is at rest in view, as on the compass.
- The edit screen has, from the top: the word `Target` with the tick that shows and switches it, in the waypoint's color; the name, with a dot in the waypoint's color beside it, on up to two lines and drawn smaller when it is long; and `Hold to delete` in the error color. Each of the three is 48 dp tall to the touch. The waypoint's color is marked on the wheel by a line across it.
- A touch "goes down on the color wheel" when it lands on the wheel's band (from 0.86 of the radius outward), on the rim outside it, or within 8 dp inside it. Such a touch is never a tap on what is under it and never holds `Hold to delete`, whose touch area reaches under the wheel at its corners. A touch that goes down anywhere else never changes the color, wherever it travels.
- What a touch on the wheel becomes is decided when it has moved far enough to be a drag. If it moves along the wheel at least as much as toward the middle, it picks the color under the finger for as long as it stays within 16 dp of the band; if it then leaves the wheel toward the middle, the color returns to what it was when the finger came down, drawn and stored, the rest of that touch changes nothing, and if it lifts more than half the screen to the right of where it began the screen goes back. If it moves mostly toward the middle from the start, it is an ordinary swipe: it changes no color and goes back like any right swipe. A touch that lifts without moving is a tap and picks the color it landed on, unless it rested longer than the system's long-press time, which changes nothing. Within three tenths of the radius of the center a finger picks no color.
- The crown turns the color by a quarter of a degree for each pixel of scroll, which is 32 degrees for one click of the emulator's crown.
- A color change is drawn at once. It is stored at most four times a second while it keeps changing, when the finger lifts, and when the screen is left, so the stored color is the last one drawn.
- Changes to waypoints (target, all targets, name, color, delete) are applied one at a time in the order they were made. Before this, a color or name change that overlapped another change to the same waypoint could undo it.
- `Hold to delete` takes one second. While it is held the color wheel gives its place to a ring in the error color that starts whole and drains toward 12 o'clock, leaving the frame's ticks behind it. Lifting the finger, moving it off the words' touch area or starting a swipe ends the hold: nothing is deleted and the wheel returns within about a fifth of a second. When the second is up the watch gives the long-press tap, the waypoint is deleted and the screen closes, once.
- A new name that is empty or only spaces keeps the old name.
- For a screen reader: a waypoint's row is a switch with the actions `Set target` or `Clear target` and `Edit`; the header's action is `Set all as targets` or `Clear all targets`; the name's action on the edit screen is `Rename`; `Hold to delete` is described as `Hold to delete` with the waypoint's name and has the long-press action `Delete`; the wheel is described as `Color` with its hue and can be set.
- The settings screen is a list inside the frame, like the waypoints screen: a dim header `Settings`, a row for each switch, `Calibrate`, and a last faint line with the app's name and version and `MIT License`. It opens with `True north` in the middle and the header above it. The scroll position is the same arc at 3 o'clock, and the rows keep their 48 dp height and fade out toward the top and bottom.
- A switch's row has its label, a smaller line under it, and its tick at the right; the whole row is the switch. The label is in ink at medium weight when the switch is on and dim when it is off. The smaller lines are: `Off: magnetic north`, `Off: meters and km`, `Faint tap at each tick mark`, `Pulses when facing the target` (Blind navigation), `Double press starts a trail` (Breadcrumb trail), `GPS position on the compass`, `With this watch's backup`.
- `Calibrate` is in the accent color with `Accuracy:` and the level (`High`, `Medium`, `Low`, `Unreliable`) under it. That line shows the level the magnetometer last reported while the compass or calibration had the heading sensor running, and is empty until there has been one. The settings screen does not run the sensor.
- The rows of the settings screen reach the ring their ends follow, 10 dp further out on each side than the rows of the waypoints screen. A row in the middle of the screen shows both its lines whole on both screen sizes. In a row above or below the middle, where the ends come in, a line that would reach the tick fades out before it.
- A tap on the settings screen acts only while that screen is at rest in view, and no switch takes a tap until the stored settings have been read. A side button does nothing there.
- Turning on `Show coordinates` without the location permission asks for precise and approximate location together, as the first save does. If precise location is not granted the switch stays off and the row's smaller line reads `Needs access to precise location` on two lines in the error color until the row is tapped again; if it is granted the switch turns on with no second tap.
- Blind navigation and Breadcrumb trail are stored with the other switches in the settings file. Where nothing is stored for them (a fresh install, an install updated from a build without them, a settings file that was found damaged and reset, a settings file that cannot be read) Blind navigation reads on and Breadcrumb trail reads off. Until the two features are built, the switches store their value and nothing else reads it.
- The calibration screen has, from the top: `Move your wrist in a figure 8`, the figure 8 with a dot in ink going round it, the accuracy level as the large word in ink (`Unknown` until the magnetometer has reported one), and `Field:` with the field strength in whole microtesla under it. The frame's fill is the confirming green, starts at 12 o'clock, runs clockwise and takes about 0.4 seconds to move to a new level. A level other than the four the sensor defines fills nothing.
- The interference warning reads `Magnetic interference nearby. Move away from chargers, magnets and steel.` in the error color, in its own place under the field strength, and shows while the field is below 25 or above 65 microtesla. Nothing else on the screen moves when it comes or goes. While it shows, the whole frame is filled in the error color in place of the green, whatever the accuracy level, `Unreliable` and `Unknown` included; the accuracy word and the field line stay as they are, and when the warning goes the frame is again as full, in green, as the accuracy alone makes it. The emulators report only `High`, so the other levels are shown by unit tests of the rule (`calibrationRing`) and were not seen on a screen.
- The crown and a side button do nothing on the calibration screen. Back and a right swipe each leave it and show the screen it was opened from, with the settings list where it was.

- The heading sensor (the rotation vector, with the magnetometer for accuracy and field strength) runs while the app is on screen and either the compass is on screen (at rest in view, or passing during a swipe) or calibration is open. It does not run on the settings, waypoints or edit screens. It stops two seconds after it is no longer wanted, so going from the compass to calibration does not stop and start it. This is one decision (`headingWanted` in `nav/Panes.kt`), made above all the screens.
- The sensor runs while the compass is passing during a swipe as well as when it is at rest in view: the dial is visible then and is drawn live.
- Across a stop and a start of the sensor the accuracy level and the field strength shown are the last the magnetometer reported, until its first report after the start replaces them; a restarted sensor's first headings come before that report. Calibration therefore opens on the level it last showed, with no `Unknown` between, and interference left over from before a stop lasts no longer than the first new field strength.
- Under a screen opened from a row (edit, calibration) every one of the three screens is hidden in the sense used above: it takes no button, runs no frame loop, gives no tap and counts no time. Up to API level 35 the navigation library draws the three screens behind such a screen while it is dragged aside, as a picture; above it, nothing is drawn behind but black until the screen has left, and the three screens are built again only then. The picture drawn behind on those levels is of screens built at that moment and not yet running: a compass there shows `--` and a dial with north at the top until the screen above has left. The three ticks that show which screen is in view appear on the compass only when the row of screens has moved, not when a screen that covered the compass leaves.

### Colors from the watch, as built

- Three colors follow the watch: the accent, the north color and the error color. The confirming green, the ink and the grays, the waypoint colors, the soft tint and the breadcrumb color do not. There is no setting for this.
- The scheme is read with `dynamicColorScheme` of the Wear Compose Material 3 library the app already uses (1.7.1; no dependency was added). Its documentation (https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/package-summary, "Added in 1.5.0") says it creates "a color scheme based on the current watchface", that "If the user changes the watchface colors, this color scheme will change accordingly", and that it "checks whether the dynamic color scheme can be used and returns null otherwise". The documentation names no Wear OS version. The 1.7.1 code returns a scheme only on API level 35 or above and only while the system's global setting `dynamic_color_theme_enabled` is 1; the scheme's primary is the system color `system_primary_fixed`, its tertiary `system_tertiary_fixed` and its error color `system_error_dark`. The Wear design guide (https://developer.android.com/design/ui/wear/guides/styles/color/system) says "Each Watch face defines two seed colors, used for primary and tertiary palettes."
- Where the library returns no scheme, the accent is `#2F8BFF` and north and the error color are `#FF3B30`.
- What was seen, by image:
  - Wear OS 4 (API 33, `system-images;android-33;android-wear;arm64-v8a`) and Wear OS 5 (API 34): no scheme; the blue and the red.
  - Wear OS 5.1 (API 35, `system-images;android-35-ext15;android-wear;arm64-v8a`): the setting is not set and there is no scheme; the blue and the red. With the setting put to 1 through `adb` the library returns the image's fixed colors (primary `#D9E2FF`, tertiary `#FDD7FA`), of which the primary is too pale to use (saturation 0.149; see below), so all three colors are the app's own: the blue and the red.
  - Wear OS 7.0 (API 37, `system-images;android-37.0;android-wear-signed;arm64-v8a`, revision 1, the newest image available, on the AVD `Wear_OS_Newest`): the setting is not set as the image comes, and changing the watch face's color in its editor (Ocean, Sage, Forest) changes neither the setting nor the system's colors, so the app shows the blue and the red. With the setting put to 1 and a seed color written to the secure setting `theme_customization_overlay_packages`, both through `adb`, the image's own system builds a scheme from the seed and the three colors follow it. This is how the two schemes in `accent-a-large.png` (seed `2F8BFF`) and `accent-b-large.png` (seed `00AA55`) were made.
  - No image was found on which the watch face itself changes the scheme, and no physical watch was tested.
- A color from the scheme is used as it is unless one of these applies; the rules are pure functions in `nav/WatchColors.kt`:
  - Contrast: a color with less than 4.5 to 1 against black (the ratio the Web Content Accessibility Guidelines ask of small text) is mixed with white just far enough to have it, which keeps its hue.
  - Gray: a color whose saturation is under 0.15, as it stands or once lightened, is not used. If that is the scheme's primary (a near-black or gray one), the scheme is not used at all and all three colors are the app's own. If it is the tertiary or the error color, only that role has the app's red.
  - North apart from the pointer: if north's hue is less than 60 degrees from the accent's, it is turned to 60 degrees from it, the way round it already leans (clockwise when the two are the same). Of the seven schemes seen on the Wear OS 7.0 image, the system's own pair was the same color in one, 32 to 57 degrees apart in five, and 154 degrees apart in one.
  - North among the ticks and apart from the pointer: on the color that is drawn, in whole red, green and blue values, north's saturation is at least 0.5, its hue is at least 60 degrees from the accent's, and its contrast with black is at least 4.5 to 1. A tertiary that already meets all three is used as it is. Otherwise its hue is turned to 60 degrees from the accent's, it is given 0.5 of saturation, and it is made brighter until it reads; one that is too dark even at full brightness is made paler, which stops above 0.5 at every hue. The scheme colors seen had 0.19 to 0.31 in six of those seven schemes, and a north that pale was hard to pick out from the ink ticks beside it. The accent and the error color are not deepened.
  - The error color apart from the accent: an error color that is less than 60 degrees of hue and less than 0.3 of saturation from the accent is replaced by the app's red `#FF3B30`. With a pink scheme the two were `#FFBEC7` and `#FFBEB9`, 13 degrees and 0.02 apart, and `Log here` and `Hold to delete` differed only in their words. The error color is not compared with north.
  - The app's own blue and red pass through these rules unchanged.
- A waypoint's color is the wearer's own and is not changed; it can come close to a themed accent or north (a violet waypoint beside a violet north was seen).
- When the colors change: the app reads the scheme when its screen is created and each time it comes back on screen. While the app is on screen a change of the setting alone is not followed until the wearer leaves and returns. When the system changes the scheme's colors it restarts the app's screen, whether the app is on screen or not; the new colors are shown at once, and the row of three screens is back on the compass.
- Turning the dial records nothing and composes nothing, as before. Coming back to the app records the dial once, as before, in the colors of that moment.
- On the Wear OS 7.0 emulator image the three screens answer swipes, swipes from the left edge and Back as on API 35, and no swipe closes the app; the crown and the side button reach the compass after each way of arriving on it.
- Leaving the edit and calibration screens by a right swipe: up to API level 35 the navigation library swipes its screens back itself. Above it (Wear OS 6 and later) the library only listens for the system's back gesture, which the app's window has switched off so that no swipe on the three screens can close the app; as first built, a right swipe therefore did not leave either screen on the Wear OS 7.0 image. Above API level 35 the app now puts those two screens in a swipe box of its own (the same one the library uses on the older levels). On the Wear OS 7.0 image 10 of 10 right swipes left the edit screen with the color unchanged and 10 of 10 left calibration, Back left both, a drag along the wheel still changed the color, a hold that started on the wheel deleted nothing, and holding `Hold to delete` deleted the waypoint and closed the screen once; on API 35 the same checks gave the same results.
