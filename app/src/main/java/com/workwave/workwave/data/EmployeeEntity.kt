package com.workwave.workwave.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "employees",
    indices = [Index(value = ["userId"], unique = true), Index(value = ["email"], unique = true)]
)
data class EmployeeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val position: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatarUri: String? = null,
    val onVacation: Boolean = false,
    val hireDate: Long? = null
)