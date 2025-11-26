package com.workwave.workwave.firebase

import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.workwave.workwave.data.EmployeeEntity
import com.workwave.workwave.data.UserEntity
import com.workwave.workwave.data.UserWithNames

object FirebaseEmployees {

    private val db = Firebase.firestore

    fun upsertUser(user: UserEntity) {
        val docId = user.id.toString()
        val data = mapOf(
            "userId" to user.id,
            "email" to user.email,
            "isHr" to user.isHr,
            "createdAt" to user.createdAt
        )
        db.collection("users").document(docId).set(data)
    }

    fun upsertEmployee(employee: EmployeeEntity) {
        val uid = employee.userId ?: return
        val data = hashMapOf(
            "userId" to uid,
            "email" to (employee.email ?: ""),
            "firstName" to (employee.firstName ?: ""),
            "lastName" to (employee.lastName ?: ""),
            "lastNameLower" to (employee.lastName ?: "").lowercase(),
            "position" to (employee.position ?: ""),
            "phone" to (employee.phone ?: ""),
            "avatarUri" to (employee.avatarUri ?: ""),
            "onVacation" to employee.onVacation,
            "hireDate" to (employee.hireDate ?: 0L),
            "createdAt" to System.currentTimeMillis(),
            "active" to true
        )
        db.collection("employees").document(uid.toString()).set(data)
    }

    fun deleteEmployeeByUserId(userId: Long) {
        db.collection("employees")
            .document(userId.toString())
            .update("active", false)
    }

    fun listenEmployees(onUpdate: (List<UserWithNames>) -> Unit): ListenerRegistration {
        return db.collection("employees")
            .whereEqualTo("active", true)
            .orderBy("lastNameLower", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) return@addSnapshotListener
                val list = snap.documents.mapNotNull { d ->
                    val uid = d.getLong("userId") ?: return@mapNotNull null
                    val email = d.getString("email") ?: return@mapNotNull null
                    val first = d.getString("firstName")
                    val last = d.getString("lastName")
                    val createdAt = d.getLong("createdAt") ?: 0L
                    UserWithNames(uid, email, first, last, createdAt)
                }
                onUpdate(list)
            }
    }
}