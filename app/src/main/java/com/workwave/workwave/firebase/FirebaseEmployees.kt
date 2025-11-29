package com.workwave.workwave.firebase

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.workwave.workwave.data.EmployeeEntity
import com.workwave.workwave.data.UserEntity
import com.workwave.workwave.data.UserWithNames

object FirebaseEmployees {

    private val db = Firebase.firestore


    fun upsertUser(user: UserEntity) {
        val uid = user.id ?: return
        val doc = db.collection("users").document(uid.toString())

        val data = mutableMapOf<String, Any>(
            "userId" to uid,
            "email" to user.email,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        try {
            val isHrField = UserEntity::class.java.getDeclaredField("isHr")
            isHrField.isAccessible = true
            (isHrField.get(user) as? Boolean)?.let { data["isHr"] = it }
        } catch (_: Exception) {

        }

        doc.set(data, SetOptions.merge())
            .addOnSuccessListener {
                doc.update("createdAt", FieldValue.serverTimestamp())
                    .addOnFailureListener { /* ignore */ }
            }
            .addOnFailureListener { /* ignore */ }
    }


    fun upsertEmployee(emp: EmployeeEntity) {
        val uid = emp.userId ?: return
        val email = emp.email ?: ""

        // employees
        val empDoc = db.collection("employees").document(uid.toString())
        val empData = mutableMapOf<String, Any>(
            "userId" to uid,
            "email" to email,
            "active" to true,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        empDoc.set(empData, SetOptions.merge())
            .addOnSuccessListener {
                empDoc.update("createdAt", FieldValue.serverTimestamp())
                    .addOnFailureListener { /* ignore */ }
            }
            .addOnFailureListener { /* ignore */ }


        updateUserFields(emp)
    }


    fun updateUserFields(emp: EmployeeEntity) {
        val uid = emp.userId ?: return
        val email = emp.email ?: ""

        val userDoc = db.collection("users").document(uid.toString())
        val userData = mutableMapOf<String, Any>(
            "userId" to uid,
            "email" to email,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        emp.firstName?.takeIf { it.isNotBlank() }?.let { userData["firstName"] = it }
        emp.lastName?.takeIf { it.isNotBlank() }?.let { userData["lastName"] = it }

        userDoc.set(userData, SetOptions.merge())
            .addOnSuccessListener {
                userDoc.update("createdAt", FieldValue.serverTimestamp())
                    .addOnFailureListener { /* ignore */ }
            }
            .addOnFailureListener { /* ignore */ }
    }

    fun deleteEmployeeByUserId(userId: Long) {
        setEmployeeActive(userId, false)
    }

    fun setEmployeeActive(userId: Long, active: Boolean) {
        val doc = db.collection("employees").document(userId.toString())
        val data = mutableMapOf<String, Any>(
            "active" to active,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        doc.set(data, SetOptions.merge())
    }

    fun listenEmployees(onChanged: (List<UserWithNames>) -> Unit): ListenerRegistration {
        return db.collection("employees")
            .whereEqualTo("active", true)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) {
                    onChanged(emptyList())
                    return@addSnapshotListener
                }
                val ids = snapshot.documents.mapNotNull { it.getLong("userId")?.toLong() }
                if (ids.isEmpty()) {
                    onChanged(emptyList()); return@addSnapshotListener
                }

                val tasks = ids.chunked(10).map { chunk ->
                    db.collection("users")
                        .whereIn("userId", chunk)
                        .get()
                }

                Tasks.whenAllSuccess<QuerySnapshot>(tasks)
                    .addOnSuccessListener { results ->
                        val docs = results.flatMap { it.documents }
                        val mapById = docs.associateBy { it.getLong("userId")!!.toLong() }

                        val list = ids.mapNotNull { id ->
                            val u = mapById[id] ?: return@mapNotNull null
                            val email = u.getString("email") ?: return@mapNotNull null
                            val first = u.getString("firstName")
                            val last = u.getString("lastName")
                            val createdAt = u.getLong("createdAt") ?: System.currentTimeMillis()
                            UserWithNames(
                                userId = id,
                                email = email,
                                firstName = first,
                                lastName = last,
                                createdAt = createdAt
                            )
                        }
                        onChanged(list)
                    }
                    .addOnFailureListener { onChanged(emptyList()) }
            }
    }

    fun listenEmployeeActive(userId: Long, onChanged: (Boolean) -> Unit): ListenerRegistration {
        val doc = db.collection("employees").document(userId.toString())
        return doc.addSnapshotListener { d, _ ->
            val active = d?.getBoolean("active") == true
            onChanged(active)
        }
    }
}