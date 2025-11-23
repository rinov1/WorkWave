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

    // users/{userId} — краткая инфа о пользователе (для авторизации и т.п.)
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

    // employees/{userId} — карточка сотрудника для списка
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
            // для списка сотрудников используем это createdAt
            "createdAt" to System.currentTimeMillis(),
            // активен ли сотрудник (для "удаления" ставим false)
            "active" to true
        )
        db.collection("employees").document(uid.toString()).set(data)
    }

    /**
     * Логическое удаление сотрудника:
     *  - помечаем "active" = false в коллекции employees/{userId}
     *  - благодаря фильтру .whereEqualTo("active", true) он пропадает из списка
     *  - данные при этом не теряются (можно будет восстановить, если нужно)
     */
    fun deleteEmployee(userId: Long) {
        db.collection("employees")
            .document(userId.toString())
            .update("active", false)
    }

    /**
     * Старое имя метода — оставлено для совместимости.
     * Если где-то в коде уже используется deleteEmployeeByUserId, он тоже будет работать.
     */
    fun deleteEmployeeByUserId(userId: Long) {
        deleteEmployee(userId)
    }

    // Живой список активных сотрудников.
    // HR сюда не попадает, если для HR не создаём карточку в employees.
    fun listenEmployees(onUpdate: (List<UserWithNames>) -> Unit): ListenerRegistration {
        return db.collection("employees")
            .whereEqualTo("active", true)
            .orderBy("lastNameLower", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) return@addSnapshotListener

                val list = snap.documents.mapNotNull { d ->
                    val userId = d.getLong("userId") ?: return@mapNotNull null
                    val email = d.getString("email") ?: return@mapNotNull null
                    val first = d.getString("firstName")
                    val last = d.getString("lastName")
                    val createdAt = d.getLong("createdAt") ?: 0L

                    UserWithNames(
                        userId = userId,
                        email = email,
                        firstName = first,
                        lastName = last,
                        createdAt = createdAt
                    )
                }
                onUpdate(list)
            }
    }
}