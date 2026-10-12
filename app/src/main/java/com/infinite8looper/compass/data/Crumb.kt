package com.infinite8looper.compass.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.infinite8looper.compass.nav.CrumbPoint
import com.infinite8looper.compass.nav.GeoPoint
import kotlinx.coroutines.flow.Flow

/**
 * One point of the breadcrumb trail. Crumbs are a table of the waypoint database, so they are in the same file as
 * the waypoints and everything that holds for that file holds for them: it is opened one way ([WaypointDatabase.open]),
 * deleted rows are overwritten, and it is copied for a backup only as [WaypointDatabase.copyIfSettled] allows.
 *
 * A crumb has no name and no color. Ids only grow, so the order of the ids is the order the crumbs were dropped in,
 * whatever the clock said at the time.
 */
@Entity(tableName = "crumbs")
data class Crumb(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val createdAt: Long,
) {
    fun toPoint() = CrumbPoint(id, GeoPoint(lat, lon))
}

@Dao
interface CrumbDao {
    /** The trail, newest first. */
    @Query("SELECT * FROM crumbs ORDER BY id DESC")
    fun observeAll(): Flow<List<Crumb>>

    @Query("SELECT * FROM crumbs ORDER BY id DESC")
    suspend fun all(): List<Crumb>

    @Insert suspend fun insert(c: Crumb): Long

    @Query("DELETE FROM crumbs WHERE id = :id")
    suspend fun delete(id: Long)

    /** Removes every crumb. */
    @Query("DELETE FROM crumbs")
    suspend fun clear()
}
