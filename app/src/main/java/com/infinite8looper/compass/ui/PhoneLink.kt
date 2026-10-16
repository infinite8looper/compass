package com.infinite8looper.compass.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.concurrent.futures.await
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.infinite8looper.compass.nav.PHONE_OPEN_WAIT_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "PhoneLink"

/**
 * Asks the paired phone to open [url] in its browser (RemoteActivityHelper, an ACTION_VIEW intent in the category
 * BROWSABLE). True when the phone took it, false when the call failed (no phone connected among them), null when
 * no answer came in [PHONE_OPEN_WAIT_MS]. The watch itself opens nothing and fetches nothing.
 */
suspend fun openOnPhone(context: Context, url: String): Boolean? {
    val intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(Uri.parse(url))
    return try {
        withTimeoutOrNull(PHONE_OPEN_WAIT_MS) {
            RemoteActivityHelper(context.applicationContext).startRemoteActivity(intent).await()
            true
        }.also { if (it == null) Log.w(TAG, "no answer from the phone in time") }
    } catch (e: CancellationException) {
        // The caller's own end is passed on; a call the library itself gave up on is a failed call.
        currentCoroutineContext().ensureActive()
        Log.w(TAG, "could not open the link on the phone (" + e.javaClass.simpleName + ")")
        false
    } catch (e: Exception) {
        Log.w(TAG, "could not open the link on the phone (" + e.javaClass.simpleName + ")")
        false
    }
}
