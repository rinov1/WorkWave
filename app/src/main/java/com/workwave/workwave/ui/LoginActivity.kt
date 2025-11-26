package com.workwave.workwave.ui

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.workwave.workwave.data.AppDatabase
import com.workwave.workwave.data.UserEntity
import com.workwave.workwave.databinding.ActivityLoginBinding
import com.workwave.workwave.security.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private val HR_EMAIL = "hr@bk.ru"
    private val HR_PASSWORD = "123456"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener { login() }

        binding.tvForgot.setOnClickListener {
            startActivity(
                Intent(this, RegisterActivity::class.java)
                    .putExtra("hrMode", false)
            )
        }
    }

    private fun login() {
        val emailRaw = binding.etEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        binding.tilEmail.error = null
        binding.tilPassword.error = null

        if (!Patterns.EMAIL_ADDRESS.matcher(emailRaw).matches()) {
            binding.tilEmail.error = "Некорректная почта"
            return
        }
        if (password.isEmpty()) {
            binding.tilPassword.error = "Введите пароль"
            return
        }

        val email = emailRaw.lowercase()

        lifecycleScope.launch {
            val db = AppDatabase.get(this@LoginActivity)
            val dao = db.userDao()
            var user = withContext(Dispatchers.IO) { dao.findByEmail(email) }

            if (user == null && email == HR_EMAIL && password == HR_PASSWORD) {
                val salt = PasswordHasher.generateSalt()
                val hash = PasswordHasher.hash(password.toCharArray(), salt)
                val hrUser = UserEntity(
                    email = HR_EMAIL,
                    passwordHashB64 = PasswordHasher.toB64(hash),
                    saltB64 = PasswordHasher.toB64(salt),
                    isHr = true
                )
                val newId = withContext(Dispatchers.IO) { dao.insert(hrUser) }
                user = hrUser.copy(id = newId)
            }

            if (user == null) {
                Snackbar.make(
                    binding.root,
                    "Пользователь не найден. Зарегистрируйтесь.",
                    Snackbar.LENGTH_LONG
                ).show()
                return@launch
            }

            val ok = PasswordHasher.verify(
                password.toCharArray(),
                user.saltB64,
                user.passwordHashB64
            )
            if (ok) {
                startActivity(
                    Intent(this@LoginActivity, HomeActivity::class.java)
                        .putExtra("userId", user.id)
                        .putExtra("isHr", user.isHr)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            } else {
                Snackbar.make(binding.root, "Неверный пароль", Snackbar.LENGTH_LONG).show()
            }
        }
    }
}