package com.workwave.workwave.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.workwave.workwave.R
import com.workwave.workwave.data.AppDatabase
import com.workwave.workwave.data.EmployeeEntity
import com.workwave.workwave.data.UserEntity
import com.workwave.workwave.databinding.ActivityProfileBinding
import com.workwave.workwave.firebase.FirebaseEmployees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class ProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityProfileBinding
    private var userId: Long = -1L
    private var employee: EmployeeEntity? = null
    private var editMode = false
    private var hasChanges = false
    private var canEditProfile = false
    private var isHr: Boolean = false
    private var isOwnProfile: Boolean = false

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null && editMode && canEditProfile) {
                binding.ivAvatar.setImageURI(uri)
                employee = (employee ?: EmployeeEntity()).copy(
                    avatarUri = uri.toString(),
                    userId = userId
                )
                hasChanges = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userId = intent.getLongExtra("userId", -1L)
        canEditProfile = intent.getBooleanExtra("editable", false)
        isHr = intent.getBooleanExtra("hrMode", false)
        isOwnProfile = intent.getBooleanExtra("ownProfile", false)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_edit -> {
                    if (!canEditProfile) return@setOnMenuItemClickListener true
                    if (!editMode) setEditMode(true) else saveIfValid()
                    true
                }
                else -> false
            }
        }

        binding.toolbar.menu.findItem(R.id.action_edit)?.isVisible = canEditProfile

        binding.ivAvatar.setOnClickListener {
            if (editMode && canEditProfile) pickImage.launch("image/*")
        }

        binding.btnPickHireDate.setOnClickListener {
            if (editMode && isHr) showHireDatePicker()
        }

        binding.btnLogout.visibility = if (isOwnProfile) View.VISIBLE else View.GONE
        binding.btnLogout.setOnClickListener { logout() }

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
        binding.tilPosition.editText?.setText(e.position ?: "")

        binding.tvTenure.text = tenureText(e.hireDate)
        binding.tvHireDate.text = formatHireDate(e.hireDate)

        binding.tvStatus.text =
            if (e.onVacation) getString(R.string.status_vacation) else getString(R.string.status_working)
        binding.swVacation.isChecked = e.onVacation

        if (!e.avatarUri.isNullOrEmpty()) {
            binding.ivAvatar.setImageURI(Uri.parse(e.avatarUri))
        }

        binding.etFirst.setText(e.firstName ?: "")
        binding.etLast.setText(e.lastName ?: "")
        binding.etPhone.setText(e.phone ?: "")
        binding.etEmail.setText(e.email ?: "")
    }

    private fun tenureText(hireDate: Long?): String {
        if (hireDate == null || hireDate <= 0) return getString(R.string.tenure_less_than_year)
        val now = Calendar.getInstance().timeInMillis
        val years = ((now - hireDate) / (365.25 * 24 * 60 * 60 * 1000)).toInt()
        return if (years <= 0) {
            getString(R.string.tenure_less_than_year)
        } else {
            getString(R.string.tenure_years, years)
        }
    }

    private fun formatHireDate(hireDate: Long?): String {
        if (hireDate == null || hireDate <= 0) return "-"
        val cal = Calendar.getInstance()
        cal.timeInMillis = hireDate
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return String.format("%02d.%02d.%04d", d, m, y)
    }

    private fun setEditMode(enabled: Boolean) {
        if (enabled && !canEditProfile) return

        editMode = enabled
        binding.toolbar.menu.findItem(R.id.action_edit)?.title =
            if (enabled) getString(R.string.profile_menu_save) else getString(R.string.profile_menu_edit)

        binding.tvFullName.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.groupEditName.visibility = if (enabled) View.VISIBLE else View.GONE

        binding.tvPhone.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.tilPhone.visibility = if (enabled) View.VISIBLE else View.GONE

        binding.tvEmail.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.tilEmail.visibility = if (enabled) View.VISIBLE else View.GONE

        if (isHr) {
            if (enabled) {
                binding.tvPosition.visibility = View.GONE
                binding.tilPosition.visibility = View.VISIBLE

                binding.groupHireDate.visibility = View.VISIBLE

                binding.tvStatus.visibility = View.GONE
                binding.swVacation.visibility = View.VISIBLE
            } else {
                binding.tvPosition.visibility = View.VISIBLE
                binding.tilPosition.visibility = View.GONE

                binding.groupHireDate.visibility = View.GONE

                binding.tvStatus.visibility = View.VISIBLE
                binding.swVacation.visibility = View.GONE
            }
        } else {
            binding.tvPosition.visibility = View.VISIBLE
            binding.tilPosition.visibility = View.GONE
            binding.groupHireDate.visibility = View.GONE
            binding.tvStatus.visibility = View.VISIBLE
            binding.swVacation.visibility = View.GONE
        }
    }

    private fun saveIfValid() {
        val first = binding.etFirst.text?.toString()?.trim().orEmpty()
        val last = binding.etLast.text?.toString()?.trim().orEmpty()
        val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()

        if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = getString(R.string.profile_invalid_email)
            return
        }
        binding.tilEmail.error = null

        val cur = (employee ?: EmployeeEntity()).copy(userId = userId)

        var updated = cur.copy(
            firstName = first.ifEmpty { null },
            lastName = last.ifEmpty { null },
            phone = phone.ifEmpty { null },
            email = email.ifEmpty { null }
        )

        if (isHr) {
            val posText = binding.tilPosition.editText?.text?.toString()?.trim().orEmpty()
            updated = updated.copy(
                position = posText.ifEmpty { null },
                onVacation = binding.swVacation.isChecked
            )
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.get(this@ProfileActivity)
                    .employeeDao()
                    .insertOrUpdate(updated)
            }
            employee = updated

            // Важно: не ре-активируем сотрудника при изменении профиля.
            // Обновляем только документ в "users".
            FirebaseEmployees.updateUserFields(updated)

            hasChanges = true
            setResult(RESULT_OK)
            fillUi()
            setEditMode(false)
        }
    }

    private fun showHireDatePicker() {
        val current = employee ?: (EmployeeEntity(userId = userId))
        val cal = Calendar.getInstance()
        cal.timeInMillis = current.hireDate ?: System.currentTimeMillis()

        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val newCal = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                employee = current.copy(hireDate = newCal.timeInMillis)
                fillUi()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun logout() {
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    override fun onBackPressed() {
        if (hasChanges) setResult(RESULT_OK)
        super.onBackPressed()
    }
}