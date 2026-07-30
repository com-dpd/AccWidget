package com.dp.accwidget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Energy-efficient widget refresh:
 * - POWER_CONNECTED / DISCONNECTED (manifest)
 * - sticky BATTERY_CHANGED registered dynamically while widgets exist (throttled)
 * - inexact backup alarms (no setExact): ~45s while charging/plugged, ~4 min on battery
 */
object WidgetTicker {
    const val ACTION_TICK = "com.dp.accwidget.ACTION_WIDGET_TICK"
    const val INTERVAL_CHARGING_MS = 45_000L
    const val INTERVAL_IDLE_MS = 4L * 60_000L
    private const val THROTTLE_CHARGING_MS = 15_000L
    private const val THROTTLE_IDLE_MS = 60_000L

    private val batteryReceiverRegistered = AtomicBoolean(false)
    private val lastEventUpdateMs = AtomicLong(0L)

    @Volatile
    private var batteryReceiver: BroadcastReceiver? = null

    fun ensureRunning(context: Context) {
        val appCtx = context.applicationContext
        if (!WidgetUpdater.hasAnyWidget(appCtx)) {
            cancel(appCtx)
            return
        }
        registerBatteryReceiver(appCtx)
        scheduleNext(appCtx, nextIntervalMs(appCtx))
    }

    fun cancel(context: Context) {
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(appCtx))
        unregisterBatteryReceiver(appCtx)
    }

    fun onPowerOrBatteryEvent(context: Context, force: Boolean = false) {
        val appCtx = context.applicationContext
        if (!WidgetUpdater.hasAnyWidget(appCtx)) {
            cancel(appCtx)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val minGap = if (isPluggedOrCharging(appCtx)) THROTTLE_CHARGING_MS else THROTTLE_IDLE_MS
        val last = lastEventUpdateMs.get()
        if (!force && now - last < minGap) {
            scheduleNext(appCtx, nextIntervalMs(appCtx))
            return
        }
        lastEventUpdateMs.set(now)
        WidgetUpdater.requestUpdate(appCtx)
        scheduleNext(appCtx, nextIntervalMs(appCtx))
    }

    fun scheduleNext(context: Context, delayMs: Long = nextIntervalMs(context)) {
        val appCtx = context.applicationContext
        if (!WidgetUpdater.hasAnyWidget(appCtx)) {
            cancel(appCtx)
            return
        }
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val trigger = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(15_000L)
        val pi = pending(appCtx)
        // Inexact — do not use setExact* for widget polling.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
        }
    }

    fun nextIntervalMs(context: Context): Long {
        return if (isPluggedOrCharging(context)) INTERVAL_CHARGING_MS else INTERVAL_IDLE_MS
    }

    fun isPluggedOrCharging(context: Context): Boolean {
        return try {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return false
            val plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            plugged ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Throwable) {
            false
        }
    }

    private fun registerBatteryReceiver(context: Context) {
        if (!batteryReceiverRegistered.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                onPowerOrBatteryEvent(ctx, force = false)
            }
        }
        batteryReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun unregisterBatteryReceiver(context: Context) {
        if (!batteryReceiverRegistered.compareAndSet(true, false)) return
        try {
            batteryReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Throwable) {
        }
        batteryReceiver = null
    }

    private fun pending(context: Context): PendingIntent {
        val i = Intent(context, WidgetTickReceiver::class.java).setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(
            context,
            42,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != WidgetTicker.ACTION_TICK) return
        val pending = goAsync()
        Thread {
            try {
                val appCtx = context.applicationContext
                if (!WidgetUpdater.hasAnyWidget(appCtx)) {
                    WidgetTicker.cancel(appCtx)
                    return@Thread
                }
                kotlinx.coroutines.runBlocking {
                    WidgetUpdater.updateAll(appCtx)
                }
                WidgetTicker.scheduleNext(appCtx, WidgetTicker.nextIntervalMs(appCtx))
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Plug/unplug — always refresh widgets promptly. */
class WidgetPowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            -> WidgetTicker.onPowerOrBatteryEvent(context, force = true)
        }
    }
}
