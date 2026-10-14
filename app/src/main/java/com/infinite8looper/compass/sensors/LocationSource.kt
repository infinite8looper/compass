package com.infinite8looper.compass.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.infinite8looper.compass.nav.Fix
import com.infinite8looper.compass.nav.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val MAX_FIX_AGE_MS = 10_000L

/** Degrees, positive east, from the World Magnetic Model built into Android. */
fun declinationAt(point: GeoPoint, altitudeM: Float, timeMs: Long): Float =
    GeomagneticField(point.lat.toFloat(), point.lon.toFloat(), altitudeM, timeMs).declination

class LocationSource(
    context: Context,
    private val permissionCheck: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    },
) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    fun hasPermission(): Boolean = permissionCheck()

    /**
     * The location provider's last known position, with the time it was taken on the clock that counts from boot, or
     * null when there is none or the permission is not held. A position that states no accuracy has NaN for it.
     */
    @SuppressLint("MissingPermission")
    suspend fun lastKnown(): Fix? {
        if (!hasPermission()) return null
        return try {
            suspendCancellableCoroutine { cont ->
                client.lastLocation.addOnCompleteListener { task ->
                    val loc = if (task.isSuccessful) task.result else null
                    cont.resume(loc?.let {
                        Fix(GeoPoint(it.latitude, it.longitude), if (it.hasAccuracy()) it.accuracy else Float.NaN, it.altitude.toFloat(), it.elapsedRealtimeNanos / 1_000_000)
                    })
                }
            }
        } catch (e: SecurityException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long): Flow<Fix> = callbackFlow {
        if (!hasPermission()) { close(); return@callbackFlow }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    val fixMs = loc.elapsedRealtimeNanos / 1_000_000
                    if (SystemClock.elapsedRealtime() - fixMs > MAX_FIX_AGE_MS) continue
                    trySend(Fix(GeoPoint(loc.latitude, loc.longitude), loc.accuracy, loc.altitude.toFloat(), fixMs))
                }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { close() }
        } catch (e: SecurityException) {
            close()
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
