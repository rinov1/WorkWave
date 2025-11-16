package com.workwave.workwave.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface EmployeeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(employee: EmployeeEntity): Long

    @Query("SELECT * FROM employees ORDER BY lastName, firstName")
    suspend fun getAll(): List<EmployeeEntity>

    @Query("SELECT * FROM employees WHERE userId = :userId LIMIT 1")
    suspend fun findByUserId(userId: Long): EmployeeEntity?

    @Query("SELECT * FROM employees WHERE id = :id")
    suspend fun getById(id: Long): EmployeeEntity?

    @Update
    suspend fun update(employee: EmployeeEntity)

    @Query("DELETE FROM employees WHERE userId = :userId")
    suspend fun deleteByUserId(userId: Long)
}