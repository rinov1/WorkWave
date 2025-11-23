package com.workwave.workwave.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: UserEntity): Long

    @Query("SELECT * FROM users WHERE LOWER(email) = LOWER(:email) LIMIT 1")
    suspend fun findByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users ORDER BY email ASC")
    suspend fun getAll(): List<UserEntity>

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): UserEntity?

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("""
        SELECT 
            u.id        AS userId,
            u.email     AS email,
            e.firstName AS firstName,
            e.lastName  AS lastName,
            u.createdAt AS createdAt
        FROM users u
        INNER JOIN employees e ON e.userId = u.id
        WHERE u.isHr = 0
        ORDER BY 
            CASE WHEN (e.lastName IS NULL OR e.lastName = '') 
                  AND (e.firstName IS NULL OR e.firstName = '') 
                 THEN 1 ELSE 0 END,
            e.lastName COLLATE NOCASE,
            e.firstName COLLATE NOCASE,
            u.email COLLATE NOCASE
    """)
    suspend fun getActiveEmployeesWithNames(): List<UserWithNames>

    @Query("""
        SELECT 
            u.id    AS userId,
            u.email AS email,
            NULL    AS firstName,
            NULL    AS lastName,
            u.createdAt AS createdAt
        FROM users u
        LEFT JOIN employees e ON e.userId = u.id
        WHERE e.userId IS NULL AND u.isHr = 0
        ORDER BY u.email COLLATE NOCASE
    """)
    suspend fun getUsersNotInEmployees(): List<UserWithNames>
}

data class UserWithNames(
    val userId: Long,
    val email: String,
    val firstName: String?,
    val lastName: String?,
    val createdAt: Long
)