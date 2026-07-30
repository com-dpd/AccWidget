package com.dp.accwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.dp.accwidget.AccWidgetApp
import com.dp.accwidget.smart.SmartChargeEngine
import com.dp.accwidget.smart.SmartChargeScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Legacy provider kept so existing widgets keep working after upgrade. */
class AccAppWidgetProvider : BaseAccWidgetProvider()

class AccWidgetCompactProvider : BaseAccWidgetProvider()

class AccWidgetStandardProvider : BaseAccWidgetProvider() {
    companion object {
        const val ACTION_STOP = "com.dp.accwidget.ACTION_WIDGET_STOP"
        const val ACTION_START = "com.dp.accwidget.ACTION_WIDGET_START"
        const val ACTION_SMART_OFF = "com.dp.accwidget.ACTION_WIDGET_SMART_OFF"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP, ACTION_START, ACTION_SMART_OFF -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        handleControlAction(context, intent.action!!)
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    private suspend fun handleControlAction(context: Context, action: String) {
        val app = AccWidgetApp.get(context)
        if (!app.acc.isRootAvailable()) {
            app.settings.update {
                it.copy(lastStatusText = "Root denied")
            }
            WidgetUpdater.updateAll(context)
            return
        }
        when (action) {
            ACTION_STOP -> {
                app.settings.update {
                    it.copy(
                        controlEnabled = false,
                        lastStatusText = SmartChargeEngine.profileSummary(it.copy(controlEnabled = false)),
                    )
                }
                app.acc.disableControl()
                SmartChargeScheduler.cancelAll(context)
            }
            ACTION_START -> {
                app.settings.update {
                    it.copy(
                        controlEnabled = true,
                        lastStatusText = SmartChargeEngine.profileSummary(it.copy(controlEnabled = true)),
                    )
                }
                SmartChargeScheduler.reinitFromSettings(context)
            }
            ACTION_SMART_OFF -> {
                app.settings.update {
                    it.copy(
                        smartEnabled = false,
                        smartDeadlineEpochMs = 0L,
                        lastStatusText = SmartChargeEngine.profileSummary(it.copy(smartEnabled = false)),
                    )
                }
                SmartChargeScheduler.cancelAll(context)
                val s = app.settings.get()
                if (s.controlEnabled) {
                    app.acc.applyHysteresis(s.resumeCapacity, s.pauseCapacity, s.maxCurrentMa)
                }
            }
        }
        WidgetUpdater.updateAll(context)
        WidgetTicker.ensureRunning(context)
    }
}

open class BaseAccWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.requestUpdate(context)
        WidgetTicker.ensureRunning(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            WidgetUpdater.updateOne(context.applicationContext, appWidgetId, newOptions)
        }
    }

    override fun onEnabled(context: Context) {
        // Display only — do not reinit ACC on widget add.
        WidgetTicker.ensureRunning(context)
        WidgetUpdater.requestUpdate(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        if (!WidgetUpdater.hasAnyWidget(context)) {
            WidgetTicker.cancel(context)
        }
    }

    override fun onDisabled(context: Context) {
        if (!WidgetUpdater.hasAnyWidget(context)) {
            WidgetTicker.cancel(context)
        }
    }
}
