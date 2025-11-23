package com.workwave.workwave.ui

import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.workwave.workwave.data.AppDatabase
import com.workwave.workwave.data.EmployeeEntity
import com.workwave.workwave.data.UserEntity
import com.workwave.workwave.databinding.ActivityProfileBinding
import com.workwave.workwave.firebase.FirebaseEmployees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private var userId: Long = -1L
    private var employee: EmployeeEntity? = null
    private var editMode = false
    private var hasChanges = false
    private var canEditProfile = false   // <-- флаг: можно ли редактировать этот профиль

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && editMode) {
            binding.ivAvatar.setImageURI(uri)
            employee = (employee ?: EmployeeEntity()).copy(avatarUri = uri.toString(), userId = userId)
            hasChanges = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userId = intent.getLongExtra("userId", -1L)
        canEditProfile = intent.getBooleanExtra("editable", false)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                com.workwave.workwave.R.id.action_edit -> {
                    // Если нет прав на редактирование — игнорируем нажатие
                    if (!canEditProfile) {
                        return@setOnMenuItemClickListener true
                    }
                    if (!editMode) setEditMode(true) else saveIfValid()
                    true
                }
                else -> false
            }
        }

        // Скрываем кнопку "Изменить", если редактирование запрещено
        binding.toolbar.menu.findItem(com.workwave.workwave.R.id.action_edit)?.isVisible = canEditProfile

        binding.ivAvatar.setOnClickListener { if (editMode) pickImage.launch("image/*") }

        lifecycleScope.launch { loadData() }
    }

    private suspend fun loadData() {
        val db = AppDatabase.get(this)
        val emp: EmployeeEntity?
        val usr: UserEntity?
        withContext(Dispatchers.IO) {
            emp = db.employeeDao().findByUserId(userId)
            usr = db.userDao().getById(userId)
        }
        employee = emp ?: EmployeeEntity(
            userId = userId,
            email = usr?.email,
            position = emp?.position,
            hireDate = emp?.hireDate,
            onVacation = emp?.onVacation ?: false
        )
        fillUi()
        setEditMode(false)
    }

    private fun fillUi() {
        val e = employee ?: return

        val fullName = listOfNotNull(e.firstName, e.lastName).joinToString(" ").ifEmpty { "-" }
        binding.tvFullName.text = fullName

        binding.tvPhone.text = e.phone ?: "-"
        binding.tvEmail.text = e.email ?: "-"

        binding.tvPosition.text = e.position ?: "-"
        binding.tvTenure.text = tenureText(e.hireDate)
        binding.tvStatus.text = if (e.onVacation) "В отпуске" else "Работает"

        if (!e.avatarUri.isNullOrEmpty()) {
            binding.ivAvatar.setImageURI(Uri.parse(e.avatarUri))
        }

        binding.etFirst.setText(e.firstName ?: "")
        binding.etLast.setText(e.lastName ?: "")
        binding.etPhone.setText(e.phone ?: "")
        binding.etEmail.setText(e.email ?: "")
    }

    private fun tenureText(hireDate: Long?): String {
        if (hireDate == null) return "-"
        val now = Calendar.getInstance().timeInMillis
        val years = ((now - hireDate) / (365.25 * 24 * 60 * 60 * 1000)).toInt()
        return if (years <= 0) "меньше года" else "$years лет"
    }

    private fun setEditMode(enabled: Boolean) {
        // Не разрешаем включить режим редактирования, если нет прав
        if (enabled && !canEditProfile) return

        editMode = enabled
        binding.toolbar.menu.findItem(com.workwave.workwave.R.id.action_edit)?.title =
            if (enabled) "Сохранить" else "Изменить"

        binding.tvFullName.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.groupEditName.visibility = if (enabled) View.VISIBLE else View.GONE

        binding.tvPhone.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.tilPhone.visibility = if (enabled) View.VISIBLE else View.GONE

        binding.tvEmail.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.tilEmail.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun saveIfValid() {
        val first = binding.etFirst.text?.toString()?.trim().orEmpty()
        val last = binding.etLast.text?.toString()?.trim().orEmpty()
        val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()

        if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Некорректный email"
            return
        }
        binding.tilEmail.error = null

        val updated = (employee ?: EmployeeEntity()).copy(
            userId = userId,
            firstName = first.ifEmpty { null },
            lastName  = last.ifEmpty { null },
            phone     = phone.ifEmpty { null },
            email     = email.ifEmpty { null }
        )

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.get(this@ProfileActivity).employeeDao().insertOrUpdate(updated)
            }
            employee = updated
            // Синк в Firestore — список обновится через listener
            FirebaseEmployees.upsertEmployee(updated)
            hasChanges = true
            setResult(RESULT_OK)
            fillUi()
            setEditMode(false)
        }
    }

    override fun onBackPressed() {
        if (hasChanges) setResult(RESULT_OK)
        super.onBackPressed()
    }
}