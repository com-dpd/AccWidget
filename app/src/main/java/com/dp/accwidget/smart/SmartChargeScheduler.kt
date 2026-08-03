package com.dp.accwidget.smart

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dp.accwidget.AccWidgetApp
import com.dp.accwidget.acc.AccController
import com.dp.accwidget.data.AccSettings
import com.dp.accwidget.widget.WidgetTicker
import com.dp.accwidget.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmartChargeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmartChargeScheduler.runTick(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmartChargeScheduler.reschedule(context.applicationContext)
                WidgetUpdater.requestUpdate(context)
                WidgetTicker.ensureRunning(context)
            } finally {
                pending.finish()
            }
        }
    }
}

object SmartChargeScheduler {
    const val ACTION_SMART_TICK = "com.dp.accwidget.ACTION_SMART_TICK"
    const val ACTION_SMART_WINDOW = "com.dp.accwidget.ACTION_SMART_WINDOW"

    private fun tickMs(settings: AccSettings): Long =
        settings.smartTickMinutes.coerceIn(1, 30).toLong() * 60_000L

    fun reschedule(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val app = AccWidgetApp.get(context)
            val s = app.settings.get()
            cancelAll(context)
            if (!s.controlEnabled || !s.smartEnabled) return@launch

            var deadline = s.smartDeadlineEpochMs
            if (deadline < System.currentTimeMillis()) {
                deadline = SmartChargeEngine.nextDeadlineEpoch(s.smartDeadlineHour, s.smartDeadlineMinute)
                app.settings.update { it.copy(smartDeadlineEpochMs = deadline) }
            }
            val window = SmartChargeEngine.windowStart(deadline, s.smartLeadMinutes)
            val now = System.currentTimeMillis()
            when {
                now < window -> scheduleExact(context, ACTION_SMART_WINDOW, window)
                now < deadline -> scheduleExact(
                    context,
                    ACTION_SMART_TICK,
                    now + tickMs(s).coerceAtLeast(60_000L),
                )
                else -> {
                    // deadline == now: treat as finished, arm tomorrow
                    val next = SmartChargeEngine.nextDeadlineEpoch(
                        s.smartDeadlineHour, s.smartDeadlineMinute, now,
                    )
                    app.settings.update { it.copy(smartDeadlineEpochMs = next) }
                    scheduleExact(
                        context,
                        ACTION_SMART_WINDOW,
                        SmartChargeEngine.windowStart(next, s.smartLeadMinutes),
                    )
                }
            }
        }
    }

    fun scheduleExact(context: Context, action: String, atEpochMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pi)
        }
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context, ACTION_SMART_TICK))
        am.cancel(pending(context, ACTION_SMART_WINDOW))
    }

    private fun pending(context: Context, action: String): PendingIntent {
        val i = Intent(context, SmartChargeAlarmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Re-apply saved profile to ACC (Apply / Start). Not called on widget add/remove. */
    suspend fun reinitFromSettings(context: Context) {
        val app = AccWidgetApp.get(context)
        app.ensureRootProbed()
        val s = app.settings.get()
        cancelAll(context)
        if (s.rootGranted != true) {
            app.settings.update {
                it.copy(lastStatusText = "Root denied · ACC control unavailable")
            }
            WidgetUpdater.requestUpdate(context)
            return
        }
        if (!s.controlEnabled) {
            app.acc.disableControl()
            app.settings.update { it.copy(lastStatusText = SmartChargeEngine.profileSummary(it)) }
            WidgetUpdater.requestUpdate(context)
            return
        }
        app.acc.startDaemon()
        if (s.smartEnabled) {
            var deadline = s.smartDeadlineEpochMs
            if (deadline < System.currentTimeMillis()) {
                deadline = SmartChargeEngine.nextDeadlineEpoch(s.smartDeadlineHour, s.smartDeadlineMinute)
                app.settings.update { it.copy(smartDeadlineEpochMs = deadline) }
            }
        }
        reschedule(context)
        runTick(context)
    }

    suspend fun runTick(context: Context) {
        val app = AccWidgetApp.get(context)
        app.ensureRootProbed()
        val acc = app.acc
        val settings = app.settings.get()
        if (!settings.controlEnabled || settings.rootGranted != true) {
            WidgetUpdater.requestUpdate(context)
            return
        }

        val batt = acc.readBattery()
        val now = System.currentTimeMillis()
        var s = settings.copy(
            lastCapacity = batt.capacity,
            lastStatusText = batt.status,
        )

        if (!s.smartEnabled) {
            acc.applyHysteresis(s.resumeCapacity, s.pauseCapacity, s.maxCurrentMa)
            s = s.copy(
                lastStatusText = SmartChargeEngine.statusLine(
                    s, batt.capacity, false, s.maxCurrentMa, batt.plugged,
                ),
            )
            app.settings.update { s }
            WidgetUpdater.requestUpdate(context)
            return
        }

        var deadline = s.smartDeadlineEpochMs
        if (deadline <= 0L) {
            deadline = SmartChargeEngine.nextDeadlineEpoch(s.smartDeadlineHour, s.smartDeadlineMinute, now)
            s = s.copy(smartDeadlineEpochMs = deadline)
        }

        val lead = s.smartLeadMinutes.coerceIn(5, 24 * 60)
        val inWindow = SmartChargeEngine.isInSmartWindow(now, deadline, lead)
        val rate = SmartChargeEngine.updateRate(
            s.lastSampleEpochMs, s.lastSampleCapacity, now, batt.capacity, s.ratePctPerHour,
        )
        s = s.copy(
            ratePctPerHour = rate,
            lastSampleEpochMs = now,
            lastSampleCapacity = batt.capacity,
        )

        when {
            now >= deadline -> {
                // Deadline passed: one-shot end → hysteresis, keep smart switch on
                finishOneShotToHysteresis(
                    context = context,
                    acc = acc,
                    s = s,
                    now = now,
                    status = "Smart deadline passed · hysteresis ${s.resumeCapacity}-${s.pauseCapacity}%",
                )
            }
            inWindow -> {
                if (batt.capacity >= s.smartTargetPct) {
                    // Target reached → hysteresis (applyHysteresis uses -d when at/above pause)
                    finishOneShotToHysteresis(
                        context = context,
                        acc = acc,
                        s = s,
                        now = now,
                        status = "Smart done → ${s.smartTargetPct}% · hysteresis ${s.resumeCapacity}-${s.pauseCapacity}%",
                    )
                } else {
                    // Smart priority: ignore hysteresis pause/mcc caps
                    acc.enableCharging()
                    val resume = (s.smartTargetPct - 5).coerceAtLeast(1)
                    val baseMcc = s.lastAppliedMcc.takeIf { it > 0 } ?: SmartChargeEngine.BASE_MA
                    acc.applyHysteresis(resume, s.smartTargetPct, baseMcc)
                    val minutesLeft = SmartChargeEngine.minutesUntil(deadline, now)
                    val mcc = if (!batt.plugged) {
                        baseMcc
                    } else {
                        SmartChargeEngine.chooseCurrentMa(
                            capacity = batt.capacity,
                            targetPct = s.smartTargetPct,
                            minutesLeft = minutesLeft,
                            ratePctPerHourAtCurrent = rate,
                            currentMa = baseMcc,
                        )
                    }
                    if (batt.plugged) {
                        acc.setCurrentMa(mcc)
                    }
                    s = s.copy(
                        lastAppliedMcc = mcc,
                        lastStatusText = SmartChargeEngine.statusLine(
                            s, batt.capacity, true, mcc, batt.plugged,
                        ),
                    )
                    app.settings.update { s }
                    val nextTick = (now + tickMs(s)).coerceAtMost(deadline)
                    scheduleExact(context, ACTION_SMART_TICK, nextTick)
                }
            }
            else -> {
                // Before window: normal hysteresis (smart switch stays on)
                acc.applyHysteresis(s.resumeCapacity, s.pauseCapacity, s.maxCurrentMa)
                s = s.copy(
                    lastStatusText = SmartChargeEngine.statusLine(
                        s, batt.capacity, false, s.maxCurrentMa, batt.plugged,
                    ),
                )
                app.settings.update { s }
                scheduleExact(
                    context,
                    ACTION_SMART_WINDOW,
                    SmartChargeEngine.windowStart(deadline, lead),
                )
            }
        }
        WidgetUpdater.requestUpdate(context)
    }

    /**
     * End the current one-shot smart window: apply hysteresis, keep [AccSettings.smartEnabled],
     * and arm the next day's deadline/window.
     */
    private suspend fun finishOneShotToHysteresis(
        context: Context,
        acc: AccController,
        s: AccSettings,
        now: Long,
        status: String,
    ) {
        val app = AccWidgetApp.get(context)
        cancelAll(context)
        acc.applyHysteresis(s.resumeCapacity, s.pauseCapacity, s.maxCurrentMa)
        val next = SmartChargeEngine.nextDeadlineEpoch(
            s.smartDeadlineHour, s.smartDeadlineMinute, now,
        )
        app.settings.update {
            s.copy(
                smartEnabled = true,
                lastAppliedMcc = s.maxCurrentMa,
                lastStatusText = status,
                smartDeadlineEpochMs = next,
            )
        }
        scheduleExact(
            context,
            ACTION_SMART_WINDOW,
            SmartChargeEngine.windowStart(next, s.smartLeadMinutes),
        )
    }
}
