package com.workwave.workwave.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "users",
    indices = [Index(value = ["email"], unique = true)]
)
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val email: String,
    val passwordHashB64: String,
    val saltB64: String,
    val isHr: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)