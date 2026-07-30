package com.dp.accwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.dp.accwidget.AccWidgetApp
import com.dp.accwidget.R
import com.dp.accwidget.data.AccSettings
import com.dp.accwidget.data.BatterySnapshot
import com.dp.accwidget.smart.SmartChargeEngine
import com.dp.accwidget.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WidgetUpdater {
    private const val H1_MAX_DP = 70
    private const val H3_MIN_DP = 140
    private const val WIDE_MIN_DP = 180

    private enum class HeightTier { H1, H2, H3 }

    fun requestUpdate(context: Context) {
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            updateAll(appCtx)
        }
    }

    suspend fun updateAll(context: Context) {
        val app = AccWidgetApp.getOrNull() ?: AccWidgetApp.get(context)
        val mgr = AppWidgetManager.getInstance(context)
        val settings = app.settings.get()
        val batt = try {
            app.acc.readBattery()
        } catch (_: Throwable) {
            null
        }

        val compactIds = mgr.getAppWidgetIds(ComponentName(context, AccWidgetCompactProvider::class.java))
        val standardIds = mgr.getAppWidgetIds(ComponentName(context, AccWidgetStandardProvider::class.java))
        val legacyIds = mgr.getAppWidgetIds(ComponentName(context, AccAppWidgetProvider::class.java))

        withContext(Dispatchers.Main) {
            for (id in compactIds) {
                mgr.updateAppWidget(id, buildCompact(context, settings, batt))
            }
            for (id in standardIds + legacyIds) {
                val opts = mgr.getAppWidgetOptions(id)
                mgr.updateAppWidget(id, buildStandard(context, settings, batt, opts))
            }
        }
    }

    suspend fun updateOne(context: Context, appWidgetId: Int, options: Bundle?) {
        val app = AccWidgetApp.getOrNull() ?: AccWidgetApp.get(context)
        val mgr = AppWidgetManager.getInstance(context)
        val settings = app.settings.get()
        val batt = try {
            app.acc.readBattery()
        } catch (_: Throwable) {
            null
        }
        val provider = mgr.getAppWidgetInfo(appWidgetId)?.provider?.className.orEmpty()
        val views = when {
            provider.endsWith("AccWidgetCompactProvider") -> buildCompact(context, settings, batt)
            else -> buildStandard(context, settings, batt, options ?: mgr.getAppWidgetOptions(appWidgetId))
        }
        withContext(Dispatchers.Main) {
            mgr.updateAppWidget(appWidgetId, views)
        }
    }

    fun buildCompact(
        context: Context,
        settings: AccSettings,
        batt: BatterySnapshot?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_acc_compact)
        val cap = batt?.capacity ?: settings.lastCapacity
        views.setTextViewText(
            R.id.widget_battery,
            if (cap >= 0) "$cap%" else "—",
        )
        val signed = batt?.currentMaSigned
        views.setTextViewText(R.id.widget_current, formatCurrent(signed))
        views.setTextColor(R.id.widget_current, currentColor(signed))
        views.setTextViewText(R.id.widget_status, SmartChargeEngine.profileSummary(settings))
        views.setOnClickPendingIntent(R.id.widget_root, openSettingsPi(context))
        return views
    }

    fun buildStandard(
        context: Context,
        settings: AccSettings,
        batt: BatterySnapshot?,
        options: Bundle?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_acc_standard)
        val minH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val tier = heightTier(minH)
        val wide = minW == 0 || minW >= WIDE_MIN_DP

        val cap = batt?.capacity ?: settings.lastCapacity
        val battStatus = batt?.status
        val profile = SmartChargeEngine.profileSummary(settings)
        val signed = batt?.currentMaSigned
        val current = formatCurrent(signed)
        val limits = if (settings.smartEnabled) {
            context.getString(
                R.string.widget_smart_line,
                settings.smartTargetPct,
                "%02d:%02d".format(settings.smartDeadlineHour, settings.smartDeadlineMinute),
            )
        } else {
            context.getString(
                R.string.widget_hysteresis_line,
                settings.resumeCapacity,
                settings.pauseCapacity,
                settings.maxCurrentMa,
            )
        }

        views.setTextViewText(R.id.widget_battery, if (cap >= 0) "$cap%" else "—")
        views.setTextViewText(R.id.widget_current, current)
        views.setTextColor(R.id.widget_current, currentColor(signed))
        views.setViewVisibility(R.id.widget_current, View.VISIBLE)

        when (tier) {
            HeightTier.H1 -> {
                views.setViewVisibility(R.id.widget_title, View.GONE)
                views.setViewVisibility(R.id.widget_batt_status, View.GONE)
                views.setViewVisibility(R.id.widget_limits, View.GONE)
                views.setViewVisibility(R.id.widget_status, View.VISIBLE)
                views.setTextViewText(R.id.widget_status, profile)
            }
            HeightTier.H2 -> {
                views.setViewVisibility(R.id.widget_title, View.GONE)
                views.setViewVisibility(R.id.widget_limits, View.VISIBLE)
                views.setViewVisibility(R.id.widget_status, View.VISIBLE)
                views.setTextViewText(R.id.widget_limits, limits)
                views.setTextViewText(R.id.widget_status, profile)
                if (wide && !battStatus.isNullOrBlank()) {
                    views.setViewVisibility(R.id.widget_batt_status, View.VISIBLE)
                    views.setTextViewText(R.id.widget_batt_status, battStatus)
                } else {
                    views.setViewVisibility(R.id.widget_batt_status, View.GONE)
                }
            }
            HeightTier.H3 -> {
                views.setViewVisibility(R.id.widget_title, View.VISIBLE)
                views.setViewVisibility(R.id.widget_limits, View.VISIBLE)
                views.setViewVisibility(R.id.widget_status, View.VISIBLE)
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_name))
                views.setTextViewText(R.id.widget_limits, limits)
                views.setTextViewText(R.id.widget_status, profile)
                if (!battStatus.isNullOrBlank()) {
                    views.setViewVisibility(R.id.widget_batt_status, View.VISIBLE)
                    views.setTextViewText(R.id.widget_batt_status, battStatus)
                } else {
                    views.setViewVisibility(R.id.widget_batt_status, View.GONE)
                }
            }
        }
        views.setViewPadding(R.id.widget_root, dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))

        val (glyphW, glyphH) = glyphSizePx(context, options, tier, wide)
        val glyph = BatteryGlyph.draw(
            capacity = cap.coerceIn(0, 100),
            resumePct = settings.resumeCapacity,
            pausePct = settings.pauseCapacity,
            widthPx = glyphW,
            heightPx = glyphH,
        )
        views.setImageViewBitmap(R.id.widget_battery_icon, glyph)

        applyButtonState(views, R.id.btn_widget_stop, filled = !settings.controlEnabled)
        applyButtonState(views, R.id.btn_widget_start, filled = settings.controlEnabled)
        applyButtonState(views, R.id.btn_widget_smart_off, filled = settings.smartEnabled)

        val btnH = if (tier == HeightTier.H1) 28 else 32
        views.setInt(R.id.btn_widget_stop, "setHeight", dp(context, btnH))
        views.setInt(R.id.btn_widget_start, "setHeight", dp(context, btnH))
        views.setInt(R.id.btn_widget_smart_off, "setHeight", dp(context, btnH))

        val open = openSettingsPi(context)
        views.setOnClickPendingIntent(R.id.widget_soc_row, open)
        views.setOnClickPendingIntent(R.id.widget_current, open)
        views.setOnClickPendingIntent(R.id.widget_limits, open)
        views.setOnClickPendingIntent(R.id.widget_status, open)
        views.setOnClickPendingIntent(R.id.widget_title, open)
        views.setOnClickPendingIntent(
            R.id.btn_widget_stop,
            actionPi(context, AccWidgetStandardProvider.ACTION_STOP, 11),
        )
        views.setOnClickPendingIntent(
            R.id.btn_widget_start,
            actionPi(context, AccWidgetStandardProvider.ACTION_START, 12),
        )
        views.setOnClickPendingIntent(
            R.id.btn_widget_smart_off,
            actionPi(context, AccWidgetStandardProvider.ACTION_SMART_OFF, 13),
        )
        return views
    }

    private fun currentColor(signedMa: Int?): Int = when {
        signedMa == null || signedMa == 0 -> 0xFFB8C4D4.toInt()
        signedMa > 0 -> 0xFF34C759.toInt()
        else -> 0xFFFF3B30.toInt()
    }

    private fun heightTier(minH: Int): HeightTier = when {
        minH in 1 until H1_MAX_DP -> HeightTier.H1
        minH >= H3_MIN_DP -> HeightTier.H3
        else -> HeightTier.H2
    }

    private fun applyButtonState(views: RemoteViews, id: Int, filled: Boolean) {
        views.setInt(
            id,
            "setBackgroundResource",
            if (filled) R.drawable.widget_btn_filled else R.drawable.widget_btn_outline,
        )
        views.setTextColor(
            id,
            if (filled) 0xFF002114.toInt() else 0xFFE8EEF5.toInt(),
        )
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
    }

    /** Glyph sized for the SoC row height, not the full widget. */
    private fun glyphSizePx(
        context: Context,
        options: Bundle?,
        tier: HeightTier,
        wide: Boolean,
    ): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val hDp = when (tier) {
            HeightTier.H1 -> 36f
            HeightTier.H2 -> 40f
            HeightTier.H3 -> 42f
        }
        val share = if (wide) 0.42f else 0.48f
        val wDp = when {
            minW > 0 -> (minW * share).coerceIn(64f, if (wide) 140f else 100f)
            else -> if (wide) 100f else 80f
        }
        return Pair(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, wDp, context.resources.displayMetrics).toInt()
                .coerceAtLeast((64 * density).toInt()),
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, hDp, context.resources.displayMetrics).toInt()
                .coerceAtLeast((28 * density).toInt()),
        )
    }

    private fun openSettingsPi(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionPi(context: Context, action: String, req: Int): PendingIntent {
        val i = Intent(context, AccWidgetStandardProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            req,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun formatCurrent(signedMa: Int?): String {
        if (signedMa == null) return "— mA"
        val sign = if (signedMa > 0) "+" else if (signedMa < 0) "−" else ""
        return "$sign${kotlin.math.abs(signedMa)} mA"
    }

    fun hasAnyWidget(context: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(context)
        return mgr.getAppWidgetIds(ComponentName(context, AccWidgetCompactProvider::class.java)).isNotEmpty() ||
            mgr.getAppWidgetIds(ComponentName(context, AccWidgetStandardProvider::class.java)).isNotEmpty() ||
            mgr.getAppWidgetIds(ComponentName(context, AccAppWidgetProvider::class.java)).isNotEmpty()
    }
}
