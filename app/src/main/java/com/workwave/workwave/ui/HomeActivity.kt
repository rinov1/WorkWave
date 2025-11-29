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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.workwave.workwave.R
import com.workwave.workwave.data.AppDatabase
import com.workwave.workwave.data.EmployeeEntity
import com.workwave.workwave.data.UserWithNames
import com.workwave.workwave.data.WorkSessionEntity
import com.workwave.workwave.databinding.ActivityHomeBinding
import com.workwave.workwave.databinding.ItemEmployeeBinding
import com.workwave.workwave.databinding.ItemSessionBinding
import com.workwave.workwave.firebase.FirebaseEmployees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HomeActivity : BaseActivity() {

    private lateinit var binding: ActivityHomeBinding
    private var userId: Long = -1L
    private var isHr: Boolean = false

    private var openSession: WorkSessionEntity? = null
    private var pendingAction: Action? = null

    private var isDeleteMode: Boolean = false

    private val sessionsAdapter = SessionsAdapter()
    private lateinit var employeesAdapter: EmployeesAdapter

    private var allEmployees: List<UserWithNames> = emptyList()

    // Статус членства текущего пользователя
    private var isEmployeeActive: Boolean = true
    private var myMembershipReg: ListenerRegistration? = null
    private val membershipPrefs by lazy {
        getSharedPreferences("membership", MODE_PRIVATE)
    }

    // Активные userId (для HR фильтрации смен)
    private var activeUserIds: Set<Long> = emptySet()

    // Последний выбранный день календаря
    private var selectedDayMillis: Long = 0L

    private enum class Action { START, FINISH }
    private enum class EmployeesSort { NAME, DATE }

    private var employeesSort: EmployeesSort = EmployeesSort.NAME

    private val settingsPrefs by lazy {
        getSharedPreferences("settings", MODE_PRIVATE)
    }

    private val profileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchQrScanner()
            } else {
                Snackbar.make(
                    binding.root,
                    getString(R.string.camera_permission_denied),
                    Snackbar.LENGTH_LONG
                ).show()
                pendingAction = null
            }
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
        binding.toolbar.title = getString(R.string.home_title)
        binding.toolbar.setNavigationOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_profile) {
                val intent = Intent(this, ProfileActivity::class.java)
                    .putExtra("userId", userId)
                    .putExtra("editable", true)
                    .putExtra("hrMode", isHr)
                    .putExtra("ownProfile", true)
                profileLauncher.launch(intent)
                true
            } else false
        }

        // Слушаем список активных сотрудников (для HR и сотрудников с доступом)
        FirebaseEmployees.listenEmployees { list ->
            allEmployees = list
            activeUserIds = list.map { it.userId }.toSet()
            applyEmployeesSort()
            refreshCalendarForMembershipChange()
        }

        // Слушаем свой статус (только если не HR)
        if (!isHr && userId > 0) {
            myMembershipReg = FirebaseEmployees.listenEmployeeActive(userId) { active ->
                onMyMembershipChanged(active)
            }
            // Первичная установка видимости People на основании локального кэша
            val cached = membershipPrefs.getBoolean("active_$userId", true)
            isEmployeeActive = cached
            updatePeopleTabVisibility()
        } else {
            // HR всегда видит вкладку Employees
            isEmployeeActive = true
            updatePeopleTabVisibility()
        }

        setupWeekStrip()
        setupTodayTexts()
        applySettingsToUi()

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
                R.id.nav_home     -> { showHomeTab(); true }
                R.id.nav_calendar -> { showCalendarTab(); true }
                R.id.nav_people   -> {
                    if (!binding.bottomNav.menu.findItem(R.id.nav_people).isVisible) {
                        showHomeTab(); false
                    } else {
                        showEmployeesTab(); true
                    }
                }
                else -> false
            }
        }
        showHomeTab()

        lifecycleScope.launch { loadOpenSessionAndRender() }
    }

    override fun onDestroy() {
        super.onDestroy()
        myMembershipReg?.remove()
    }

    override fun onResume() {
        super.onResume()
        applySettingsToUi()
    }

    private fun onMyMembershipChanged(active: Boolean) {
        val key = "active_$userId"
        val prev = membershipPrefs.getAll()[key] as? Boolean
        if (prev != null && prev != active) {
            if (!active) {
                Snackbar.make(binding.root, getString(R.string.emp_you_were_removed), Snackbar.LENGTH_LONG).show()
            } else {
                Snackbar.make(binding.root, getString(R.string.emp_you_were_added), Snackbar.LENGTH_LONG).show()
            }
        }
        membershipPrefs.edit().putBoolean(key, active).apply()
        isEmployeeActive = active
        updatePeopleTabVisibility()
        refreshCalendarForMembershipChange()
    }

    private fun updatePeopleTabVisibility() {
        val visible = isHr || isEmployeeActive
        val item = binding.bottomNav.menu.findItem(R.id.nav_people)
        item.isVisible = visible
        if (!visible && binding.bottomNav.selectedItemId == R.id.nav_people) {
            binding.bottomNav.selectedItemId = R.id.nav_home
            showHomeTab()
        }
    }

    private fun applySettingsToUi() {
        val use24 = settingsPrefs.getBoolean("time_24h", true)
        if (use24) {
            binding.tcTime.format24Hour = "HH:mm"
            binding.tcTime.format12Hour = null
        } else {
            binding.tcTime.format24Hour = null
            binding.tcTime.format12Hour = "hh:mm a"
        }
        setupTodayTexts()
    }

    private fun sortEmployeesByName(list: List<UserWithNames>): List<UserWithNames> {
        val loc = Locale.getDefault()

        fun UserWithNames.displayNameLower(): String {
            val name = when {
                !firstName.isNullOrBlank() && !lastName.isNullOrBlank() -> "$firstName $lastName"
                !firstName.isNullOrBlank() -> firstName!!
                !lastName.isNullOrBlank() -> lastName!!
                else -> email
            }
            return name.lowercase(loc)
        }

        return list.sortedWith(
            compareBy<UserWithNames>(
                { it.displayNameLower() },
                { it.email.lowercase(loc) }
            )
        )
    }

    private fun sortEmployeesByDate(list: List<UserWithNames>): List<UserWithNames> {
        return list.sortedBy { it.createdAt }
    }

    private fun applyEmployeesSort() {
        val sorted = when (employeesSort) {
            EmployeesSort.NAME -> sortEmployeesByName(allEmployees)
            EmployeesSort.DATE -> sortEmployeesByDate(allEmployees)
        }
        if (::employeesAdapter.isInitialized) {
            employeesAdapter.submitList(sorted)
        }
        sessionsAdapter.updateUsers(sorted.associateBy { it.userId })
    }

    private fun showHomeTab() {
        binding.toolbar.title = getString(R.string.home_title)
        binding.homeContent.visibility = View.VISIBLE
        binding.incCalendar.root.visibility = View.GONE
        binding.incEmployees.root.visibility = View.GONE
    }

    private fun showCalendarTab() {
        binding.toolbar.title = getString(R.string.calendar_title)
        binding.homeContent.visibility = View.GONE
        binding.incCalendar.root.visibility = View.VISIBLE
        binding.incEmployees.root.visibility = View.GONE
        if (binding.incCalendar.rvSessions.adapter == null) initCalendarSection()
    }

    private fun showEmployeesTab() {
        binding.toolbar.title = getString(R.string.employees_title)
        binding.homeContent.visibility = View.GONE
        binding.incCalendar.root.visibility = View.GONE
        binding.incEmployees.root.visibility = View.VISIBLE
        if (binding.incEmployees.rvEmployees.adapter == null) initEmployeesSection()
    }

    private fun initCalendarSection() {
        binding.incCalendar.rvSessions.layoutManager = LinearLayoutManager(this)
        binding.incCalendar.rvSessions.adapter = sessionsAdapter

        val today = Calendar.getInstance()
        binding.incCalendar.calendarView.date = today.timeInMillis
        selectedDayMillis = today.timeInMillis
        updateSelectedDateText(selectedDayMillis)
        loadSessionsForDay(selectedDayMillis)

        binding.incCalendar.calendarView.setOnDateChangeListener { _, y, m, d ->
            val cal = Calendar.getInstance().apply {
                set(y, m, d, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            selectedDayMillis = cal.timeInMillis
            updateSelectedDateText(selectedDayMillis)
            loadSessionsForDay(selectedDayMillis)
        }
    }

    private fun initEmployeesSection() {
        employeesAdapter = EmployeesAdapter(
            onClick = { user -> onEmployeeClicked(user) }
        )
        binding.incEmployees.rvEmployees.layoutManager = LinearLayoutManager(this)
        binding.incEmployees.rvEmployees.adapter = employeesAdapter

        applyEmployeesSort()
        setupEmployeesButtons()
        setupSortButtons()
    }

    private fun setupEmployeesButtons() {
        val addBtn = binding.incEmployees.btnAddEmployee
        val delBtn = binding.incEmployees.btnDeleteEmployee

        if (!isHr) {
            addBtn.visibility = View.GONE
            delBtn.visibility = View.GONE
        } else {
            addBtn.visibility = View.VISIBLE
            delBtn.visibility = View.VISIBLE

            addBtn.setOnClickListener { showAddEmployeeDialog() }
            delBtn.setOnClickListener { toggleDeleteMode() }
        }
    }

    private fun setupSortButtons() {
        val btnName = binding.incEmployees.btnSortName
        val btnDate = binding.incEmployees.btnSortDate

        fun updateState() {
            btnName.isEnabled = employeesSort != EmployeesSort.NAME
            btnDate.isEnabled = employeesSort != EmployeesSort.DATE
        }

        btnName.setOnClickListener {
            employeesSort = EmployeesSort.NAME
            applyEmployeesSort()
            updateState()
        }

        btnDate.setOnClickListener {
            employeesSort = EmployeesSort.DATE
            applyEmployeesSort()
            updateState()
        }

        updateState()
    }

    private fun onEmployeeClicked(user: UserWithNames) {
        if (isHr && isDeleteMode) {
            confirmDeleteEmployee(user)
        } else {
            openEmployee(user.userId)
        }
    }

    private fun openEmployee(targetUserId: Long) {
        val canEdit = isHr || (targetUserId == userId)

        profileLauncher.launch(
            Intent(this, ProfileActivity::class.java)
                .putExtra("userId", targetUserId)
                .putExtra("editable", canEdit)
                .putExtra("hrMode", isHr)
                .putExtra("ownProfile", targetUserId == userId)
        )
    }

    private fun toggleDeleteMode() {
        if (!isHr) return
        isDeleteMode = !isDeleteMode
        val btn = binding.incEmployees.btnDeleteEmployee
        btn.text = if (isDeleteMode) getString(R.string.delete_cancel) else getString(R.string.delete)
        val msg = if (isDeleteMode) {
            getString(R.string.delete_mode_on)
        } else {
            getString(R.string.delete_mode_off)
        }
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun confirmDeleteEmployee(user: UserWithNames) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_employee_title))
            .setMessage(getString(R.string.delete_employee_message, user.email))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch { deleteEmployee(user) }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private suspend fun deleteEmployee(user: UserWithNames) {
        FirebaseEmployees.deleteEmployeeByUserId(user.userId)
        isDeleteMode = false
        binding.incEmployees.btnDeleteEmployee.text = getString(R.string.delete)
        Snackbar.make(binding.root, getString(R.string.employee_removed_from_list), Snackbar.LENGTH_SHORT).show()
        // обновим календарь — сработает и через слушатель, но сделаем мгновенно
        refreshCalendarForMembershipChange()
    }

    private fun showAddEmployeeDialog() {
        if (!isHr) return

        val activeIds = allEmployees.map { it.userId }.toSet()
        val db = Firebase.firestore

        db.collection("users")
            .get()
            .addOnSuccessListener { snap ->
                val candidates = snap.documents.mapNotNull { d ->
                    val id = d.getLong("userId") ?: return@mapNotNull null
                    val email = d.getString("email") ?: return@mapNotNull null
                    val isHrUser = d.getBoolean("isHr") ?: false
                    if (isHrUser || activeIds.contains(id)) {
                        null
                    } else {
                        SimpleUser(id, email)
                    }
                }

                if (candidates.isEmpty()) {
                    Snackbar.make(binding.root, getString(R.string.no_employees_to_add), Snackbar.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val labels = candidates.map { it.email }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.choose_employee_to_add))
                    .setItems(labels) { _, which ->
                        val u = candidates[which]
                        lifecycleScope.launch {
                            val empFromDb = withContext(Dispatchers.IO) {
                                AppDatabase.get(this@HomeActivity)
                                    .employeeDao()
                                    .findByUserId(u.userId)
                            }
                            val emp = empFromDb ?: EmployeeEntity(
                                userId = u.userId,
                                email = u.email
                            )
                            FirebaseEmployees.upsertEmployee(emp) // активируем сотрудника
                            Snackbar.make(binding.root, getString(R.string.employee_added), Snackbar.LENGTH_SHORT).show()
                            // обновим календарь
                            refreshCalendarForMembershipChange()
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            .addOnFailureListener { e ->
                Snackbar.make(
                    binding.root,
                    getString(R.string.error_loading_users, e.localizedMessage ?: "unknown"),
                    Snackbar.LENGTH_LONG
                ).show()
            }
    }

    private fun setupWeekStrip() {
        val ru = Locale("ru", "RU")

        binding.tvMonth.text = SimpleDateFormat("LLLL", ru).format(Date()).lowercase(ru)

        val tvs: List<TextView> = listOf(
            binding.tvN1, binding.tvN2, binding.tvN3, binding.tvN4,
            binding.tvN5, binding.tvN6, binding.tvN7
        )

        val cal = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY }
        val today = cal.clone() as Calendar
        val dayOfWeek = ((today.get(Calendar.DAY_OF_WEEK) + 5) % 7)

        val monday = cal.clone() as Calendar
        monday.add(Calendar.DAY_OF_MONTH, -dayOfWeek)

        // Цвета из темы (видимые в тёмной/светлой теме)
        val defaultTextColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        val todayTextColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimary)

        tvs.forEachIndexed { i, tv ->
            val d = monday.clone() as Calendar
            d.add(Calendar.DAY_OF_MONTH, i)
            tv.text = d.get(Calendar.DAY_OF_MONTH).toString()

            if (i == dayOfWeek) {
                tv.setBackgroundResource(R.drawable.bg_today)
                tv.setTextColor(todayTextColor)
            } else {
                tv.background = null
                tv.setTextColor(defaultTextColor)
            }
        }
    }

    private fun setupTodayTexts() {
        val pattern = settingsPrefs.getString("date_format", "EEEE, d MMMM") ?: "EEEE, d MMMM"
        val df = SimpleDateFormat(pattern, Locale.getDefault())
        binding.tvDate.text = df.format(Date())
    }

    private fun updateSelectedDateText(dayMillis: Long) {
        val pattern = settingsPrefs.getString("date_format", "EEEE, d MMMM") ?: "EEEE, d MMMM"
        val df = SimpleDateFormat(pattern, Locale.getDefault())
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

            // Фильтрация по членству:
            // - HR видит только смены активных сотрудников
            // - обычный пользователь видит свои смены только если активен
            val visible = when {
                isHr -> sessions.filter { it.userId in activeUserIds }
                else -> if (isEmployeeActive) sessions.filter { it.userId == userId } else emptyList()
            }

            val summaries = visible
                .filter { it.endTime != null }
                .groupBy { it.userId to it.userEmail }
                .map { (key, list) ->
                    val firstStart = list.minOfOrNull { s -> s.startTime }
                    val lastEnd = list.maxOfOrNull { s -> s.endTime!! }
                    val totalMs = list.sumOf { s -> (s.endTime!! - s.startTime) }
                    DaySummaryItem(
                        userId = key.first,
                        userEmail = key.second,
                        totalMillis = totalMs,
                        firstStart = firstStart,
                        lastEnd = lastEnd
                    )
                }
                .sortedBy { it.userEmail }

            sessionsAdapter.submitList(summaries)
            binding.incCalendar.tvEmpty.visibility = if (summaries.isEmpty()) View.VISIBLE else View.GONE
        }
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
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val contents = barcode.rawValue ?: return@addOnSuccessListener
                when (pendingAction) {
                    Action.START -> lifecycleScope.launch { startWorkAfterScan(contents) }
                    Action.FINISH -> lifecycleScope.launch { finishWorkAfterScan(contents) }
                    else -> {}
                }
                pendingAction = null
            }
            .addOnCanceledListener {
                pendingAction = null
            }
            .addOnFailureListener { e ->
                Snackbar.make(
                    binding.root,
                    getString(R.string.scan_error, e.localizedMessage ?: "unknown"),
                    Snackbar.LENGTH_LONG
                ).show()
                pendingAction = null
            }
    }

    private suspend fun startWorkAfterScan(qr: String) {
        if (userId <= 0) return

        val officeId = parseOfficeId(qr)
        if (officeId == null) {
            Snackbar.make(binding.root, getString(R.string.invalid_qr), Snackbar.LENGTH_LONG).show()
            return
        }

        val dao = AppDatabase.get(this).workSessionDao()

        val existing = withContext(Dispatchers.IO) {
            dao.getOpenSessionForUser(userId)
        }
        if (existing != null) {
            openSession = existing
            Snackbar.make(binding.root, getString(R.string.shift_already_started), Snackbar.LENGTH_SHORT).show()
            renderState()
            return
        }

        val now = System.currentTimeMillis()
        val session = WorkSessionEntity(
            userId = userId,
            startTime = now,
            officeId = officeId
        )

        withContext(Dispatchers.IO) {
            dao.insert(session)
        }

        openSession = session
        Snackbar.make(binding.root, getString(R.string.shift_started), Snackbar.LENGTH_SHORT).show()
        renderState()
    }

    private suspend fun finishWorkAfterScan(qr: String) {
        if (userId <= 0) return

        val officeId = parseOfficeId(qr)
        if (officeId == null) {
            Snackbar.make(binding.root, getString(R.string.invalid_qr), Snackbar.LENGTH_LONG).show()
            return
        }

        val dao = AppDatabase.get(this).workSessionDao()

        val open = withContext(Dispatchers.IO) {
            dao.getOpenSessionForUser(userId)
        }

        if (open == null) {
            Snackbar.make(binding.root, getString(R.string.no_active_shift), Snackbar.LENGTH_LONG).show()
            return
        }

        if (!open.officeId.isNullOrEmpty() && open.officeId != officeId) {
            Snackbar.make(
                binding.root,
                getString(R.string.wrong_qr_for_finish),
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        val end = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            dao.finishSession(open.id, end)
        }

        openSession = null
        Snackbar.make(binding.root, getString(R.string.shift_finished), Snackbar.LENGTH_SHORT).show()
        renderState()
    }

    private fun parseOfficeId(qr: String): String? {
        val trimmed = qr.trim()
        return if (trimmed.isEmpty()) null else trimmed
    }

    // Обновление календаря при изменении членства/списка сотрудников
    private fun refreshCalendarForMembershipChange() {
        val day = if (selectedDayMillis != 0L) selectedDayMillis else Calendar.getInstance().timeInMillis
        loadSessionsForDay(day)
    }

    private data class SimpleUser(
        val userId: Long,
        val email: String
    )
}

