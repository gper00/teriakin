package com.example.tes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tes.data.AppPreferences
import com.example.tes.data.Session
import com.example.tes.databinding.ActivityMainBinding
import com.example.tes.service.FocusSessionService
import com.example.tes.sound.SoundManager
import com.example.tes.viewmodel.FocusViewModel
import com.example.tes.viewmodel.Screen
import com.example.tes.viewmodel.TimerState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: FocusViewModel
    private lateinit var soundManager: SoundManager

    private var serviceBound = false
    private var focusService: FocusSessionService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            focusService = (service as? FocusSessionService.LocalBinder)?.getService()
            serviceBound = true
            // If alarm was triggered while activity was not in foreground (e.g. user was
            // on Instagram), show the dialog now that we're reconnected.
            if (focusService?.isAlarmActive == true) {
                showAlarmDialog()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            focusService = null
        }
    }

    private val installedApps = mutableListOf<AppInfo>()
    private val selectedApps = mutableSetOf<String>()
    private lateinit var appAdapter: AppListAdapter

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistable permission so we can read this URI across reboots
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            lifecycleScope.launch {
                viewModel.repository.saveAlarmCustomPath(uri.toString())
                finishSave(uri.toString())
            }
        } else {
            Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = FocusViewModel(application as android.app.Application)
        soundManager = SoundManager(this)

        observeViewModel()
        setupClickListeners()
        setupAppList()
        setupHistoryList()
    }

    override fun onResume() {
        super.onResume()
        checkUsageStatsPermission()
    }

    // ===== OBSERVERS =====

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.timerState.collect { state ->
                when (state) {
                    TimerState.IDLE -> showScreen(Screen.HOME)
                    TimerState.RUNNING, TimerState.PAUSED -> showTimerOverlay()
                    TimerState.COMPLETED, TimerState.INTERRUPTED -> { }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is com.example.tes.viewmodel.SessionEvent.Completed -> {
                        Toast.makeText(this@MainActivity, "Focus complete! 🎉", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        lifecycleScope.launch {
            combine(
                viewModel.elapsedSeconds,
                viewModel.sessionDuration,
                viewModel.distractionCount
            ) { elapsed, total, distractions ->
                Triple(elapsed, total, distractions)
            }.collect { (elapsed, total, distractions) ->
                binding.tvOverlayTimer.text = viewModel.formatTime(elapsed)
                binding.progressTimer.max = total
                binding.progressTimer.progress = elapsed
                binding.tvDistractionCount.text = "\uD83D\uDCF1 $distractions distractions"
            }
        }

        lifecycleScope.launch {
            viewModel.currentScreen.collect { screen ->
                showScreen(screen)
            }
        }

        lifecycleScope.launch {
            viewModel.sessionDuration.collect { secs ->
                binding.tvTimer.text = viewModel.formatTime(secs)
            }
        }

        lifecycleScope.launch {
            viewModel.repository.sessionHistory.collect { sessions ->
                if (sessions.isEmpty()) {
                    binding.tvHistoryEmpty.visibility = View.VISIBLE
                    binding.rvHistory.visibility = View.GONE
                } else {
                    binding.tvHistoryEmpty.visibility = View.GONE
                    binding.rvHistory.visibility = View.VISIBLE
                    (binding.rvHistory.adapter as? SessionAdapter)?.submitList(sessions.reversed())
                }
            }
        }
    }

    // ===== CLICK HANDLERS =====

    private fun setupClickListeners() {
        binding.chip5min.setOnClickListener { selectPreset(300) }
        binding.chip15min.setOnClickListener { selectPreset(900) }
        binding.chip25min.setOnClickListener { selectPreset(1500) }
        binding.chip45min.setOnClickListener { selectPreset(2700) }

        binding.btnStart.setOnClickListener { startFocusSession() }
        binding.btnSetup.setOnClickListener { viewModel.navigateTo(Screen.SETUP) }
        binding.btnHistory.setOnClickListener { viewModel.navigateTo(Screen.HISTORY) }

        binding.btnPauseOverlay.setOnClickListener {
            if (viewModel.timerState.value == TimerState.RUNNING) {
                viewModel.pauseSession()
                binding.btnPauseOverlay.setText(R.string.btn_resume)
            } else {
                viewModel.resumeSession()
                binding.btnPauseOverlay.setText(R.string.btn_pause)
            }
        }
        binding.btnStopOverlay.setOnClickListener {
            AlertDialog.Builder(this, R.style.Theme_FocusBuddy_AlertDialog)
                .setTitle(R.string.stop_confirm_title)
                .setMessage(R.string.stop_confirm_msg)
                .setPositiveButton("Yes, end it") { _, _ ->
                    soundManager.stop()
                    viewModel.stopSession()
                    serviceBound = false
                    focusService = null
                    stopService(Intent(this, FocusSessionService::class.java))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnBackSetup.setOnClickListener { viewModel.navigateTo(Screen.HOME) }
        binding.btnSaveSetup.setOnClickListener { saveSetup() }
        binding.btnPreviewAlarm.setOnClickListener { previewAlarm() }
        binding.editSearchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appAdapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnBackHistory.setOnClickListener { viewModel.navigateTo(Screen.HOME) }
    }

    private fun selectPreset(seconds: Int) {
        viewModel.setDuration(seconds)
        listOf(
            binding.chip5min to 300,
            binding.chip15min to 900,
            binding.chip25min to 1500,
            binding.chip45min to 2700
        ).forEach { (chip, sec) ->
            chip.background = getDrawable(
                if (sec == seconds) R.drawable.chip_bg_selected else R.drawable.chip_bg
            )
            chip.setTextColor(
                if (sec == seconds) getColor(R.color.bg_primary) else getColor(R.color.text_secondary)
            )
        }
    }

    // ===== SESSION CONTROLS =====

    private fun startFocusSession() {
        if (serviceBound) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        viewModel.startSession()
        val intent = Intent(this, FocusSessionService::class.java)

        FocusSessionService.onDistractionDetected = {
            runOnUiThread { showAlarmDialog() }
        }
        FocusSessionService.onAlarmDismissed = {
            runOnUiThread { dismissAlarm() }
        }
        FocusSessionService.onSessionEndRequested = {
            runOnUiThread {
                soundManager.stop()
                viewModel.abortSessionFromAlarm()
                serviceBound = false
                focusService = null
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    // ===== ALARM =====

    private fun showAlarmDialog() {
        // Activity may be finishing/destroyed if user switched to Instagram and system
        // reclaimed the activity. AlertDialog.Builder would throw BadTokenException.
        if (isFinishing || isDestroyed) return

        viewModel.triggerDistraction()

        AlertDialog.Builder(this, R.style.Theme_FocusBuddy_AlertDialog)
            .setTitle("\uD83D\uDEA8 GET BACK TO FOCUS!")
            .setMessage("You opened a distracting app!")
            .setPositiveButton("Go Back") { _, _ ->
                soundManager.stop()
                focusService?.dismissAlarm()
                viewModel.dismissAlarm()
            }
            .setNeutralButton("Dismiss & End") { _, _ ->
                soundManager.stop()
                focusService?.dismissAlarm()
                viewModel.abortSessionFromAlarm()
                serviceBound = false
                focusService = null
                stopService(Intent(this, FocusSessionService::class.java))
            }
            .setCancelable(false)
            .show()
    }

    private fun dismissAlarm() {
        soundManager.stop()
        viewModel.dismissAlarm()
    }

    // ===== SCREEN MANAGEMENT =====

    private fun showScreen(screen: Screen) {
        binding.homeScreen.visibility = if (screen == Screen.HOME && viewModel.timerState.value == TimerState.IDLE) View.VISIBLE else View.GONE
        binding.timerOverlay.visibility = if (screen == Screen.HOME && viewModel.timerState.value != TimerState.IDLE) View.VISIBLE else View.GONE
        binding.setupScreen.visibility = if (screen == Screen.SETUP) View.VISIBLE else View.GONE
        binding.historyScreen.visibility = if (screen == Screen.HISTORY) View.VISIBLE else View.GONE
    }

    private fun showTimerOverlay() {
        binding.homeScreen.visibility = View.GONE
        binding.timerOverlay.visibility = View.VISIBLE
        binding.setupScreen.visibility = View.GONE
        binding.historyScreen.visibility = View.GONE
    }

    // ===== SETUP =====

    private fun setupHistoryList() {
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = SessionAdapter()
    }

    private fun setupAppList() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(intent, 0)
        installedApps.clear()
        activities.forEach { resolveInfo ->
            val appName = resolveInfo.loadLabel(pm).toString()
            val packageName = resolveInfo.activityInfo.packageName
            installedApps.add(AppInfo(appName, packageName, resolveInfo.loadIcon(pm)))
        }
        installedApps.sortBy { it.name.lowercase() }

        appAdapter = AppListAdapter(installedApps) { pkg, selected ->
            if (selected) selectedApps.add(pkg) else selectedApps.remove(pkg)
        }

        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = appAdapter

        lifecycleScope.launch {
            val saved = viewModel.repository.distractingApps.first()
            selectedApps.addAll(saved)
            appAdapter.setSelected(saved)
        }

        lifecycleScope.launch {
            val source = viewModel.repository.alarmSource.first()
            when (source) {
                "builtin" -> binding.radioBuiltin.isChecked = true
                "file" -> binding.radioFile.isChecked = true
                "recording" -> binding.radioRecord.isChecked = true
            }
        }
    }

    private fun saveSetup() {
        val checkedId = binding.radioAlarm.checkedRadioButtonId

        // Save distracting apps regardless
        lifecycleScope.launch {
            viewModel.repository.saveDistractingApps(selectedApps.toSet())
        }

        when (checkedId) {
            R.id.radioFile -> {
                filePickerLauncher.launch(arrayOf("audio/*"))
            }
            R.id.radioRecord -> {
                Toast.makeText(this, "Recording not yet available — use Built-in or MP3", Toast.LENGTH_SHORT).show()
            }
            else -> {
                lifecycleScope.launch {
                    viewModel.repository.saveAlarmSource(AppPreferences.ALARM_SOURCE_BUILTIN)
                    finishSave(null)
                }
            }
        }
    }

    private fun finishSave(customPath: String?) {
        lifecycleScope.launch {
            if (customPath != null) {
                viewModel.repository.saveAlarmSource(AppPreferences.ALARM_SOURCE_FILE)
            }
            Toast.makeText(this@MainActivity, "Settings saved", Toast.LENGTH_SHORT).show()
            viewModel.navigateTo(Screen.HOME)
        }
    }

    private fun previewAlarm() {
        soundManager.playBuiltin()
        binding.btnPreviewAlarm.postDelayed({
            soundManager.stop()
        }, 3000)
    }

    // ===== PERMISSIONS =====

    private fun checkUsageStatsPermission() {
        if (!hasUsageStatsPermission()) {
            AlertDialog.Builder(this, R.style.Theme_FocusBuddy_AlertDialog)
                .setTitle(R.string.usage_permission_title)
                .setMessage(R.string.usage_permission_msg)
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    override fun onDestroy() {
        super.onDestroy()
        soundManager.release()
        if (serviceBound) {
            unbindService(connection)
        }
    }
}

// ===== DATA CLASSES =====

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: android.graphics.drawable.Drawable
)

// ===== ADAPTERS =====

class AppListAdapter(
    private val allApps: List<AppInfo>,
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private var filteredList = allApps
    private val selected = mutableSetOf<String>()

    fun setSelected(apps: Set<String>) {
        selected.clear()
        selected.addAll(apps)
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        filteredList = if (query.isBlank()) allApps
        else allApps.filter { it.name.lowercase().contains(query.lowercase()) }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = filteredList[position]
        holder.name.text = app.name
        holder.icon.setImageDrawable(app.icon)

        // Clear listener before setChecked to prevent infinite loop
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = selected.contains(app.packageName)

        // Track selection whether user clicks the CheckBox directly OR the item row
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selected.add(app.packageName) else selected.remove(app.packageName)
            onToggle(app.packageName, isChecked)
        }
        holder.itemView.setOnClickListener {
            holder.checkbox.isChecked = !holder.checkbox.isChecked
        }
    }

    override fun getItemCount() = filteredList.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivAppIcon)
        val name: TextView = view.findViewById(R.id.tvAppName)
        val checkbox: CheckBox = view.findViewById(R.id.cbAppSelect)
    }
}

class SessionAdapter : RecyclerView.Adapter<SessionAdapter.ViewHolder>() {
    private var sessions = listOf<Session>()

    fun submitList(list: List<Session>) {
        sessions = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessions[position]
        val date = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id"))
            .format(Date(session.startTimeMillis))
        holder.date.text = date
        val totalMinutes = session.durationSeconds / 60
        val totalSecs = session.durationSeconds % 60
        holder.duration.text = if (totalMinutes > 0) "${totalMinutes}m ${totalSecs}s" else "${totalSecs}s"
        holder.distractions.text = "\uD83D\uDCF1 ${session.distractions} distractions"

        if (session.completed) {
            holder.status.text = "✓ Completed"
            holder.status.setTextColor(holder.itemView.context.getColor(R.color.primary_green))
        } else {
            holder.status.text = "✗ Interrupted"
            holder.status.setTextColor(holder.itemView.context.getColor(R.color.danger_red))
        }
    }

    override fun getItemCount() = sessions.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val date: TextView = view.findViewById(R.id.tvSessionDate)
        val duration: TextView = view.findViewById(R.id.tvSessionDuration)
        val distractions: TextView = view.findViewById(R.id.tvSessionDistractions)
        val status: TextView = view.findViewById(R.id.tvSessionStatus)
    }
}
