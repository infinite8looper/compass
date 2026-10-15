# Wear compass v3: changes after the first run on a watch

Date: 2026-10-13. Follows `2026-10-11-wear-compass-v2-redesign.md`, which stays in force wherever this document does not change it.

## Where this comes from

The owner ran a debug build (commit becfa3e) on a Pixel Watch 3 (Wear OS, API 37) on 2026-10-13 and reported, in these words:

> ok, i was able to open it on my phone-- lots of good stuff!
>
> the compass seems to work well.
>
> double clicking the secondary (non-stem) button does *not* work: it opens the recents app or debugging settings. so we need a different way of setting waypoints. what about pinch gestures, or tapping the display?
>
> other issues:
>  - the "calibration" mode is really difficult to use. i can't move my wrist in a figure 8, and the screen keeps turning off. plus, there's no indicator to say whether i'm doing it correctly.
>  - swiping from the compass --> waypoints --> settings is slow and the display stutters. it doesn't feel like a smooth animation. scrolling through the settings menu also has poor performance. needs optimization.
>  - detecting breadcrumb mode should be *on* by default
>  - icon still needs to be fixed (looks smaller than the other icons currently)
>  - theming from the watch face isn't being picked up. also: let's add a toggle to either use custom theming (from watchface) OR to use unthemed mode (default), which draws north in red, current location in blue, and uses the full color wheel for waypoints
>  - "MAG" displays both when using magnetic north and when using true north
>  - accuracy of compass seems to drift. e.g. turning around several times in place causes north to point in a different direction with each rotation.
>  - there should be a "deeper" vibration when the current direction indicator passes over one of the thicker (major) compass ticks, and another (middle?) vibration to denote passing over the diretion of the current goal/target. i.e. we need to distinguish from the minor ticks.
>  - splitting gps coords doesn't work well. let's adjust so they're displayed together. may need a redesign/rethink
>  - laying down a waypoint is too slow-- it should be instant. needs optimization.

Asked four questions the same day, the owner answered:

> 1. system takes single presses too
> 2. i've been indoors. but i *do* have a GPS fix
> 3. saving a waypoint takes a couple of seconds. yes, also indoors (but i had a GPS fix)
> 4. yes, build whatever you need. BUT i'm going to leave this session running overnight, and my watch won't be near. so we'll need to test on the watch tomorrow. get as far as you can independently without it and i'll check back tomorrow.

