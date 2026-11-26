package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RoomScanDao {
    @Query("SELECT * FROM room_scans ORDER BY createdAtMs DESC")
    fun observeAllScans(): Flow<List<RoomScanEntity>>

    @Query("SELECT * FROM room_scans WHERE id = :id LIMIT 1")
    fun observeScanById(id: Long): Flow<RoomScanEntity?>

    @Query("SELECT * FROM room_scans WHERE id = :id LIMIT 1")
    suspend fun getScanById(id: Long): RoomScanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(scan: RoomScanEntity): Long

    @Update
    suspend fun updateScan(scan: RoomScanEntity)

    @Query("UPDATE room_scans SET title = :title WHERE id = :id")
    suspend fun renameScan(id: Long, title: String)

    @Query("DELETE FROM room_scans WHERE id = :id")
    suspend fun deleteScanById(id: Long)
}
