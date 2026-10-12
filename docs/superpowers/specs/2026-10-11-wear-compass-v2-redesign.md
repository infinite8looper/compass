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
- The crown press is the only way to leave the app. Swipes are for moving between the three screens.

## Changed by the owner after approval

- "when the current direction and goal (target) direction align within 2 degrees, show a thicker line, not a circle (and vibrate/pulse if blind navigation is enabled)". This replaces the ring around the pointer and the 5 degree window in the mockups.
- "Also: enable blind navigation by default".
- On the hold-flat label: "red hold flat text should be another less jarring color... Light gray?" It is light gray.
- On the other targets: "I'm also wondering if all waypoints aside from the active one should be desaturated (near gray) and maybe even partially transparent". From four drawn options the owner chose the soft tint: a pale version of each waypoint's color at about 60 percent strength.
- On colors: "maybe the colors should be drawn using theme consistent coloring to match the watch face?" The owner chose: yes where the watch provides it, for the accent only.
- The `Calibrate` prompt on the compass is light gray too.
- "North should also be drawn in a themed color".

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
- While it works, the distance slot shows a short status: `Saving…`, with the current accuracy once a fix is arriving; then `Saved Waypoint N` with the accuracy on the line under it, for about two seconds, with a light confirming tap; or a failure in red for about three seconds: `No GPS fix`, `Location is off`, `Needs location access`, or `Couldn't save`.
- The first press on a fresh install shows the system's location permission dialog; if granted, the save continues without a second press.
- The rules for which fix is saved are unchanged (15 m or better at once; the best fresh fix after 20 seconds), with one tightening: a fix taken before the press is never saved.
- A second press while a save is in progress does nothing.
- `Saved Waypoint N` is in a soft green, the confirmation color. The accuracy under `Saving…` and `Saved Waypoint N` is small and dim, in the chosen units. `No GPS fix` has `Try again outdoors` under it in the same small dim text; the other failures are one line.
- With no targets the status appears alone under the heading and the compass is clean again when it clears.
- The separate logging screen is removed.

### Getting around

- Three screens sit side by side and wrap around: settings, compass, waypoints. From the compass, swiping left shows waypoints and swiping right shows settings; swiping the same way twice reaches the other one.
- Long-pressing the compass also opens waypoints.
- Tapping the compass does nothing.
- Swiping up or down, or turning the crown, on the compass still moves the focus among the targets.
- The side button acts only while the compass is in view.
- Pressing the crown leaves the app. A right swipe does not.
- Screens opened from a row (edit a waypoint, calibration) go back with the usual right swipe.

### Unchanged

The clean compass with no waypoints, `MAG`, the hold-flat animation, tick feedback, the keep-awake rule, and the calibrate prompt.

## Waypoints screen

- A header line states how many waypoints are targets, for example `3 of 4 targets`. Tapping it sets all as targets, or clears all if all already are.
- First row: `Log here`, which saves a waypoint exactly as the side button does. It exists because not every watch passes its side button to apps.
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
- A right swipe that does not start on the color wheel goes back.

## Settings screen

One tap per switch, in this order: True north; Feet and miles; Tick feedback; Blind navigation; Breadcrumb trail; Show coordinates; Back up waypoints; then Calibrate (showing the current accuracy level, opening calibration) and the version.

New settings: Blind navigation (on by default) and Breadcrumb trail (off by default).

## Calibration screen

The figure-8 guide stays. The frame fills in green by quarters as accuracy improves (none for unreliable, one quarter for low, three for medium, all four for high). The accuracy level is the large text; the field strength is small; the interference warning is red.

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
- Everything already listed in the first spec.

## Decided during the build, reported to the owner