(The questions: 1, does a single press of the side button save a waypoint; 2, was the watch indoors when `MAG` showed under true north; 3, how slow is saving; 4, may a release-style build signed with this computer's local debug key be built and installed on the watch for performance testing.)

A read-only diagnosis of each item, with file and line, is the starting point for the tasks. Nothing in it was measured on a device.

## Decided for the owner, to accept or change

The owner was away while this was written. Each line below is the controller's choice, not the owner's, and each can be reversed. The words shown to wearers are listed at the end.

1. **Saving from the compass is a double tap on the dial.** On a watch that offers it (Wear OS 7, API 37, Pixel Watch 3 and later), a double pinch does the same. Both do what a single press of the side button did: save a waypoint, or drop a crumb while a trail is being laid. A single tap stays unused, so a brush of the screen does nothing.
2. **A trail is started and ended from the `Trail` row** on the waypoints screen, as today. No gesture on the compass does it.
3. **The side button stays for watches that hand it to apps.** The app asks the watch whether it has an assignable button; where it has none, the settings do not mention a double press, and nothing waits 300 ms.
4. **`Breadcrumb trail` is on by default**, also for an install that never touched the switch.
5. **A save is instant when the app holds a position.** The press is answered with a vibration at once. A position not older than 10 seconds is written immediately with its own accuracy, and is replaced in place if a more accurate one arrives within 10 seconds of the press. With no position in hand the save waits as it does today. This reverses the v2 rule that only a position taken after the press may be saved.
6. **Location runs whenever the compass or the waypoints screen is on screen** and the permission is held, so that a position is nearly always in hand. This costs battery while the app is open.
7. **True north gets a position by itself.** With true north selected, the permission held and no position known, the app asks for one. Selecting true north without the permission asks for it. `MAG` still shows for as long as the dial really is magnetic.
8. **Three vibrations for the dial**: a light one for the thin lines, a deeper one for the thick lines (every 30 degrees), and a third, in between and of a different shape, when the pointer crosses the direction of the focused target, with or without `Blind navigation`. The app adds the `VIBRATE` permission, which needs no prompt, to play them.
9. **A `Colors` setting with two choices**: `Standard`, the default (north red, the wearer's position and pointer blue, any color of the wheel for a waypoint), and `Watch face` (the watch's own colors).
10. **Calibration** keeps the screen on for up to two minutes, drops the figure 8, and shows a ring that fills as the wrist turns through new directions, with the sensor's own accuracy beside it.
11. **Heading drift is not changed blind.** The app gains a second way to compute the heading and a check screen that shows the two side by side on the watch. Which one the compass uses is decided after the owner has turned in place with it.
12. **Coordinates stand together** as two adjacent lines in the lower half of the readout; the prompt and a status's second line move up.
13. **The icon** gets a colored disc that fills the round mask, with a larger mark.
14. **A build for the watch**: a build type that is optimized like a release and signed with the local debug key, for testing only.

## Inputs

- A double tap anywhere on the compass dial, in view, saves a waypoint, or drops a crumb while the trail mode is laying. It goes through the same view-model call, status band and permission flow as the side button's single press did.
- Double tap, long press and the swipes do not interfere: a long press still opens waypoints, a vertical swipe still moves the focus, a sideways swipe still turns the screen. The tap area of the `Calibrate` prompt keeps its tap.
- On API 37 and above the dial carries the one-handed primary action (`Modifier.oneHandedGesture`, in the Wear Compose Material 3 library already in use), mapped to the same call. The documentation says the library does nothing on a watch without the framework.
- `WearableButtons` is asked once whether the watch has an assignable button. Where it has, the side button works as in v2. Where it has not, the key handling is not installed, the 300 ms wait does not exist, and the settings row for the trail does not speak of a press.
- The accessibility tree gains a custom action on the dial for saving, so the action does not depend on a gesture.

As built (Task 2, 2026-10-13):

- A double tap is two taps whose second goes down within the system's double-tap time after the first is let go (and not sooner than its minimum), no further apart than the system's double-tap distance, with the compass at rest in view when the first went down and when the second was let go (`nextTap`, `nav/Inputs.kt`). Those times are the touch events' own (`PointerInputChange.uptimeMillis`, read before the tap detector handles the event), not the times the app handles them, as Android's own double-tap detector measures it; on a busy API 34 emulator touches were handled 60 to 215 ms late, which had made two taps 120 ms apart measure 359 ms (fix round 1, continued, 2026-10-14). Each tap is taken when it is let go; a tap that is no partner of the one before it waits for one in its turn, so a stray tap does not use up the double tap after it, and a third tap close behind a double tap is not a second one (fix round 1, 2026-10-14). The long press comes at the system's long-press time as before. A tap followed by a held touch is a long press; a tap followed by a swipe is a swipe.
- The one-handed primary action is registered only while the compass is at rest in view. The library acts on every registered gesture whose screen it takes to be active, and the ring of screens does not tell it which screen that is, so the app composes the modifier in and out. When the gesture arrives the library plays a vibration of its own (its long-press feedback). No emulator has the gesture service, API 37 included, so the double pinch was never delivered to the app.
- The watch has an assignable button when `WearableButtons.getButtonInfo` describes one of `KEYCODE_STEM_1`, `KEYCODE_STEM_2`, `KEYCODE_STEM_3` and `getButtonCount` is above one or unknown (`hasAssignableButton`). This is what the watch says: a watch can describe a button and keep its presses for itself, and what the Pixel Watch 3 says is not known. The emulator images at API 33, 34, 35 and 37 report a count of 0 and describe no key.
- The trail row's words on a watch with no button, proposed: `Trail row on waypoints`.
- For a screen reader to stop on the dial and offer `Save waypoint here`, the dial has a name, proposed: `Compass` (the app's name). No screen reader was run.

## Saving

- The press (tap, pinch, button or the `Log here` row) gives a vibration at once, before any position is asked for.
- A position in hand, not older than 10 seconds at the press, is written at once with its accuracy. The status band says saved at once. The poor-fix note of v2 applies to what was written.
- For 10 seconds after the press the app keeps listening; a position with a smaller reported error replaces the waypoint's position and accuracy in place, once or more. This stops as soon as the waypoint is edited, moved or deleted, and never brings a deleted waypoint back.
- With no position in hand the v2 path runs unchanged (`Saving…`, a position of 15 m or better taken after the press, 20 seconds at most).
- A crumb follows the same rule.
- The pure decision (use in hand, wait, refine or not) lives in `nav/` and is unit-tested first.

As built (Task 3, 2026-10-14):

- The position in hand is the latest position the view model holds from its own location updates (the screens' 5 s updates, a refinement's 1 s updates and a fix a waiting save took: `inHand`), and, without one of those not older than 10 seconds, the location provider's last known position, asked for at the press and waited for at most 500 ms (`positionInHand`; fix round 1). Ages are the press's time minus the position's, both on the clock that counts from boot. `positionToSaveAtOnce`, `refines`, `refineWindowLeftMs` and `refinementMayWrite` in `nav/Navigation.kt` decide; a position with no accuracy is not saved at once.
- With a position in hand the status is `Saving…` with that accuracy from the press until the row is written, then `Saved …`; on the API 35 emulator the row was written 6 to 50 ms after the press. The last position is stored after the row, without being waited for.
- During the 10 seconds the app asks for positions once a second, only while the screens are a reason for location: Home, the screen off, settings or a screen opened from a row end the request at once (fix round 1). A replacement is written under the lock the edits take, from the row as it is then, and only while the row still holds the position the save (or the last replacement) wrote: a delete or a change of position ends it, a new name or color does not. The press's status is not changed by a replacement: it says what the save wrote, and a result waiting to be shown stays as it was. A second save in the 10 seconds is a row of its own, and each row is refined.
- The vibration at the press is `HapticFeedbackConstants.VIRTUAL_KEY`, played before the permission or a position is asked for, on every route but the double pinch, whose library plays its own. The confirming vibration of a save (`CONFIRM`) waits until 250 ms after the press's was asked for, so that on an instant save it does not cut the press's short; on the emulators both are recorded `finished`. Until 2026-10-14 it waited 150 ms: on the emulators the press plays for up to 112 ms, and a loaded API 34 emulator started a press 67 ms after it was asked for, so the confirm cut it short (`DialVibrationsTest`); 250 ms covers the press and twice that latency (`SAVE_CONFIRM_AFTER_PRESS_MS`). A newer press may still cut an older press's short, as any vibration the app asks for stops the one playing.
- In the debug and watch builds (`BuildConfig.DIAGNOSTICS`) each save logs one line under the tag `CompassSave` when its listening has ended: milliseconds from the press to the row written, whether the position was in hand or waited for, its age at the press and its accuracy, and whether it was refined and to what accuracy. No coordinates and no name.
- The API 33, 34 and 35 emulator images give every position from the geo console an accuracy of 5 m; the API 37 image gives accuracies that vary (3.0 to 3.8 m seen), and there the app's own updates refined saves in the suite. The tests hand the view model a position more accurate than any emulator gives through the call its updates make, to check each case.

## Location while the app is on screen

- With the precise permission held, location updates run while the compass or the waypoints screen is in view, at the present interval, and stop when neither is.
- The declination is known as soon as one position is. With true north selected and no position stored, the first position is asked for at once.
- Selecting `True` in the north setting without the permission opens the system's permission dialog once; declining leaves the setting on true and the dial on magnetic with `MAG`.
- The screen-awake rule does not change: this does not keep the screen on.

As built (Task 3, 2026-10-14):

- The screens are a reason for location while the app is started (on screen), the three screens are on top and the compass or the waypoints screen is on screen, at rest or passing in a swipe (`screensWantLocation`, `nav/Panes.kt`). Whether the app is on screen is taken from each lifecycle event as it comes. Tested exits: a swipe to settings, the edit screen, calibration, Home, and the screen put to sleep. A position composes only the compass's readout (`CompassReadout`), the waypoint rows and the waypoints' status band, not the screens (fix round 1).
- True north is a reason for location while it is selected and no position has ever been stored (`trueNorthWantsPosition`), on any screen while the app is on screen. Once the first position is stored it is no reason any more, and the declination comes from the stored position.
- `MAG` and the heading's magnetic state come from one rule, `dialIsMagnetic`.
- Switching `True north` on without the precise permission opens the system's dialog after the setting is stored; the answer only refreshes the permission state. This path is driven by `scripts/screenshot-matrix.sh <suffix> truenorth` (wiped data, `True north` off and on again, `Don’t allow`) and was looked at in pictures on both round sizes, not by an instrumented test: a test process cannot give up a permission it holds without being ended.

## Vibrations

- The kind of line crossed is decided in `nav/`: thin (every 2 degrees), thick (every 30 degrees, the cardinal points among them), and the bearing of the focused target.
- One crossing gives one vibration, the strongest kind that applies. The existing limits stay (not while the dial is catching up, at most one per 40 ms, not right after a lock pulse).
- With the `VIBRATE` permission the app plays composition primitives where the watch says it supports them (`areAllPrimitivesSupported`), and the `HapticFeedbackConstants` it uses today where it does not. Which primitive plays for which kind is fixed in one table in the code.
- The target's crossing vibration plays with `Blind navigation` off as well. With it on, the lock pulse of v2 remains and the crossing vibration is not added to it.
- How different the three feel can only be judged on a wrist. The check screen plays each on a tap.

As built (Task 4, 2026-10-14):

- The table (`ui/Vibrations.kt`): thin `TICK` at 0.5, thick `THUD` at 1.0, target two `CLICK`s at 0.7 50 ms apart; without primitives `SEGMENT_FREQUENT_TICK` (`CLOCK_TICK` on API 33), `GESTURE_START` and `CONFIRM`, chosen because on the four emulator images they play three different effects, none the press's. The press, the confirming vibration, the lock, the heartbeat and the long press are rows of the same table, as haptic feedback constants.
- Implementer's ruling, for the owner: the three are played only with `Tick feedback` on.
- Controller's ruling: the target's vibration is silent while the position is old, as blind navigation is.
- The target's vibration is not held back by a line played just before it (the line's is cut short); a line is not played while the target's lasts (its primitives' durations, at least 150 ms), and neither is the target's again (fix round 2: a pointer rocking on the bearing can pass it again sooner, and each new one would cut the one before short). Where the target plays as `CONFIRM`, whose length the app cannot ask, that time is 300 ms, the length of `CONFIRM` on the emulator images. Only the pointer's motion passes the target: a new position that moves the bearing past a still pointer gives nothing.

