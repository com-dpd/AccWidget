package com.dp.accwidget.smart

import com.dp.accwidget.data.AccSettings
import kotlin.math.ceil
import kotlin.math.max

object SmartChargeEngine {
    val CURRENT_GRID = intArrayOf(300, 500, 750, 1000, 1250, 1500, 1750, 2000)
    const val BASE_MA = 300
    const val MAX_MA = 2000
    const val DEFAULT_LEAD_MINUTES = 60
    const val TICK_MS = 10L * 60L * 1000L

    /** Baseline: assume ~8%/h at 300 mA until calibrated (conservative / slow). */
    private const val DEFAULT_RATE_AT_300 = 8f

    fun nextDeadlineEpoch(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun windowStart(deadlineEpoch: Long, leadMinutes: Int): Long {
        val leadMs = leadMinutes.coerceIn(5, 24 * 60).toLong() * 60_000L
        return deadlineEpoch - leadMs
    }

    fun isInSmartWindow(now: Long, deadlineEpoch: Long, leadMinutes: Int): Boolean {
        if (deadlineEpoch <= 0L) return false
        val start = windowStart(deadlineEpoch, leadMinutes)
        return now in start until deadlineEpoch
    }

    fun updateRate(
        prevEpoch: Long,
        prevCap: Int,
        nowEpoch: Long,
        nowCap: Int,
        prevRate: Float,
    ): Float {
        if (prevEpoch <= 0L || prevCap < 0 || nowCap < 0) return prevRate
        val dtH = (nowEpoch - prevEpoch).toFloat() / (1000f * 60f * 60f)
        if (dtH < 0.05f) return prevRate
        val dCap = (nowCap - prevCap).toFloat()
        if (dCap <= 0f) return prevRate
        val instant = dCap / dtH
        return if (prevRate <= 0f) instant else (prevRate * 0.4f + instant * 0.6f)
    }

    /**
     * Pick minimal grid current that can finish in time.
     * Smart mode may exceed hysteresis maxCurrentMa (up to MAX_MA).
     */
    fun chooseCurrentMa(
        capacity: Int,
        targetPct: Int,
        minutesLeft: Double,
        ratePctPerHourAtCurrent: Float,
        currentMa: Int,
    ): Int {
        val remaining = (targetPct - capacity).coerceAtLeast(0)
        if (remaining == 0) return BASE_MA
        if (minutesLeft <= 0) return MAX_MA

        val rateAt300 = when {
            ratePctPerHourAtCurrent > 0f && currentMa > 0 ->
                ratePctPerHourAtCurrent * (BASE_MA.toFloat() / currentMa.toFloat())
            else -> DEFAULT_RATE_AT_300
        }.coerceAtLeast(0.5f)

        fun etaMinutesAt(ma: Int): Double {
            val rate = rateAt300 * (ma.toFloat() / BASE_MA.toFloat())
            val perMin = rate / 60.0
            if (perMin <= 0) return Double.POSITIVE_INFINITY
            return remaining / perMin
        }

        if (etaMinutesAt(BASE_MA) <= minutesLeft) return BASE_MA

        for (ma in CURRENT_GRID) {
            if (etaMinutesAt(ma) <= minutesLeft) return ma
        }

        val needed = ceil(
            currentMa.coerceAtLeast(BASE_MA) *
                (etaMinutesAt(currentMa.coerceAtLeast(BASE_MA)) / minutesLeft)
        ).toInt()
        return roundUpToGrid(needed.coerceIn(BASE_MA, MAX_MA))
    }

    fun roundUpToGrid(ma: Int): Int {
        for (g in CURRENT_GRID) {
            if (g >= ma) return g
        }
        return MAX_MA
    }

    fun profileSummary(
        controlEnabled: Boolean,
        resume: Int,
        pause: Int,
        smartEnabled: Boolean,
        smartTarget: Int,
        smartHour: Int,
        smartMinute: Int,
    ): String {
        return buildString {
            append(if (controlEnabled) "ON" else "OFF")
            append(" · >=$resume% → OFF <=$pause%")
            if (smartEnabled) {
                append(" · Smart → $smartTarget% ")
                append("%02d:%02d".format(smartHour, smartMinute))
            }
        }
    }

    fun profileSummary(settings: AccSettings): String = profileSummary(
        controlEnabled = settings.controlEnabled,
        resume = settings.resumeCapacity,
        pause = settings.pauseCapacity,
        smartEnabled = settings.smartEnabled,
        smartTarget = settings.smartTargetPct,
        smartHour = settings.smartDeadlineHour,
        smartMinute = settings.smartDeadlineMinute,
    )

    fun statusLine(
        settings: AccSettings,
        capacity: Int,
        inWindow: Boolean,
        mcc: Int,
        plugged: Boolean,
    ): String {
        return buildString {
            append(if (settings.controlEnabled) "ON" else "OFF")
            append(" · ")
            append("$capacity%")
            if (settings.smartEnabled) {
                append(" · Smart→${settings.smartTargetPct}% ")
                append("%02d:%02d".format(settings.smartDeadlineHour, settings.smartDeadlineMinute))
                if (inWindow) append(" · ${mcc}mA")
                if (!plugged && inWindow) append(" · !plug")
            } else {
                append(" · ${settings.resumeCapacity}-${settings.pauseCapacity}%")
                append(" · ${settings.maxCurrentMa}mA")
            }
        }
    }

    fun minutesUntil(deadlineEpoch: Long, now: Long = System.currentTimeMillis()): Double {
        return max(0.0, (deadlineEpoch - now).toDouble() / 60000.0)
    }
}
