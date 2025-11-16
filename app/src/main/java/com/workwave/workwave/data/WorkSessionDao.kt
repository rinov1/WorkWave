package com.workwave.workwave.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface WorkSessionDao {

    @Insert
    suspend fun insert(session: WorkSessionEntity): Long

    @Update
    suspend fun update(session: WorkSessionEntity)

    @Query("SELECT * FROM work_sessions WHERE userId = :userId AND endTime IS NULL LIMIT 1")
    suspend fun getOpenSessionForUser(userId: Long): WorkSessionEntity?

    @Query("UPDATE work_sessions SET endTime = :endTime WHERE id = :id")
    suspend fun finishSession(id: Long, endTime: Long)

    @Query("""
        SELECT 
            ws.id AS id,
            ws.userId AS userId,
            ws.startTime AS startTime,
            ws.endTime AS endTime,
            ws.officeId AS officeId,
            u.email AS userEmail
        FROM work_sessions ws
        INNER JOIN users u ON u.id = ws.userId
        WHERE ws.startTime BETWEEN :startMillis AND :endMillis
        ORDER BY ws.startTime ASC
    """)
    suspend fun sessionsByDay(startMillis: Long, endMillis: Long): List<SessionWithUserEmail>
}

data class SessionWithUserEmail(
    val id: Long,
    val userId: Long,
    val startTime: Long,
    val endTime: Long?,
    val officeId: String?,
    val userEmail: String
)