- While the logging status has a second line, that line takes the place of the words `Hold flat` or `Calibrate`, and the calibrate prompt's tap area goes with its word. The hold-flat drawing keeps looping, and the words return when the status clears.
- A press while a result is waiting or showing starts a new save.
- A status line that does not fit its slot at full size is drawn smaller, down to three quarters, before it is cut short. `Needs location access` is one that is drawn smaller.
- The save continues when the wearer moves to another screen. The time a result is shown (about two seconds for a save, about three for a failure) counts only while the compass is in view; a result that arrives while it is not waits and is shown for its full time when the compass is next in view.
- The screen stays on while a save is in progress, with or without targets.
- The confirming tap is given once, when the saved result is first shown.
- The first press asks for precise and approximate location together. If only approximate location is granted, nothing is saved, the compass shows `Needs location access`, and the next press asks again.
- `Log here` on the waypoints screen starts the same save and stays on the waypoints screen. While a save is in progress or its result is showing, the header of the waypoints screen shows the status in place of the count: the same words in the same colors as on the compass, with the second line small and dim under them. This holds for a save started from either screen. The time a result is shown counts while the compass or the waypoints screen is at rest in view, whichever it is; a showing that is cut short starts again on whichever of the two is in view next; the confirming tap is given once.
- The three screens: an app that is started afresh opens on the compass. An app that was left with the crown and is still running comes back on the screen it was left on.
- Back, on a watch that has it, returns to the compass from waypoints and from settings, and leaves the app from the compass.
- A screen opened from a row comes back to the screen it was opened from. If the system has stopped the app in the meantime and restores that screen, going back from it shows the compass.
- A swipe ends on a whole screen and turns one screen. A second swipe made before the first has come to rest, or a finger that catches the screens while they slide, carries on to the next screen. While the screens are being moved (a finger has them, or they are still sliding) no screen counts as in view: the compass acts on no crown turn, button press, vertical swipe or long press, gives no tick and no confirming tap, and does not count the time of a logging result. The words of a waiting result are drawn on the compass as it passes; its time and its confirming tap start when the screens have come to rest on the compass.
- A touch on the compass becomes one gesture: a sideways swipe turns to the next screen, a vertical swipe moves the focus, and a long press (the system's long-press time) opens waypoints with a light tap. A swipe at an angle goes whichever way the finger first travels far enough. A long press that starts on the calibrate prompt's tap area opens nothing.
- The compass acts on the crown and the side button only while it is at rest in view. While the screens are moving, a turn or press that reaches the compass is dropped. The crown scrolls the list on the other two screens.
- While another screen is in view the compass does not listen to the sensor, draws no frames, gives no tick, and does not count the time of a logging result. When it comes back the dial catches up and the ticks stay quiet until it has.
- The screen is kept on for targets only while the compass is in view. It is kept on during a save on any screen, the ones opened from a row included.
- Location for targets runs on all three screens while the app is on screen, as it did on the separate screens before: the waypoints screen shows distances from it.
- The three ticks that show which screen is in view are at the bottom edge on the waypoints and settings screens. On the compass they appear only around a swipe, from the moment the compass starts to move until about a second after it has come to rest, at the bottom of the readout (under the lowest line of text, inside the circle the letters sweep), where nothing on the turning dial passes.
- The window's own swipe-to-dismiss is switched off for the app, so no right swipe on the three screens can close it.
- The waypoints header reads `N of M targets` for every count, `1 of 1 targets` and `0 of 3 targets` included, and `No waypoints yet` when there are none. With no waypoints the header is the quiet line and a tap on it does nothing.
- A waypoint's row has its name, its distance under the name when a position is known, and its tick at the right. A target's name is in ink at medium weight; the name of a waypoint that is not a target is dim. The header is dim and `Log here` is in the accent color.
- The rows of the waypoints screen keep their full size wherever they are, so a row is 48 dp tall at the edge as in the middle. The two ends of a row follow the round edge: a row above or below the middle is drawn shorter. Rows fade out toward the top and the bottom of the frame and are cut off inside it. The screen opens with `Log here` in the middle, the header above it and the newest waypoint below.
- The scroll position is an arc in the accent color on the frame at 3 o'clock, as long as the part of the list that is on screen. It is not drawn when the whole list fits.
- On the waypoints screen the three ticks that show which screen is in view stand in the frame's own band at 6 o'clock, and the frame's ticks leave a gap for them. The settings screen keeps the earlier place of its three ticks until it gets its frame.
- A tap or a long press on the waypoints screen acts only while that screen is at rest in view, as on the compass.
- The edit screen has, from the top: the word `Target` with the tick that shows and switches it, in the waypoint's color; the name, with a dot in the waypoint's color beside it, on up to two lines and drawn smaller when it is long; and `Hold to delete` in the error color. Each of the three is 48 dp tall to the touch. The waypoint's color is marked on the wheel by a line across it.
- A touch "starts on the color wheel" when it goes down on the wheel's band (from 0.86 of the radius outward), on the rim outside it, or within 8 dp inside it. Such a touch belongs to the wheel alone: it never goes back and never holds `Hold to delete`, whose touch area reaches under the wheel at its corners. A touch that starts anywhere else never changes the color, wherever it travels.
- On the wheel, a finger that moves picks the color under it for as long as it stays down, and a tap picks the color it lands on. A finger that rests on the wheel for longer than the system's long-press time and lifts without moving changes nothing. Within three tenths of the radius of the center a finger picks no color.
- The crown turns the color by a quarter of a degree for each pixel of scroll, which is 32 degrees for one click of the emulator's crown.
- A color change is drawn at once. It is stored at most four times a second while it keeps changing, when the finger lifts, and when the screen is left, so the stored color is the last one drawn.
- Changes to waypoints (target, all targets, name, color, delete) are applied one at a time in the order they were made. Before this, a color or name change that overlapped another change to the same waypoint could undo it.
- `Hold to delete` takes one second. While it is held the color wheel gives its place to a ring in the error color that starts whole and drains toward 12 o'clock, leaving the frame's ticks behind it. Lifting the finger, moving it off the words' touch area or starting a swipe ends the hold: nothing is deleted and the wheel returns within about a fifth of a second. When the second is up the watch gives the long-press tap, the waypoint is deleted and the screen closes, once.
- A new name that is empty or only spaces keeps the old name.
- For a screen reader: a waypoint's row is a switch with the actions `Set target` or `Clear target` and `Edit`; the header's action is `Set all as targets` or `Clear all targets`; the name's action on the edit screen is `Rename`; `Hold to delete` is described as `Hold to delete` with the waypoint's name and has the long-press action `Delete`; the wheel is described as `Color` with its hue and can be set.
