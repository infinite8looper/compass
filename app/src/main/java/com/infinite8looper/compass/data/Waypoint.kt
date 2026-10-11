package com.infinite8looper.compass.data

import android.content.Context
import androidx.room.*
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.WaypointInfo
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "waypoints")
data class Waypoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val createdAt: Long,
    val hue: Float,
    val isTarget: Boolean,
) {
    fun toInfo() = WaypointInfo(id, name, GeoPoint(lat, lon), hue, isTarget)
}

@Dao
interface WaypointDao {
    @Query("SELECT * FROM waypoints ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<Waypoint>>

    @Query("SELECT * FROM waypoints WHERE id = :id")
    suspend fun get(id: Long): Waypoint?

    @Insert suspend fun insert(w: Waypoint): Long
    @Update suspend fun update(w: Waypoint)

    @Query("DELETE FROM waypoints WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE waypoints SET isTarget = :isTarget WHERE id = :id")
    suspend fun setTarget(id: Long, isTarget: Boolean)

    @Query("UPDATE waypoints SET isTarget = :isTarget")
    suspend fun setAllTargets(isTarget: Boolean)
}

@Database(entities = [Waypoint::class], version = 1, exportSchema = false)
abstract class WaypointDatabase : RoomDatabase() {
    abstract fun waypoints(): WaypointDao

    companion object {
        @Volatile private var instance: WaypointDatabase? = null
        fun open(context: Context, name: String): WaypointDatabase =
            Room.databaseBuilder(context.applicationContext, WaypointDatabase::class.java, name).build()
        fun get(context: Context): WaypointDatabase =
            instance ?: synchronized(this) { instance ?: open(context, "waypoints.db").also { instance = it } }
    }
}
