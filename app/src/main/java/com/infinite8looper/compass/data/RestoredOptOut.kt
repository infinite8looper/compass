package com.infinite8looper.compass.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Carries the wearer's turning off of `Back up waypoints` across a restore and a transfer. A backup or transfer
 * made with the switch off holds the marker file and no settings file, so the app it is restored to would otherwise
 * start at its defaults, with the switch on. When the marker comes in a restore, that is put on record in two files,
 * one in each of [folder] and [second] (the app's files folder and its no-backup folder; the backup rules name
 * neither file, so neither is ever backed up), and [settle] then writes the switch off, turned off by the wearer,
 * to the settings file and removes the records. While the settings file cannot be written the records stay and the
 * switch is owed off ([owed]): the backup agent hands over the marker alone and the settings screen shows the
 * switch off. One record is enough, so one folder that takes no write does not lose it.
 *
 * A restore that brings no marker changes nothing, and nothing here ever turns the switch on. The wearer's own
 * later choice does ([wearerSet]): once it is written, nothing is owed.
 *
 * The app has one of these for the process ([get]): the backup agent and the screens ask the same object, so they
 * cannot hold different answers, and [owedState] lets the screens follow it. [settle] and [wearerSet] run one at a
 * time, each from start to end, so whichever comes later decides: a settling that began before the wearer's choice
 * cannot write over it. The constructor is for tests, which give it folders of their own.
 */
class RestoredOptOut(folder: File, second: File) {
    private val records = listOf(File(folder, RECORD_FILE), File(second, RECORD_FILE))
    /** The marker came in this process's restore, kept also in memory in case no record can be written. */
    @Volatile private var came = false
    /** [settle] and [wearerSet], one at a time. */
    private val changes = Mutex()
    private val owedNow = MutableStateFlow(records.any { it.exists() })

    /** [owed], as a state that changes when the marker comes, when what is owed is settled and when the wearer chooses. */
    val owedState: StateFlow<Boolean> = owedNow.asStateFlow()

    /**
     * A restore brought the marker. Both records are tried; throws the first failure after both have been, and the
     * coming is kept in memory even so.
     */
    fun markerCame() {
        came = true
        var failure: Exception? = null
        for (record in records) {
            try { record.writeText(RECORD_TEXT) } catch (e: Exception) { if (failure == null) failure = e }
        }
        owedNow.value = true
        failure?.let { throw it }
    }

    /** Whether the switch is owed off: the marker came and the settings file has not been written since. */
    fun owed(): Boolean = (came || records.any { it.exists() }).also { owedNow.value = it }

    /**
     * Writes what is owed to the settings file through [store]. True when nothing is owed any more; false when the
     * file could not be written or a record could not be removed, in which case the switch stays owed off. Throws
     * only to pass a cancellation on.
     */
    suspend fun settle(store: SettingsStore): Boolean = changes.withLock {
        // Asked with the lock held: a choice of the wearer's that came first has left nothing owed.
        if (!owed()) return@withLock true
        try {
            store.setBackupEnabled(false)
            clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The wearer set the switch in the app: it is written through [store], and once it is, nothing is owed any
     * more, whichever way it was set. Throws if the write fails, and what was owed then stays owed.
     */
    suspend fun wearerSet(store: SettingsStore, on: Boolean) = changes.withLock {
        store.setBackupEnabled(on)
        clear()
        Unit
    }

    /** Removes the records. True when none is left. */
    private fun clear(): Boolean {
        came = false
        for (record in records) record.delete()
        return records.none { it.exists() }.also { owedNow.value = !it }
    }

    companion object {
        private const val RECORD_FILE = "backup-off-owed"
        private const val RECORD_TEXT = "A restore brought a backup made with Back up waypoints off.\n"

        @Volatile private var instance: RestoredOptOut? = null
        /** The one for this process, on the app's files folder and its no-backup folder. */
        fun get(context: Context): RestoredOptOut = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { RestoredOptOut(it.filesDir, it.noBackupFilesDir) }.also { instance = it }
        }
    }
}
