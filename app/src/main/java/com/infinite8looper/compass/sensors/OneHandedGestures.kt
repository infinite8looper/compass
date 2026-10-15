package com.infinite8looper.compass.sensors

import android.content.Context
import android.util.Log

/**
 * What the watch says of the one-handed gestures (the double pinch), for the check screen.
 *
 * The Wear Compose library's one-handed gestures (`Modifier.oneHandedGesture`, Wear Compose Material 3 1.7.1) reach
 * the system through the Wear SDK: before it registers a gesture it asks `com.google.wear.Sdk.hasApiFeature` for
 * `com.google.wear.feature.GESTURE_DETECTION` and, where that is so, takes the SDK's `GestureInputManager`; a
 * failure of either, such as the class not being there, is taken as no gestures (read from the library's
 * `SdkGestureInputManagerImpl`). The SDK is a library the system offers apps (`wear-sdk`, listed in
 * /system/etc/permissions/wear-sdk.xml on the emulator images); an app reaches its classes only where it is in the
 * app's class path. So three things are told apart here: whether the system lists the library ([sdkListed]),
 * whether the app can reach its classes ([sdkReached]), and whether the system says it detects the gestures
 * ([gestures], asked as the library asks; false where the SDK cannot be reached).
 */
object OneHandedGestures {
    data class Report(val sdkListed: Boolean, val sdkReached: Boolean, val gestures: Boolean)

    private const val SDK_LIBRARY = "wear-sdk"
    private const val SDK_CLASS = "com.google.wear.Sdk"
    private const val GESTURE_FEATURE = "com.google.wear.feature.GESTURE_DETECTION"

    /** Asked each time: on the check screen only, and quick. */
    fun report(context: Context): Report {
        val listed = try { context.packageManager.systemSharedLibraryNames?.contains(SDK_LIBRARY) == true } catch (e: Exception) { failed(e); false }
        val sdk = try { Class.forName(SDK_CLASS, false, context.classLoader) } catch (e: ClassNotFoundException) { null } catch (e: LinkageError) { failed(e); null }
        val gestures = sdk != null && try {
            sdk.getMethod("hasApiFeature", String::class.java).invoke(null, GESTURE_FEATURE) == true
        } catch (e: Exception) { failed(e); false } catch (e: LinkageError) { failed(e); false }
        return Report(listed, sdk != null, gestures)
    }

    private fun failed(e: Throwable) { Log.w("OneHandedGestures", "the Wear SDK could not be asked (" + e.javaClass.simpleName + ")") }
}