private data class DaySummaryItem(
    val userId: Long,
    val userEmail: String,
    val totalMillis: Long,
    val firstStart: Long?,
    val lastEnd: Long?
)

private class SessionsAdapter :
    ListAdapter<DaySummaryItem, SessionsAdapter.VH>(Diff) {

    private var usersById: Map<Long, UserWithNames> = emptyMap()

    fun updateUsers(map: Map<Long, UserWithNames>) {
        usersById = map
        notifyDataSetChanged()
    }

    object Diff : DiffUtil.ItemCallback<DaySummaryItem>() {
        override fun areItemsTheSame(o: DaySummaryItem, n: DaySummaryItem) =
            o.userId == n.userId && o.userEmail == n.userEmail

        override fun areContentsTheSame(o: DaySummaryItem, n: DaySummaryItem) = o == n
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), usersById)
    }

    class VH(private val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root) {

        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        private fun formatMinutesRu(m: Long): String {
            val n = (m % 100).toInt()
            val form = when {
                n in 11..14 -> "минут"
                n % 10 == 1 -> "минута"
                n % 10 in 2..4 -> "минуты"
                else -> "минут"
            }
            return "$m $form"
        }

        fun bind(item: DaySummaryItem, usersById: Map<Long, UserWithNames>) {
            val user = usersById[item.userId]

            val name = when {
                user == null -> null
                !user.firstName.isNullOrBlank() && !user.lastName.isNullOrBlank() ->
                    "${user.firstName} ${user.lastName}"
                !user.firstName.isNullOrBlank() -> user.firstName
                !user.lastName.isNullOrBlank() -> user.lastName
                else -> null
            }

            val title = if (name != null) {
                "$name (${item.userEmail})"
            } else {
                item.userEmail
            }
            binding.tvEmail.text = title

            val startStr = item.firstStart?.let { timeFmt.format(Date(it)) } ?: "?"
            val endStr = item.lastEnd?.let { timeFmt.format(Date(it)) } ?: "?"

            val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(item.totalMillis)
            val durationText = formatMinutesRu(totalMinutes)

            binding.tvTime.text = "$durationText $startStr - $endStr"
        }
    }
}

private class EmployeesAdapter(
    private val onClick: (UserWithNames) -> Unit
) : ListAdapter<UserWithNames, EmployeesAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<UserWithNames>() {
        override fun areItemsTheSame(o: UserWithNames, n: UserWithNames) = o.userId == n.userId
        override fun areContentsTheSame(o: UserWithNames, n: UserWithNames) = o == n
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemEmployeeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemEmployeeBinding,
        private val onClick: (UserWithNames) -> Unit
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
            binding.tvMeta.text =
                "email: ${u.email} • зарегистрирован: ${df.format(Date(u.createdAt))}"

            binding.root.setOnClickListener { onClick(u) }
        }
    }
}