## Colors

- A setting `Colors`: `Standard` or `Watch face`. Default `Standard`.
- `Standard`: north is red, the pointer and the wearer's position are blue, a waypoint may have any color of the wheel. Nothing is read from the watch.
- `Watch face`: the colors come from `dynamicColorScheme`. A pale primary is deepened and kept, not thrown away. More of the instrument follows the scheme than in v2: the pointer, north, the words that can be tapped, the switch ticks, and the ink and the grays where the scheme gives them, provided each still reads against black (the contrast checks of `nav/WatchColors.kt` stay).
- Where the watch gives no scheme (the library returns nothing), `Watch face` cannot be chosen and the row says so.
- The check screen shows what the watch returned, so the cause on the owner's watch can be read off.
- The owner's answers of 2026-10-14, verbatim. On how deep a pale primary is deepened: "Deepen to ~0.3 saturation". On the waypoints' colors: "in standard (non-theme) colors use the full color wheel and start with something away from the existing red/blue colors. for themed colors make sure the wheel has *3* anchorpoints-- otherwise the wheel will have repetitions (i.e., not actually a wheel)". Later the same day, on where the first new waypoint's color is: "new waypoint should be near some other color-- when unthemed, how about near blue/green or yellow/green?"; and on the row's click label, `Use watch face colors` / `Use standard colors`: "approved". Later again, on new waypoints' places, the owner chose "Skip red and blue (Recommended)", whose text was: "New waypoints never land within about ±25° of north's red or the pointer's blue; a place that would is moved on to the next one. Existing waypoints keep their colors."

