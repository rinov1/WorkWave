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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.workwave.workwave.R
import com.workwave.workwave.data.AppDatabase
import com.workwave.workwave.data.EmployeeEntity
import com.workwave.workwave.data.SessionWithUserEmail
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

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private var userId: Long = -1L
    private var isHr: Boolean = false

    private var openSession: WorkSessionEntity? = null
    private var pendingAction: Action? = null

    // флаг "режим удаления сотрудника"
    private var isDeleteMode: Boolean = false

    // календарь — свод по сотрудникам за день
    private val sessionsAdapter = SessionsAdapter()
    private lateinit var employeesAdapter: EmployeesAdapter

    // все сотрудники, которые сейчас в списке (из Firestore)
    private var allEmployees: List<UserWithNames> = emptyList()

    private enum class Action { START, FINISH }

    // launcher профиля
    private val profileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* no-op */ }

    // Permission-на-запрос камеры
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchQrScanner()
            } else {
                Snackbar.make(
                    binding.root,
                    "Дайте доступ к камере для сканирования QR",
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

        // слушаем список сотрудников из Firestore
        FirebaseEmployees.listenEmployees { list ->
            allEmployees = list
            if (::employeesAdapter.isInitialized) {
                employeesAdapter.submitList(list)
            }
            sessionsAdapter.updateUsers(list.associateBy { it.userId })
        }

        // шапка недели и дата
        setupWeekStrip()
        setupTodayTexts()

        // старт и стоп смены
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
                R.id.nav_people   -> { showEmployeesTab(); true }
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
        if (binding.incCalendar.rvSessions.adapter == null) initCalendarSection()
    }

    private fun showEmployeesTab() {
        binding.toolbar.title = "Сотрудники"
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
        updateSelectedDateText(today.timeInMillis)
        loadSessionsForDay(today.timeInMillis)

        binding.incCalendar.calendarView.setOnDateChangeListener { _, y, m, d ->
            val cal = Calendar.getInstance().apply {
                set(y, m, d, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            updateSelectedDateText(cal.timeInMillis)
            loadSessionsForDay(cal.timeInMillis)
        }
    }

    private fun initEmployeesSection() {
        employeesAdapter = EmployeesAdapter(
            onClick = { user -> onEmployeeClicked(user) }
        )
        binding.incEmployees.rvEmployees.layoutManager = LinearLayoutManager(this)
        binding.incEmployees.rvEmployees.adapter = employeesAdapter

        if (allEmployees.isNotEmpty()) {
            employeesAdapter.submitList(allEmployees)
        }

        setupEmployeesButtons()
    }

    /** кнопки "Добавить" и "Удалить" сверху списка сотрудников */
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

    /** клик по сотруднику в списке */
    private fun onEmployeeClicked(user: UserWithNames) {
        if (isHr && isDeleteMode) {
            confirmDeleteEmployee(user)
        } else {
            openEmployee(user.userId)
        }
    }

    /** включение / выключение режима удаления */
    private fun toggleDeleteMode() {
        if (!isHr) return
        isDeleteMode = !isDeleteMode
        val btn = binding.incEmployees.btnDeleteEmployee
        btn.text = if (isDeleteMode) "Отменить удаление" else "Удалить"
        val msg = if (isDeleteMode) {
            "Нажмите на сотрудника, которого хотите удалить"
        } else {
            "Режим удаления выключен"
        }
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }

    /** диалог подтверждения удаления сотрудника */
    private fun confirmDeleteEmployee(user: UserWithNames) {
        AlertDialog.Builder(this)
            .setTitle("Удалить сотрудника?")
            .setMessage("Удалить ${user.email}? Также будут удалены все его смены.")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch { deleteEmployee(user) }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** фактическое удаление сотрудника из Room и Firestore */
    private suspend fun deleteEmployee(user: UserWithNames) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.get(this@HomeActivity)
            db.workSessionDao().deleteByUserId(user.userId)
            db.employeeDao().deleteByUserId(user.userId)
            db.userDao().deleteById(user.userId)
        }
        // Firestore
        FirebaseEmployees.deleteEmployeeByUserId(user.userId)

        isDeleteMode = false
        binding.incEmployees.btnDeleteEmployee.text = "Удалить"

        Snackbar.make(binding.root, "Сотрудник удалён", Snackbar.LENGTH_SHORT).show()
    }

    /**
     * Кнопка "Добавить": показываем пользователей, которых нет в списке сотрудников,
     * выбираем одного и добавляем в коллекцию employees (Firestore).
     */
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

                    // пропускаем HR и тех, кто уже в списке сотрудников
                    if (isHrUser || activeIds.contains(id)) {
                        null
                    } else {
                        SimpleUser(id, email)
                    }
                }

                if (candidates.isEmpty()) {
                    Snackbar.make(binding.root, "Нет сотрудников для добавления", Snackbar.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val labels = candidates.map { it.email }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle("Выберите сотрудника для добавления")
                    .setItems(labels) { _, which ->
                        val u = candidates[which]
                        // создаём минимальную карточку сотрудника в Firestore
                        val emp = EmployeeEntity(
                            userId = u.userId,
                            email = u.email
                        )
                        FirebaseEmployees.upsertEmployee(emp)
                        Snackbar.make(binding.root, "Сотрудник добавлен", Snackbar.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            .addOnFailureListener { e ->
                Snackbar.make(
                    binding.root,
                    "Ошибка загрузки списка пользователей: ${e.localizedMessage ?: "неизвестная"}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
    }

    /**
     * Открытие профиля сотрудника.
     *
     * - HR может редактировать любого.
     * - Обычный сотрудник может редактировать только себя.
     */
    private fun openEmployee(targetUserId: Long) {
        val canEdit = isHr || (targetUserId == userId)

        profileLauncher.launch(
            Intent(this, ProfileActivity::class.java)
                .putExtra("userId", targetUserId)
                .putExtra("editable", canEdit)
                .putExtra("hrMode", isHr)
        )
    }

    // ————— ШАПКА НЕДЕЛИ И ДАТА —————

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
        val fmt = SimpleDateFormat("EEEE, d MMMM", ru)
        binding.tvDate.text = fmt.format(Date())
    }

    // ————————————————————————————————————

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

            val visible = if (isHr) sessions else sessions.filter { it.userId == userId }

            val summaries = visible
                .filter { it.endTime != null }
                .groupBy { it.userId to it.userEmail }
                .map { (key, list) ->
                    val totalMs = list.sumOf { s -> (s.endTime!! - s.startTime) }
                    DaySummaryItem(
                        userId = key.first,
                        userEmail = key.second,
                        totalMinutes = TimeUnit.MILLISECONDS.toMinutes(totalMs)
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
                    "Ошибка сканирования: ${e.localizedMessage ?: "неизвестная ошибка"}",
                    Snackbar.LENGTH_LONG
                ).show()
                pendingAction = null
            }
    }

    private suspend fun startWorkAfterScan(qr: String) {
        if (userId <= 0) return

        val officeId = parseOfficeId(qr)
        if (officeId == null) {
            Snackbar.make(binding.root, "Некорректный QR-код", Snackbar.LENGTH_LONG).show()
            return
        }

        val dao = AppDatabase.get(this).workSessionDao()

        val existing = withContext(Dispatchers.IO) {
            dao.getOpenSessionForUser(userId)
        }
        if (existing != null) {
            openSession = existing
            Snackbar.make(binding.root, "Смена уже начата", Snackbar.LENGTH_SHORT).show()
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
        Snackbar.make(binding.root, "Смена начата", Snackbar.LENGTH_SHORT).show()
        renderState()
    }

    private suspend fun finishWorkAfterScan(qr: String) {
        if (userId <= 0) return

        val officeId = parseOfficeId(qr)
        if (officeId == null) {
            Snackbar.make(binding.root, "Некорректный QR-код", Snackbar.LENGTH_LONG).show()
            return
        }

        val dao = AppDatabase.get(this).workSessionDao()

        val open = withContext(Dispatchers.IO) {
            dao.getOpenSessionForUser(userId)
        }

        if (open == null) {
            Snackbar.make(binding.root, "Нет активной смены", Snackbar.LENGTH_LONG).show()
            return
        }

        if (!open.officeId.isNullOrEmpty() && open.officeId != officeId) {
            Snackbar.make(
                binding.root,
                "Сканирован неверный QR-код (должен быть тот же, что при входе)",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        val end = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            dao.finishSession(open.id, end)
        }

        openSession = null
        Snackbar.make(binding.root, "Смена завершена", Snackbar.LENGTH_SHORT).show()
        renderState()
    }

    private fun parseOfficeId(qr: String): String? {
        val trimmed = qr.trim()
        return if (trimmed.isEmpty()) null else trimmed
    }

    // вспомогательный класс для кандидатов на добавление
    private data class SimpleUser(
        val userId: Long,
        val email: String
    )
}

/* ---------- модель для "свод по сотруднику за день" ---------- */
private data class DaySummaryItem(
    val userId: Long,
    val userEmail: String,
    val totalMinutes: Long
)

/* ---------- адаптер календаря ---------- */
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

            val hours = item.totalMinutes / 60
            val minutes = item.totalMinutes % 60
            val timeText = if (hours > 0) {
                "${hours} ч ${minutes} мин"
            } else {
                "${minutes} мин"
            }
            binding.tvTime.text = timeText
        }
    }
}

/* ---------- адаптер списка сотрудников ---------- */
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