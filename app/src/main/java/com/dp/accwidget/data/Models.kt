package com.dp.accwidget.data

data class AccSettings(
    val controlEnabled: Boolean = false,
    val resumeCapacity: Int = 60,
    val pauseCapacity: Int = 80,
    val maxCurrentMa: Int = 1000,
    val smartEnabled: Boolean = false,
    val smartTargetPct: Int = 80,
    val smartDeadlineHour: Int = 7,
    val smartDeadlineMinute: Int = 0,
    /** Minutes before deadline when smart window opens. */
    val smartLeadMinutes: Int = 60,
    /** Epoch millis of next deadline (computed). */
    val smartDeadlineEpochMs: Long = 0L,
    val lastStatusText: String = "—",
    val lastAppliedMcc: Int = 0,
    val lastCapacity: Int = -1,
    val lastSampleEpochMs: Long = 0L,
    val lastSampleCapacity: Int = -1,
    val ratePctPerHour: Float = 0f,
    val hideLauncherIcon: Boolean = false,
    /** null = not probed yet; true/false after one-shot root check. */
    val rootGranted: Boolean? = null,
) {
    companion object {
        const val HYSTERESIS_MAX_MA = 3000
        const val HYSTERESIS_MIN_MA = 300
    }
}

data class BatterySnapshot(
    val capacity: Int,
    val status: String,
    /** Signed mA: positive = charging into battery, negative = discharging. */
    val currentMaSigned: Int,
    val plugged: Boolean,
    val inputSuspend: Int,
) {
    val currentMaAbs: Int get() = kotlin.math.abs(currentMaSigned)
}
