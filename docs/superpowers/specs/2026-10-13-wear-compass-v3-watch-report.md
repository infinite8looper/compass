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

- A double tap is two taps whose second goes down within the system's double-tap time after the first is let go (and not sooner than its minimum), no further apart than the system's double-tap distance, with the compass at rest in view when the first went down and when the second was let go (`nextTap`, `nav/Inputs.kt`). Each tap is taken when it is let go; a tap that is no partner of the one before it waits for one in its turn, so a stray tap does not use up the double tap after it, and a third tap close behind a double tap is not a second one (fix round 1, 2026-10-14). The long press comes at the system's long-press time as before. A tap followed by a held touch is a long press; a tap followed by a swipe is a swipe.
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
- The vibration at the press is `HapticFeedbackConstants.VIRTUAL_KEY`, played before the permission or a position is asked for, on every route but the double pinch, whose library plays its own. The confirming vibration of a save (`CONFIRM`) waits until 150 ms after the press's, so that on an instant save it does not cut the press's short; on the emulators both are recorded `finished`.
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
- The target's vibration is not held back by a line played just before it (the line's is cut short); a line is not played while the target's lasts (its primitives' durations, at least 150 ms). Only the pointer's motion passes the target: a new position that moves the bearing past a still pointer gives nothing.

## Colors

- A setting `Colors`: `Standard` or `Watch face`. Default `Standard`.
- `Standard`: north is red, the pointer and the wearer's position are blue, a waypoint may have any color of the wheel. Nothing is read from the watch.
- `Watch face`: the colors come from `dynamicColorScheme`. A pale primary is deepened and kept, not thrown away. More of the instrument follows the scheme than in v2: the pointer, north, the words that can be tapped, the switch ticks, and the ink and the grays where the scheme gives them, provided each still reads against black (the contrast checks of `nav/WatchColors.kt` stay).
- Where the watch gives no scheme (the library returns nothing), `Watch face` cannot be chosen and the row says so.
- The check screen shows what the watch returned, so the cause on the owner's watch can be read off.

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

For the owner to approve or change: `Colors`, `Standard`, `Watch face`, `Not available on this watch`; the calibration instruction `Turn your wrist slowly in every direction`; the ring's label `Covered`; the accessibility action `Save waypoint here` (also built as the label the double pinch is given, which the system may show or speak); the trail row's words on a watch with no button (built as `Trail row on waypoints`); the dial's name for a screen reader (built as `Compass`).
