package com.dp.accwidget.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("acc_widget_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val CONTROL = booleanPreferencesKey("control_enabled")
        val RESUME = intPreferencesKey("resume_capacity")
        val PAUSE = intPreferencesKey("pause_capacity")
        val MCC = intPreferencesKey("max_current_ma")
        val SMART = booleanPreferencesKey("smart_enabled")
        val SMART_TARGET = intPreferencesKey("smart_target_pct")
        val SMART_HOUR = intPreferencesKey("smart_deadline_hour")
        val SMART_MIN = intPreferencesKey("smart_deadline_minute")
        val SMART_LEAD = intPreferencesKey("smart_lead_minutes")
        val SMART_TICK = intPreferencesKey("smart_tick_minutes")
        val SMART_EPOCH = longPreferencesKey("smart_deadline_epoch")
        val STATUS = stringPreferencesKey("last_status")
        val LAST_MCC = intPreferencesKey("last_applied_mcc")
        val LAST_CAP = intPreferencesKey("last_capacity")
        val SAMPLE_EPOCH = longPreferencesKey("last_sample_epoch")
        val SAMPLE_CAP = intPreferencesKey("last_sample_capacity")
        val RATE = floatPreferencesKey("rate_pct_per_hour")
        val HIDE_ICON = booleanPreferencesKey("hide_launcher_icon")
        val ROOT_CHECKED = booleanPreferencesKey("root_checked")
        val ROOT_GRANTED = booleanPreferencesKey("root_granted")
    }

    val settingsFlow: Flow<AccSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun get(): AccSettings = context.dataStore.data.first().toSettings()

    suspend fun update(transform: (AccSettings) -> AccSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.CONTROL] = next.controlEnabled
            prefs[Keys.RESUME] = next.resumeCapacity
            prefs[Keys.PAUSE] = next.pauseCapacity
            prefs[Keys.MCC] = next.maxCurrentMa
            prefs[Keys.SMART] = next.smartEnabled
            prefs[Keys.SMART_TARGET] = next.smartTargetPct
            prefs[Keys.SMART_HOUR] = next.smartDeadlineHour
            prefs[Keys.SMART_MIN] = next.smartDeadlineMinute
            prefs[Keys.SMART_LEAD] = next.smartLeadMinutes
            prefs[Keys.SMART_TICK] = next.smartTickMinutes
            prefs[Keys.SMART_EPOCH] = next.smartDeadlineEpochMs
            prefs[Keys.STATUS] = next.lastStatusText
            prefs[Keys.LAST_MCC] = next.lastAppliedMcc
            prefs[Keys.LAST_CAP] = next.lastCapacity
            prefs[Keys.SAMPLE_EPOCH] = next.lastSampleEpochMs
            prefs[Keys.SAMPLE_CAP] = next.lastSampleCapacity
            prefs[Keys.RATE] = next.ratePctPerHour
            prefs[Keys.HIDE_ICON] = next.hideLauncherIcon
            if (next.rootGranted == null) {
                prefs.remove(Keys.ROOT_CHECKED)
                prefs.remove(Keys.ROOT_GRANTED)
            } else {
                prefs[Keys.ROOT_CHECKED] = true
                prefs[Keys.ROOT_GRANTED] = next.rootGranted
            }
        }
    }

    private fun Preferences.toSettings(): AccSettings {
        val rootChecked = this[Keys.ROOT_CHECKED] == true
        val rootGranted = if (rootChecked) this[Keys.ROOT_GRANTED] else null
        return AccSettings(
            controlEnabled = this[Keys.CONTROL] ?: false,
            resumeCapacity = this[Keys.RESUME] ?: 60,
            pauseCapacity = this[Keys.PAUSE] ?: 80,
            maxCurrentMa = this[Keys.MCC] ?: 1000,
            smartEnabled = this[Keys.SMART] ?: false,
            smartTargetPct = this[Keys.SMART_TARGET] ?: 80,
            smartDeadlineHour = this[Keys.SMART_HOUR] ?: 7,
            smartDeadlineMinute = this[Keys.SMART_MIN] ?: 0,
            smartLeadMinutes = this[Keys.SMART_LEAD] ?: 60,
            smartTickMinutes = this[Keys.SMART_TICK] ?: 10,
            smartDeadlineEpochMs = this[Keys.SMART_EPOCH] ?: 0L,
            lastStatusText = this[Keys.STATUS] ?: "—",
            lastAppliedMcc = this[Keys.LAST_MCC] ?: 0,
            lastCapacity = this[Keys.LAST_CAP] ?: -1,
            lastSampleEpochMs = this[Keys.SAMPLE_EPOCH] ?: 0L,
            lastSampleCapacity = this[Keys.SAMPLE_CAP] ?: -1,
            ratePctPerHour = this[Keys.RATE] ?: 0f,
            hideLauncherIcon = this[Keys.HIDE_ICON] ?: false,
            rootGranted = rootGranted,
        )
    }
}
