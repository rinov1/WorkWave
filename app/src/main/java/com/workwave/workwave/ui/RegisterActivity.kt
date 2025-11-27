package com.workwave.workwave.ui

import android.os.Bundle
import android.util.Patterns
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.workwave.workwave.data.AppDatabase
import com.workwave.workwave.data.EmployeeEntity
import com.workwave.workwave.data.UserEntity
import com.workwave.workwave.databinding.ActivityRegisterBinding
import com.workwave.workwave.firebase.FirebaseEmployees
import com.workwave.workwave.security.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RegisterActivity : BaseActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private var hrMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hrMode = intent.getBooleanExtra("hrMode", false)

        binding.btnRegister.setOnClickListener { registerUser() }

        binding.tvAlready.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun registerUser() {
        val emailRaw = binding.etEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        binding.tilEmail.error = null
        binding.tilPassword.error = null

        if (!Patterns.EMAIL_ADDRESS.matcher(emailRaw).matches()) {
            binding.tilEmail.error = "Некорректная почта"
            return
        }
        if (password.length < 6) {
            binding.tilPassword.error = "Минимум 6 символов"
            return
        }

        val email = emailRaw.lowercase()

        lifecycleScope.launch {
            val db = AppDatabase.get(this@RegisterActivity)
            val userDao = db.userDao()
            val employeeDao = db.employeeDao()

            val existing = withContext(Dispatchers.IO) { userDao.findByEmail(email) }
            if (existing != null) {
                Snackbar.make(
                    binding.root,
                    "Пользователь с такой почтой уже существует",
                    Snackbar.LENGTH_LONG
                ).show()
                return@launch
            }

            val salt = PasswordHasher.generateSalt()
            val hash = PasswordHasher.hash(password.toCharArray(), salt)

            val newUser = UserEntity(
                email = email,
                passwordHashB64 = PasswordHasher.toB64(hash),
                saltB64 = PasswordHasher.toB64(salt),
                isHr = hrMode
            )

            val newId = withContext(Dispatchers.IO) { userDao.insert(newUser) }
            val userWithId = newUser.copy(id = newId)

            val employee = EmployeeEntity(
                userId = newId,
                email = email
            )

            withContext(Dispatchers.IO) {
                employeeDao.insertOrUpdate(employee)
            }

            FirebaseEmployees.upsertUser(userWithId)
            FirebaseEmployees.upsertEmployee(employee)

            startActivity(
                Intent(this@RegisterActivity, HomeActivity::class.java)
                    .putExtra("userId", userWithId.id)
                    .putExtra("isHr", userWithId.isHr)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
    }
}