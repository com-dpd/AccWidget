package com.dp.accwidget.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dp.accwidget.AccWidgetApp
import com.dp.accwidget.R
import com.dp.accwidget.data.AccSettings
import com.dp.accwidget.databinding.ActivityMainBinding
import com.dp.accwidget.smart.SmartChargeEngine
import com.dp.accwidget.smart.SmartChargeScheduler
import com.dp.accwidget.widget.WidgetTicker
import com.dp.accwidget.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var bindingUiFromStore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            AccWidgetApp.get(this@MainActivity).ensureRootProbed()
        }

        lifecycleScope.launch {
            AccWidgetApp.get(this@MainActivity).settings.settingsFlow.collectLatest { s ->
                bindingUiFromStore = true
                binding.switchControl.isChecked = s.controlEnabled
                if (!binding.inputResume.isFocused) binding.inputResume.setText(s.resumeCapacity.toString())
                if (!binding.inputPause.isFocused) binding.inputPause.setText(s.pauseCapacity.toString())
                if (!binding.inputCurrent.isFocused) binding.inputCurrent.setText(s.maxCurrentMa.toString())
                binding.switchSmart.isChecked = s.smartEnabled
                if (!binding.inputSmartTarget.isFocused) {
                    binding.inputSmartTarget.setText(s.smartTargetPct.toString())
                }
                if (!binding.inputSmartTime.isFocused) {
                    binding.inputSmartTime.setText(
                        "%02d:%02d".format(s.smartDeadlineHour, s.smartDeadlineMinute),
                    )
                }
                if (!binding.inputSmartLead.isFocused) {
                    binding.inputSmartLead.setText(s.smartLeadMinutes.toString())
                }
                if (!binding.inputSmartTick.isFocused) {
                    binding.inputSmartTick.setText(s.smartTickMinutes.toString())
                }
                binding.switchHideIcon.isChecked = s.hideLauncherIcon
                updateRootBanner(s.rootGranted)
                refreshProfileSummary()
                bindingUiFromStore = false
            }
        }

        lifecycleScope.launch {
            while (isActive) {
                refreshLiveBattery()
                delay(2_000L)
            }
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!bindingUiFromStore) refreshProfileSummary()
            }
        }
        listOf(
            binding.inputResume,
            binding.inputPause,
            binding.inputCurrent,
            binding.inputSmartTarget,
            binding.inputSmartTime,
            binding.inputSmartLead,
            binding.inputSmartTick,
        ).forEach { it.addTextChangedListener(watcher) }

        binding.switchControl.setOnCheckedChangeListener { _, _ ->
            if (!bindingUiFromStore) refreshProfileSummary()
        }
        binding.switchSmart.setOnCheckedChangeListener { _, _ ->
            if (!bindingUiFromStore) refreshProfileSummary()
        }
        binding.switchHideIcon.setOnCheckedChangeListener { _, checked ->
            if (bindingUiFromStore) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                AccWidgetApp.get(this@MainActivity).settings.update {
                    it.copy(hideLauncherIcon = checked)
                }
                applyLauncherVisibility(checked)
                Toast.makeText(
                    this@MainActivity,
                    if (checked) R.string.launcher_hidden else R.string.launcher_shown,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        binding.btnRefresh.setOnClickListener { resetToDefaults() }
        binding.btnApply.setOnClickListener { applyFromUi() }
        binding.btnPermissions.setOnClickListener {
            requestNotifications()
            maybeRequestExactAlarms()
            maybeIgnoreBatteryOptimizations()
        }
    }

    private fun updateRootBanner(rootGranted: Boolean?) {
        when (rootGranted) {
            null -> {
                binding.textRootStatus.setText(R.string.root_checking)
                binding.textRootStatus.setTextColor(
                    ContextCompat.getColor(this, android.R.color.darker_gray),
                )
            }
            true -> {
                binding.textRootStatus.setText(R.string.root_granted)
                binding.textRootStatus.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_green_dark),
                )
            }
            false -> {
                binding.textRootStatus.setText(R.string.root_denied)
                binding.textRootStatus.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_red_dark),
                )
            }
        }
    }

    private suspend fun refreshLiveBattery() {
        val batt = withContext(Dispatchers.IO) {
            try {
                AccWidgetApp.get(this@MainActivity).acc.readBattery()
            } catch (_: Throwable) {
                null
            }
        } ?: return
        val plugged = if (batt.plugged) "yes" else "no"
        binding.textLiveBattery.text = getString(
            R.string.live_battery_fmt,
            batt.capacity,
            batt.status,
            WidgetUpdater.formatCurrent(batt.currentMaSigned),
            plugged,
        )
        binding.textLiveBattery.setTextColor(
            when {
                batt.currentMaSigned > 0 -> 0xFF1B6B4A.toInt()
                batt.currentMaSigned < 0 -> 0xFFC62828.toInt()
                else -> ContextCompat.getColor(this, android.R.color.darker_gray)
            },
        )
    }

    private fun refreshProfileSummary() {
        val resume = binding.inputResume.text.toString().toIntOrNull() ?: 60
        val pause = binding.inputPause.text.toString().toIntOrNull() ?: 80
        val target = binding.inputSmartTarget.text.toString().toIntOrNull() ?: 80
        val time = binding.inputSmartTime.text.toString().trim()
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        binding.textStatus.text = SmartChargeEngine.profileSummary(
            controlEnabled = binding.switchControl.isChecked,
            resume = resume,
            pause = pause,
            smartEnabled = binding.switchSmart.isChecked,
            smartTarget = target,
            smartHour = hour,
            smartMinute = minute,
        )
    }

    private fun resetToDefaults() {
        lifecycleScope.launch {
            val defaults = AccSettings()
            withContext(Dispatchers.IO) {
                val app = AccWidgetApp.get(this@MainActivity)
                val prevRoot = app.settings.get().rootGranted
                app.settings.update {
                    defaults.copy(rootGranted = prevRoot)
                }
                applyLauncherVisibility(false)
                SmartChargeScheduler.cancelAll(this@MainActivity)
                if (prevRoot == true) {
                    app.acc.disableControl()
                }
                WidgetTicker.ensureRunning(this@MainActivity)
            }
            WidgetUpdater.requestUpdate(this@MainActivity)
            Toast.makeText(this@MainActivity, R.string.reset_done, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyFromUi() {
        lifecycleScope.launch {
            val resume = binding.inputResume.text.toString().toIntOrNull()?.coerceIn(1, 99) ?: 60
            val pause = binding.inputPause.text.toString().toIntOrNull()?.coerceIn(2, 100) ?: 80
            val mcc = binding.inputCurrent.text.toString().toIntOrNull()
                ?.coerceIn(AccSettings.HYSTERESIS_MIN_MA, AccSettings.HYSTERESIS_MAX_MA) ?: 1000
            val smart = binding.switchSmart.isChecked
            val target = binding.inputSmartTarget.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 80
            val lead = binding.inputSmartLead.text.toString().toIntOrNull()?.coerceIn(5, 24 * 60) ?: 60
            val tick = binding.inputSmartTick.text.toString().toIntOrNull()
                ?.coerceIn(SmartChargeEngine.MIN_TICK_MINUTES, SmartChargeEngine.MAX_TICK_MINUTES)
                ?: SmartChargeEngine.DEFAULT_TICK_MINUTES
            val time = binding.inputSmartTime.text.toString().trim()
            val parts = time.split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
            val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
            val control = binding.switchControl.isChecked
            val hideIcon = binding.switchHideIcon.isChecked
            if (resume >= pause) {
                Toast.makeText(this@MainActivity, R.string.resume_lt_pause, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val app = AccWidgetApp.get(this@MainActivity)
            withContext(Dispatchers.IO) {
                app.ensureRootProbed()
            }
            val rootOk = app.settings.get().rootGranted == true
            if (control && !rootOk) {
                Toast.makeText(this@MainActivity, R.string.root_required, Toast.LENGTH_LONG).show()
            }

            val deadline = if (smart) {
                SmartChargeEngine.nextDeadlineEpoch(hour, minute)
            } else {
                0L
            }

            withContext(Dispatchers.IO) {
                app.settings.update {
                    it.copy(
                        controlEnabled = control && rootOk,
                        resumeCapacity = resume,
                        pauseCapacity = pause.coerceAtLeast(resume + 1),
                        maxCurrentMa = mcc,
                        smartEnabled = smart && rootOk,
                        smartTargetPct = target,
                        smartDeadlineHour = hour,
                        smartDeadlineMinute = minute,
                        smartLeadMinutes = lead,
                        smartTickMinutes = tick,
                        smartDeadlineEpochMs = if (smart && rootOk) deadline else 0L,
                        hideLauncherIcon = hideIcon,
                        lastStatusText = SmartChargeEngine.profileSummary(
                            control && rootOk,
                            resume,
                            pause.coerceAtLeast(resume + 1),
                            smart && rootOk,
                            target,
                            hour,
                            minute,
                        ),
                    )
                }
                applyLauncherVisibility(hideIcon)
                if (rootOk) {
                    SmartChargeScheduler.reinitFromSettings(this@MainActivity)
                } else {
                    SmartChargeScheduler.cancelAll(this@MainActivity)
                }
                WidgetTicker.ensureRunning(this@MainActivity)
            }
            refreshProfileSummary()
            WidgetUpdater.requestUpdate(this@MainActivity)
            Toast.makeText(this@MainActivity, R.string.applied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyLauncherVisibility(hide: Boolean) {
        val pm = packageManager
        val alias = ComponentName(packageName, "$packageName.LauncherAlias")
        val state = if (hide) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun maybeRequestExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            if (!am.canScheduleExactAlarms()) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    },
                )
            }
        }
    }

    private fun maybeIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        }
    }
}
