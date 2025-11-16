package com.workwave.workwave.ui

import android.content.Intent
import android.database.sqlite.SQLiteConstraintException
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.workwave.workwave.data.AppDatabase
import com.workwave.workwave.data.UserEntity
import com.workwave.workwave.databinding.ActivityRegisterBinding
import com.workwave.workwave.security.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegister.setOnClickListener { register() }
        binding.tvAlready.setOnClickListener {
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }
    }

    private fun register() {
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

        binding.btnRegister.isEnabled = false
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@RegisterActivity).userDao()
            try {
                val exists = withContext(Dispatchers.IO) { dao.findByEmail(email) }
                if (exists != null) {
                    Snackbar.make(binding.root, "Почта уже зарегистрирована", Snackbar.LENGTH_LONG).show()
                    return@launch
                }

                val salt = PasswordHasher.generateSalt()
                val hash = PasswordHasher.hash(password.toCharArray(), salt)

                val user = UserEntity(
                    email = email,
                    passwordHashB64 = PasswordHasher.toB64(hash),
                    saltB64 = PasswordHasher.toB64(salt),
                    isHr = false
                )

                withContext(Dispatchers.IO) { dao.insert(user) }

                Snackbar.make(binding.root, "Аккаунт создан. Войдите.", Snackbar.LENGTH_LONG).show()
                startActivity(
                    Intent(this@RegisterActivity, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            } catch (e: SQLiteConstraintException) {
                Snackbar.make(binding.root, "Почта уже зарегистрирована", Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("RegisterActivity", "Ошибка регистрации", e)
                Snackbar.make(binding.root, "Ошибка регистрации: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.btnRegister.isEnabled = true
            }
        }
    }
}