As built (Task 5, 2026-10-14, and its fix round the same day):

- The setting is stored in the settings file (`colors`, the name `STANDARD` or `WATCH_FACE`), which is backed up with the others; nothing stored, a name that is neither, a value that is not text, and a file that cannot be read are all `Standard` (`colorModeOf`, `SettingsStore.colorsIn`). The activity reads it before it shows anything and follows it while it is on screen.
- `Standard`: the pointer `#2F8BFF`, north and the error color `#FF3B30`, the ink `#E8EAED`, the secondary text `#9E9E9E`, the faint ticks `#6E6E6E`, the dial's numbers `#7C7C7C`, and a waypoint's stored place (0 to 360) taken as a hue on the full wheel at 0.65 of saturation and full brightness (`wheelColor`), as waypoints were colored in v2 before the ring. New waypoints are given places 137.508 degrees apart (the golden angle) as in v2, now starting at 108.3 (`FIRST_WAYPOINT_PLACE`), the middle of the widest gap of hue between north's red (3.2) and the pointer's blue (213.5), a green; until then they started at 0, a red beside north's. A new waypoint's place is chosen from that numbering (`nextWaypointPlace`, fix round 2): a place within 25 degrees (`SKIP_HUE_APART`) of north's red or the pointer's blue is passed over, and so is one already taken; of the first eight left, the one furthest from the places of the waypoints there are is given, the earliest of those as far. So no new waypoint lands within 25 degrees of the red or the blue (the numbering's third, seventh and eighth places, 23.3, 213.4 and 350.9, are never given), the first is still 108.3, and on an install whose waypoints were numbered from 0 a new place is not put beside an old one: with eight such waypoints the numbering's next place, 108.3, was 3.3 degrees from one, and now the next three are each at least 23 degrees from every place taken. On a new install the first eight are 108.3, 298.4, 180.9, 43.4, 245.8, 75.9, 148.5 and 265.9. The place is chosen and the waypoint stored under one lock, so two saves made together get two places. A waypoint keeps the place it was given: the places of existing waypoints are not changed, and a color set by hand on the edit screen is not held to the 25 degrees. The colors are never made from the scheme in this mode (`paletteFor` ignores it). The library is still asked whether it gives a scheme, so that the row can say whether `Watch face` can be chosen; what it gives is not kept.
- `Watch face`: the three roles as in v2 (`roleColors`), with one change: a primary with less than 0.30 of saturation once it reads (`ACCENT_MIN_SATURATION`), but at least 0.10 (`GRAY_SATURATION`), is deepened to 0.30 with its hue and its brightest channel kept, and the scheme is used. Below 0.10 it is a gray and, as before, the whole scheme gives way to the app's own three. The tertiary and the error color are still used from 0.15 (`MIN_SATURATION`). The Wear OS 5.1 image's `#D9E2FF` (0.149) becomes `#B2C4FF`, so on that image the scheme is now used (north `#FD7EF3`, error `#F2B8B5`); the primaries the API 37 image made, 0.28 and 0.22, are deepened to 0.30 too. The accent is told from the ink by its saturation: it has at least 0.2 more (`ACCENT_INK_SATURATION_APART`), checked on the recorded schemes and on 20 000 random ones. The ink, the secondary text, the faint ticks and the dial's numbers take the hue and the saturation of the scheme's `onSurface`, `onSurfaceVariant` and `outline` (the system's `system_on_surface_dark`, `system_on_surface_variant_dark`, `system_outline_dark`), the saturation held under 0.10, at the least brightness at which each has at least the contrast with black of the app's own color for that role (`graysFor`); a dark hue that cannot read that well at full brightness gives up saturation instead. So each reads at least as well as in `Standard` and their order from light to dark is kept. A waypoint's stored place is a color on a ring of three anchors that are at least 60 degrees of hue from one another (`ringThirdFor`): the accent, north, and the error color where it is that far from both; otherwise a color made at the middle of the widest gap of hue between the accent and north, with the mean of their saturations (at least 0.15) at full brightness, lightened until it reads at 4.5 to 1. Errors are still drawn in the error color; only the ring's third anchor changes. On the API 35 image the error color `#F2B8B5` is 58 degrees from north, and the third anchor is `#D4FF98`; with the API 37 seeds the error color `#FFBEB9` is the third. A scheme whose primary gives way (the app's blue, red and red) has the third `#58FF30`. The pointer, north, the words that can be tapped and the switch ticks follow the roles, as in v2. The background stays black; the confirming green and the breadcrumb color are not taken from the scheme.
- The schemes the emulators gave, as primary, tertiary, error, `onSurface`, `onSurfaceVariant`, `outline`: API 35 `#D9E2FF #FDD7FA #F2B8B5 #E2E2E9 #C5C6D0 #8F9099`; API 37 with the seed `2F8BFF` `#B7CFFF #D3C8FF #FFBEB9 #E4E8F4 #CACEDA #A2A5B1`; API 37 with `00AA55` `#BBEFC2 #DCE9A1 #FFBEB9 #E0EBDE #C6D1C4 #9EA99C` (`theme_style` `NEUTRAL` in place of `TONAL_SPOT` gave the same colors). What the app makes of them, as accent, north, error, ink, secondary text, faint, numbers: API 35 `#B2C4FF #FD7EF3 #F2B8B5 #EAEAF1 #9D9EA6 #6D6E75 #7B7C84`; seed `2F8BFF` `#B2CCFF #D47FFF #FFBEB9 #E6EAF6 #9B9EA7 #6C6E76 #7A7C85`; seed `00AA55` `#A7EFB1 #D9E974 #FFBEB9 #E2EDE0 #99A197 #6A7168 #777F75`.
- With no scheme the row reads `Not available on this watch`, takes no tap, and the colors are those of `Standard`; a stored `Watch face` stays stored. Seen on the API 35 and API 37 emulators by putting `dynamic_color_theme_enabled` to 0 and to 1 with `adb shell settings put global`: with 0 the row said `Not available on this watch` and the compass had the blue and the red; with 1 again it said `Watch face`. `ColorsRowTest` does the same through the shell.
- With the API 35 image's scheme the accent deepened to 0.30, `#B2C4FF`, is a clear periwinkle beside the near-white ink `#EAEAF1` (`colors-watchface-large.png`); deepened only to 0.15 (`#D8E1FF`) it had been told from the ink by its shape and place more than by its color.
- Where the first new waypoint's color is. With `Standard` its place, 108.3, is the wheel's `#7AFF59`, between yellow-green and green, as the owner asked. With `Watch face` the ring is read turned (`RING_READ_OFFSET_TURNS`, two thirds of a turn less 108.3 over 360) so that the place 108.3 is the ring's third anchor: the accent is at the place 228.3 and north at 348.3, and their order and the thirds between them are as before. Until then a place was read straight, the accent at 0, and the first new waypoint was nine tenths of the way from the accent to north, close to north's pink (`#F685F4` beside north's `#FD7EF3` on the API 35 image). Now it is the third anchor (`#D4FF98` on the API 35 image, the error color `#FFBEB9` with the API 37 seeds), checked on the recorded schemes and on 20 000 random ones, and in `colors-watchface-large.png` and `-small.png` (`Waypoint 8`). No stored place is changed, so a waypoint made before this keeps its place and is drawn at the turned ring's color for it. The reading allocates nothing.
- The 25 degrees skipped are of the stored place, so they hold in `Watch face` too, where the ring is read as above, unchanged. The places passed over, 188.5 to 238.5 and 338.2 to 28.2, hold the turned ring's accent (228.3) and north (348.3), each about 10 degrees in from an edge, so a new waypoint is read at least 10.1 degrees of place from the accent and from north, a twelfth of the way to the next anchor, and from the rest of the ring as before (fix round 2).
- The row's click label, which a screen reader says, is `Use watch face colors` with `Standard` chosen and `Use standard colors` with `Watch face` chosen (approved by the owner); where the row takes no tap there is no click to label. `ColorsRowTest` reads it from the system's accessibility tree.


## Calibration

- The screen stays on while calibration is open, for two minutes from opening or from the last change of the ring, whichever is later.
- The figure 8 animation goes. In its place a ring of segments: each segment stands for a region of directions that the measured magnetic field has pointed in, in the watch's own frame, and lights when the field has been there. It shows motion covered, and is labeled as that, not as accuracy.
- Beside it: the magnetometer's accuracy as a word, as today, and the rotation vector's estimated heading error in degrees when the sensor gives one.
- The words ask for a motion a wrist can make.
- The `Calibrate` prompt on the compass also shows when the estimated heading error is large, not only at a low magnetometer level. The threshold is fixed in `nav/` and unit-tested.
- The coverage logic is pure and unit-tested with real recorded vectors; the screen is checked on the emulators through their sensor console.

## Heading

- The heading source becomes a choice in the code: the fused rotation vector (today), a heading from the magnetometer and gravity only (the geomagnetic rotation vector where the watch has it, else `SensorManager.getRotationMatrix`), and a blend that follows the fused one from moment to moment and is steered toward the magnetic one with a time constant.
- The blend is pure code in `nav/`, unit-tested.
- The compass keeps the fused source until the owner has compared them on the watch.
- Sensor events are delivered on a thread of their own, not the main thread.

As built (Task 6, 2026-10-15), for this section and the one above:

- Regions (`nav/Calibration.kt`): the directions of the field in the watch's frame are cut into 24 regions of equal area, three bands by the share of the field along the axis out of the face (below -1/3: through the back; between; above 1/3: out of the face), each of eight sectors by the direction across the face, counterclockwise from 3 o'clock, sector 0 centered there. The field is the magnetometer's (calibrated) reading. A field whose strength is outside 25 to 65 microtesla (the interference rule) and a field with no direction light nothing. The regions lit are counted from the screen's opening; the reading held from before it is not counted.
- The ring stands where the figure 8 was, with three layers (through the back outermost, out of the face innermost), lit segments in the confirming green, the others faint; `Covered` and the share lit in whole percent to its left, and `Error` with the estimated heading error to its right when there is one (`±12°`, whole degrees, at least 1). The instruction is `Turn your wrist slowly in every direction`. The frame's fill by the magnetometer's level and the interference warning are as before.
- The estimated heading error is values[4] of the rotation vector's event, in radians, taken only when it is above 0: -1 is "unavailable", and the AOSP rotation vector that the emulator images run copies the accelerometer's event and writes only values[0] to [3] (`RotationVectorSensor::process`, `*outEvent = event`), so there values[4] is always 0. So on the emulators no error is shown and the `Calibrate` prompt comes from the level alone; neither the error's place on the screen nor the prompt from it has been seen.
- The `Calibrate` prompt shows at a level of low or below, as before, or at an estimated heading error above 30 degrees (`LARGE_HEADING_ERROR_DEG`, `calibrationPromptDue`).
- The screen is kept on through the same switch as the compass's reasons (`KeepAwake`), while the calibration screen says so (`CompassViewModel.setCalibrationAwake`): from the opening until two minutes after it or after the last new segment, whichever is later (`calibrationAwakeUntil`, elapsed real time). `CalibrationScreenTest` saw it let go 119.3 s after the opening on API 37 with the watch still.
- Sources (`sensors/HeadingSource.kt`, `HeadingKind` in `nav/Navigation.kt`): each reading carries the fused heading, the magnetic one and the blend (each not a number while it is not worked out), the shown one of the kind asked for, the field vector and the estimated error. The fused source registers the rotation vector and the magnetometer only, as before; the magnetic source and the blend add the geomagnetic rotation vector (or, on a watch without it, the accelerometer at the game rate). A reading comes with each rotation vector event; for the magnetic kind none comes until the first magnetic heading. The pitch for `Hold flat` is the fused sensor's in every kind.
- The blend (`HeadingBlend`): each fused reading moves it by the fused heading's change since the last, then 1 - e^(-dt/5 s) of its difference from the magnetic heading is taken out. Thirty full turns each way at 120 degrees a second with a fused heading drifting 0.6 degrees a second (54 degrees by the end) kept it within 5 degrees of the true heading after the first 15 s, and within 3.5 degrees 10 s after stopping (`HeadingBlendTest`).
- The switch for the running app is `CompassViewModel.setHeadingKind`; a start is always on the fused source (`DEFAULT_HEADING_KIND`), nothing is stored. The check screen's row (Task 7) is to call it.
- On the emulators, lying flat at the headings 0, 90, 200 and 300 set through the console, the fused, magnetic (both ways) and blended headings were each within 3 degrees of the heading set (`scripts/heading-sources-check.sh`), and `scripts/calibration-check.sh` lit 12 of 24 regions (50%) with the fourteen recorded poses, and nothing more with a 92 microtesla field.

## Check screen

- Reached from the calibration screen, only in builds made with the diagnostics switch on (the debug build and the watch build; never a build for a store).
- Shows live: the three headings, the estimated heading error, the magnetometer's level and field strength; what the watch returned for its colors; whether the watch has an assignable button and the one-handed framework; which vibration primitives it supports, with a tap to play each of the three dial vibrations.
- A row switches the compass's heading source for the running app, so the owner can turn in place with each.

## Readout with coordinates

- Latitude and longitude are two adjacent lines, at four decimal places and no smaller than today, in the lower half of the readout.
- The prompt (`Calibrate`, `Hold flat`) and a status's second line take the place the latitude had above.
- Nothing may touch or overlap in any state on either round size; this is shown with the picture matrix, as in v2.
- If no arrangement meets that, the alternatives are shown as pictures for the owner and the best one is built.

## Smoothness

- The three screens exist for good: a turn of the ring never builds a screen, and a list keeps its place.
- No row and no list is drawn through an offscreen buffer while scrolling; the fades are drawn over the text.
- Text styles are remembered.
- Frame times for ten swipes and a settings scroll are recorded on an emulator before and after with `scripts/frame-times.sh`. Emulator numbers compare before with after and say nothing certain about the watch.
- A build type for the watch: minified and optimized as the release is, not debuggable, signed with the local debug key, application id unchanged so it updates the installed app in place. It is never uploaded anywhere.

## Icon

- The background becomes a colored disc that fills the round mask; the mark is enlarged toward the edge of the 66 dp safe zone; the monochrome layer follows.
- Shown beside the system's icons in the launcher on an emulator, before and after.

## Not possible to verify without the watch

Smoothness on the watch; the drift and which heading source cures it; the feel of the three vibrations and whether the watch supports the primitives; whether the watch returns a color scheme; whether the double pinch arrives; whether calibration is now usable; battery with location running while the app is open.

## Words shown to wearers, proposed

Approved by the owner on 2026-10-14: the `Colors` row's click label, `Use watch face colors` / `Use standard colors`. For the owner to approve or change: `Colors`, `Standard`, `Watch face`, `Not available on this watch`; the calibration instruction `Turn your wrist slowly in every direction`; the ring's label `Covered`, and beside it the share lit (`50%`); the estimated heading error's label `Error` and value `±12°` (built by Task 6, not in the spec before); the accessibility action `Save waypoint here` (also built as the label the double pinch is given, which the system may show or speak); the trail row's words on a watch with no button (built as `Trail row on waypoints`); the dial's name for a screen reader (built as `Compass`).
