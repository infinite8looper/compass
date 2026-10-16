package com.infinite8looper.compass.nav

// Words the wearer sees that were added after the owner approved the rest, kept together so that changing one is a
// change to this file alone.

/** The row of the waypoints screen that drops a crumb while a trail is being laid. */
const val DROP_CRUMB = "Drop crumb"

/** What an accessibility service is told the dial is: the app's name. The dial needs a name for a screen reader to stop on it and offer [SAVE_HERE_ACTION]. */
const val DIAL_NAME = "Compass"

/** The name an accessibility service is given for the compass's action that saves the position: what a double tap on the dial does. */
const val SAVE_HERE_ACTION = "Save waypoint here"

/**
 * The smaller line of the `Breadcrumb trail` row in settings. On a watch with a button the app can act on it says
 * what the button does; on one without, it says where the setting shows, and speaks of no press.
 */
fun trailSettingDetail(assignableButton: Boolean): String =
    if (assignableButton) "Double press starts a trail" else "Trail row on waypoints"

// The question asked before `Back up waypoints` is turned off: what is asked, what turning it off does, and the two answers.

/** What is asked. */
const val BACKUP_OFF_QUESTION = "Turn off backup?"
/** What turning it off does to the backup that is stored. It says nothing about when: the system decides when a backup runs. */
const val BACKUP_OFF_DETAIL = "The next backup replaces the stored one, without waypoints."
/** The answer that turns it off, shown in the error color. */
const val BACKUP_OFF_CONFIRM = "Turn off"
/** The answer that leaves it on; Back and a right swipe do the same. */
const val BACKUP_OFF_KEEP = "Keep on"

// The `Colors` setting's row (v3 spec, "Colors"; the words are the spec's proposal, for the owner to approve).

/** What the row sets. */
const val COLORS_LABEL = "Colors"
/** The smaller line of the row: the choice as it stands. */
const val COLORS_STANDARD = "Standard"
const val COLORS_WATCH_FACE = "Watch face"
/** The smaller line of the row on a watch that gives no color scheme, where `Watch face` cannot be chosen. */
const val COLORS_NOT_AVAILABLE = "Not available on this watch"
/** What a tap on the row does, said by a screen reader (the row's click label); the owner approved both (2026-10-14). */
const val COLORS_USE_WATCH_FACE = "Use watch face colors"
const val COLORS_USE_STANDARD = "Use standard colors"

/**
 * What the `Colors` row shows ([detail]), what a tap on it chooses ([tapGives]; null: it takes no tap) and what a
 * screen reader says a tap does ([clickLabel]; null when it takes no tap).
 */
data class ColorsRow(val detail: String, val tapGives: ColorMode?, val clickLabel: String?)

/**
 * The `Colors` row for the stored choice [mode] on a watch that gives a color scheme or not ([available]). With one,
 * the row says the choice and a tap gives the other. Without one it says so and takes no tap; what is stored is
 * kept, and the colors are those of `Standard` ([paletteFor]).
 */
fun colorsRow(mode: ColorMode, available: Boolean): ColorsRow = when {
    !available -> ColorsRow(COLORS_NOT_AVAILABLE, null, null)
    mode == ColorMode.STANDARD -> ColorsRow(COLORS_STANDARD, ColorMode.WATCH_FACE, COLORS_USE_WATCH_FACE)
    else -> ColorsRow(COLORS_WATCH_FACE, ColorMode.STANDARD, COLORS_USE_STANDARD)
}

// The `Privacy policy` row, the last of the settings (the words are proposed, for the owner to approve).

/** What the row is. */
const val PRIVACY_POLICY_LABEL = "Privacy policy"
/** What a tap on the row does, said by a screen reader (the row's click label). */
const val PRIVACY_POLICY_CLICK_LABEL = "Open on your phone"
/** The page a tap opens on the paired phone: the compass's section of the published policy. */
const val PRIVACY_POLICY_URL = "https://infinite8looper.github.io/privacy.html#compass"
/** The policy's address as the watch shows it when the phone did not open it: the link without its scheme and section. */
const val PRIVACY_POLICY_ADDRESS = "infinite8looper.github.io/privacy.html"
/** What the row says once the phone has taken the link. */
const val PRIVACY_OPENED_ON_PHONE = "Opened on your phone"
/** How long the watch waits for the phone to take the link before it shows [PRIVACY_POLICY_ADDRESS] instead. */
const val PHONE_OPEN_WAIT_MS = 10_000L

/** Where a tap on the `Privacy policy` row has got to: not tapped yet, the phone being asked, opened there, or not. */
enum class PhoneOpen { NOT_SENT, SENDING, OPENED, NOT_OPENED }

/** What the `Privacy policy` row shows under its label ([detail]) and whether it takes a tap ([takesTap]). */
data class PrivacyRow(val detail: String, val takesTap: Boolean)

/**
 * The `Privacy policy` row in each [state]. Where the phone did not open the link the row shows the address, in two
 * lines broken before the path, since it is one word too long for one line. While the phone is being asked the row
 * takes no second tap; after an answer a tap asks again.
 */
fun privacyRow(state: PhoneOpen): PrivacyRow = when (state) {
    PhoneOpen.NOT_SENT -> PrivacyRow("", true)
    PhoneOpen.SENDING -> PrivacyRow("", false)
    PhoneOpen.OPENED -> PrivacyRow(PRIVACY_OPENED_ON_PHONE, true)
    PhoneOpen.NOT_OPENED -> PrivacyRow(PRIVACY_POLICY_ADDRESS.replaceFirst("/", "\n/"), true)
}

/** What came of asking the phone: [delivered] true when it took the link, false when the call failed, null when no answer came in [PHONE_OPEN_WAIT_MS]. */
fun phoneOpenAfter(delivered: Boolean?): PhoneOpen = if (delivered == true) PhoneOpen.OPENED else PhoneOpen.NOT_OPENED
