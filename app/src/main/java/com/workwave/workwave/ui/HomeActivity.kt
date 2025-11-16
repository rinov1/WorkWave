package com.workwave.workwave.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.workwave.workwave.R
import com.workwave.workwave.data.AppDatabase
import com.workwave.workwave.data.EmployeeEntity
import com.workwave.workwave.data.SessionWithUserEmail
import com.workwave.workwave.data.UserWithNames
import com.workwave.workwave.data.WorkSessionEntity
import com.workwave.workwave.databinding.ActivityHomeBinding
import com.workwave.workwave.databinding.ItemEmployeeBinding
import com.workwave.workwave.databinding.ItemSessionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private var userId: Long = -1L
    private var isHr: Boolean = false

    private var openSession: WorkSessionEntity? = null
    private var pendingAction: Action? = null

    private var calendarInit = false
    private var employeesInit = false
    private val sessionsAdapter = SessionsAdapter()
    private lateinit var employeesAdapter: EmployeesAdapter

    private enum class Action { START, FINISH }

    private val profileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && employeesInit) {
                lifecycleScope.launch { refreshEmployeesList() }
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                when (pendingAction) {
                    Action.START, Action.FINISH -> launchQrScanner()
                    else -> {}
                }
            } else {
                Snackbar.make(binding.root, "Дайте доступ к камере для сканирования QR", Snackbar.LENGTH_LONG).show()
            }
        }

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        when (pendingAction) {
            Action.START -> lifecycleScope.launch { startWorkAfterScan(contents) }
            Action.FINISH -> lifecycleScope.launch { finishWorkAfterScan(contents) }
            else -> {}
        }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userId = intent.getLongExtra("userId", -1L)
        isHr = intent.getBooleanExtra("isHr", false)

        val navIconRes = resources.getIdentifier("icdsettings_24", "drawable", packageName)
            .takeIf { it != 0 }
            ?: resources.getIdentifier("ic_settings_24", "drawable", packageName)
        if (navIconRes != 0) binding.toolbar.setNavigationIcon(navIconRes)
        binding.toolbar.title = "WorkWave"
        binding.toolbar.setNavigationOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_profile) {
                val intent = Intent(this, ProfileActivity::class.java)
                    .putExtra("userId", userId)
                    .putExtra("editable", true)
                    .putExtra("hrMode", isHr)
                profileLauncher.launch(intent)
                true
            } else false
        }

        setupWeekStrip()
        setupTodayTexts()

        binding.btnStart.setOnClickListener {
            pendingAction = Action.START
            ensureCameraPermThenScan()
        }
        binding.btnFinish.setOnClickListener {
            pendingAction = Action.FINISH
            ensureCameraPermThenScan()
        }

        binding.bottomNav.selectedItemId = R.id.nav_home
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { showHomeTab(); true }
                R.id.nav_calendar -> { showCalendarTab(); true }
                R.id.nav_people -> { showEmployeesTab(); true }
                else -> false
            }
        }
        showHomeTab()

        lifecycleScope.launch { loadOpenSessionAndRender() }
    }


    private fun showHomeTab() {
        binding.toolbar.title = "WorkWave"
        binding.homeContent.visibility = View.VISIBLE
        binding.incCalendar.root.visibility = View.GONE
        binding.incEmployees.root.visibility = View.GONE
    }

    private fun showCalendarTab() {
        binding.toolbar.title = "Календарь"
        binding.homeContent.visibility = View.GONE
        binding.incCalendar.root.visibility = View.VISIBLE
        binding.incEmployees.root.visibility = View.GONE
        if (!calendarInit) initCalendarSection()
    }

    private fun showEmployeesTab() {
        binding.toolbar.title = "Сотрудники"
        binding.homeContent.visibility = View.GONE
        binding.incCalendar.root.visibility = View.GONE
        binding.incEmployees.root.visibility = View.VISIBLE
        if (!employeesInit) initEmployeesSection()
    }


    private fun initCalendarSection() {
        binding.incCalendar.rvSessions.layoutManager = LinearLayoutManager(this)
        binding.incCalendar.rvSessions.adapter = sessionsAdapter

        val today = Calendar.getInstance()
        binding.incCalendar.calendarView.date = today.timeInMillis
        updateSelectedDateText(today.timeInMillis)
        loadSessionsForDay(today.timeInMillis)

        binding.incCalendar.calendarView.setOnDateChangeListener { _, y, m, d ->
            val cal = Calendar.getInstance().apply {
                set(y, m, d, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val millis = cal.timeInMillis
            updateSelectedDateText(millis)
            loadSessionsForDay(millis)
        }
        calendarInit = true
    }

    private fun initEmployeesSection() {
        binding.incEmployees.hrActionsBar.visibility = if (isHr) View.VISIBLE else View.GONE

        binding.incEmployees.btnAddEmployee.setOnClickListener {
            lifecycleScope.launch { showAddEmployeeDialog() }
        }
        binding.incEmployees.btnDeleteEmployee.setOnClickListener {
            lifecycleScope.launch { showDeleteEmployeeDialog() }
        }

        employeesAdapter = EmployeesAdapter(
            isHr = isHr,
            onClick = { user -> openEmployee(user.userId) },
            onLongDelete = { user ->
                if (!isHr) return@EmployeesAdapter
                confirmDeleteEmployee(user)
            }
        )
        binding.incEmployees.rvEmployees.layoutManager = LinearLayoutManager(this)
        binding.incEmployees.rvEmployees.adapter = employeesAdapter

        lifecycleScope.launch { refreshEmployeesList() }
        employeesInit = true
    }

    private suspend fun refreshEmployeesList() {
        val list = withContext(Dispatchers.IO) {
            AppDatabase.get(this@HomeActivity).userDao().getActiveEmployeesWithNames()
        }
        employeesAdapter.submitList(list)
    }

    private fun openEmployee(targetUserId: Long) {
        val intent = Intent(this, ProfileActivity::class.java)
            .putExtra("userId", targetUserId)
            .putExtra("editable", isHr)
            .putExtra("hrMode", isHr)
        profileLauncher.launch(intent)
    }

    private suspend fun showAddEmployeeDialog() {
        val users = withContext(Dispatchers.IO) {
            AppDatabase.get(this@HomeActivity).userDao().getUsersNotInEmployees()
        }
        if (users.isEmpty()) {
            Snackbar.make(binding.root, "Нет пользователей для добавления", Snackbar.LENGTH_SHORT).show()
            return
        }
        val labels = users.map { u ->
            val name = listOfNotNull(u.firstName, u.lastName).joinToString(" ").trim()
            if (name.isNotEmpty()) "$name (${u.email})" else u.email
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Добавить сотрудника")
            .setItems(labels) { _, which ->
                val u = users[which]
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.get(this@HomeActivity).employeeDao()
                            .insertOrUpdate(EmployeeEntity(userId = u.userId, email = u.email))
                    }
                    refreshEmployeesList()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private suspend fun showDeleteEmployeeDialog() {
        val employees = withContext(Dispatchers.IO) {
            AppDatabase.get(this@HomeActivity).userDao().getActiveEmployeesWithNames()
        }
        if (employees.isEmpty()) {
            Snackbar.make(binding.root, "Список сотрудников пуст", Snackbar.LENGTH_SHORT).show()
            return
        }
        val labels = employees.map { u ->
            val name = listOfNotNull(u.firstName, u.lastName).joinToString(" ").trim()
            if (name.isNotEmpty()) "$name (${u.email})" else u.email
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Удалить сотрудника из списка")
            .setItems(labels) { _, which ->
                confirmDeleteEmployee(employees[which])
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDeleteEmployee(user: UserWithNames) {
        AlertDialog.Builder(this)
            .setTitle("Удалить из списка?")
            .setMessage(user.email)
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.get(this@HomeActivity).employeeDao().deleteByUserId(user.userId)
                    }
                    refreshEmployeesList()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }


    private fun updateSelectedDateText(dayMillis: Long) {
        val ru = Locale("ru", "RU")
        val df = SimpleDateFormat("EEEE, d MMMM yyyy", ru)
        binding.incCalendar.tvSelectedDate.text = df.format(Date(dayMillis))
    }

    private fun startEndOfDay(millis: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.timeInMillis - 1
        return start to end
    }

    private fun loadSessionsForDay(dayMillis: Long) {
        val (start, end) = startEndOfDay(dayMillis)
        lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) {
                AppDatabase.get(this@HomeActivity)
                    .workSessionDao()
                    .sessionsByDay(start, end)
            }
            val list = if (isHr) sessions else sessions.filter { it.userId == userId }
            sessionsAdapter.submitList(list)
            binding.incCalendar.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }


    private fun setupWeekStrip() {
        val ru = Locale("ru", "RU")
        binding.tvMonth.text = SimpleDateFormat("LLLL", ru).format(Date()).lowercase(ru)

        val tvs: List<TextView> = listOf(
            binding.tvN1, binding.tvN2, binding.tvN3, binding.tvN4, binding.tvN5, binding.tvN6, binding.tvN7
        )
        val cal = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY }
        val today = cal.clone() as Calendar
        val dayOfWeek = ((today.get(Calendar.DAY_OF_WEEK) + 5) % 7)
        val monday = cal.clone() as Calendar
        monday.add(Calendar.DAY_OF_MONTH, -dayOfWeek)

        tvs.forEachIndexed { i, tv ->
            val d = monday.clone() as Calendar
            d.add(Calendar.DAY_OF_MONTH, i)
            tv.text = d.get(Calendar.DAY_OF_MONTH).toString()
            if (i == dayOfWeek) {
                tv.setBackgroundResource(R.drawable.bg_today)
                tv.setTextColor(getColor(android.R.color.white))
            } else {
                tv.background = null
                tv.setTextColor(getColor(android.R.color.black))
            }
        }
    }

    private fun setupTodayTexts() {
        val ru = Locale("ru", "RU")
        val dateFmt = SimpleDateFormat("EEEE, d MMMM", ru)
        binding.tvDate.text = dateFmt.format(Date())
    }

    private suspend fun loadOpenSessionAndRender() {
        if (userId <= 0) return
        val dao = AppDatabase.get(this).workSessionDao()
        openSession = withContext(Dispatchers.IO) { dao.getOpenSessionForUser(userId) }
        renderState()
    }

    private fun renderState() {
        if (openSession == null) {
            binding.btnStart.visibility = View.VISIBLE
            binding.chronometer.visibility = View.GONE
            binding.btnFinish.visibility = View.GONE
            binding.chronometer.stop()
        } else {
            binding.btnStart.visibility = View.GONE
            binding.chronometer.visibility = View.VISIBLE
            binding.btnFinish.visibility = View.VISIBLE

            val base = SystemClock.elapsedRealtime() -
                    (System.currentTimeMillis() - (openSession!!.startTime))
            binding.chronometer.base = base
            binding.chronometer.start()
        }
    }

    private fun ensureCameraPermThenScan() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchQrScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Наведите камеру на QR")
            .setBeepEnabled(true)
            .setOrientationLocked(true)
        qrScannerLauncher.launch(options)
    }

    private suspend fun startWorkAfterScan(qr: String) {
        if (userId <= 0) return
        val now = System.currentTimeMillis()
        val dao = AppDatabase.get(this).workSessionDao()
        val session = WorkSessionEntity(userId = userId, startTime = now, officeId = parseOfficeId(qr))
        withContext(Dispatchers.IO) { dao.insert(session) }
        openSession = session
        Snackbar.make(binding.root, "Смена начата", Snackbar.LENGTH_SHORT).show()
        renderState()
    }

    private suspend fun finishWorkAfterScan(qr: String) {
        val dao = AppDatabase.get(this).workSessionDao()
        val end = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            val open = dao.getOpenSessionForUser(userId)
            if (open != null) dao.finishSession(open.id, end)
        }
        openSession = null
        Snackbar.make(binding.root, "Смена завершена", Snackbar.LENGTH_SHORT).show()
        renderState()
    }

    private fun parseOfficeId(qr: String): String? = qr
}

private class SessionsAdapter :
    ListAdapter<SessionWithUserEmail, SessionsAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<SessionWithUserEmail>() {
        override fun areItemsTheSame(o: SessionWithUserEmail, n: SessionWithUserEmail) = o.id == n.id
        override fun areContentsTheSame(o: SessionWithUserEmail, n: SessionWithUserEmail) = o == n
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(private val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root) {
        private val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
        fun bind(item: SessionWithUserEmail) {
            binding.tvEmail.text = item.userEmail
            val start = Date(item.startTime)
            val text = if (item.endTime != null) {
                val end = Date(item.endTime)
                val durMin = TimeUnit.MILLISECONDS.toMinutes(item.endTime - item.startTime)
                "${tf.format(start)} — ${tf.format(end)}  •  ${durMin} мин"
            } else {
                "${tf.format(start)} — … (ещё в работе)"
            }
            binding.tvTime.text = text
        }
    }
}

private class EmployeesAdapter(
    private val isHr: Boolean,
    private val onClick: (UserWithNames) -> Unit,
    private val onLongDelete: (UserWithNames) -> Unit
) : ListAdapter<UserWithNames, EmployeesAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<UserWithNames>() {
        override fun areItemsTheSame(o: UserWithNames, n: UserWithNames) = o.userId == n.userId
        override fun areContentsTheSame(o: UserWithNames, n: UserWithNames) = o == n
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemEmployeeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, isHr, onClick, onLongDelete)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemEmployeeBinding,
        private val isHr: Boolean,
        private val onClick: (UserWithNames) -> Unit,
        private val onLongDelete: (UserWithNames) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        private val df = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

        fun bind(u: UserWithNames) {
            val name = when {
                !u.firstName.isNullOrBlank() && !u.lastName.isNullOrBlank() -> "${u.firstName} ${u.lastName}"
                !u.firstName.isNullOrBlank() -> u.firstName!!
                !u.lastName.isNullOrBlank() -> u.lastName!!
                else -> u.email
            }
            binding.tvEmail.text = name
            binding.tvMeta.text = "email: ${u.email} • зарегистрирован: ${df.format(Date(u.createdAt))}"

            binding.root.setOnClickListener { onClick(u) }
            if (isHr) {
                binding.root.setOnLongClickListener { onLongDelete(u); true }
            } else {
                binding.root.setOnLongClickListener(null)
            }
        }
    }
}