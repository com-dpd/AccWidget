package com.dp.accwidget.acc

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.dp.accwidget.data.BatterySnapshot
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Root wrapper around ACC CLI for control.
 * Battery reads prefer BatteryManager / world-readable sysfs (no su).
 */
class AccController(context: Context) {
    private val appContext = context.applicationContext

    @Volatile private var cachedAcc: String? = null
    @Volatile private var cachedAccd: String? = null
    @Volatile private var rootAvailable: Boolean? = null

    data class CmdResult(val code: Int, val out: String, val err: String) {
        val ok: Boolean
            get() = code == 0 ||
                out.contains("OK") ||
                out.contains("DISABLED") ||
                out.contains("STARTED") ||
                out.contains("SET:") ||
                out.contains("PAUSED") ||
                out.contains("✅") ||
                out.contains("Max charging current")
    }

    /** One-shot root probe; subsequent calls reuse the cached result. */
    fun probeRoot(force: Boolean = false): Boolean {
        if (!force && rootAvailable != null) return rootAvailable!!
        val r = runSu("id", timeoutSec = 8)
        val granted = r.code == 0 && (r.out.contains("uid=0") || r.out.contains("root"))
        rootAvailable = granted
        if (!granted) {
            cachedAcc = null
            cachedAccd = null
        }
        return granted
    }

    fun isRootAvailable(): Boolean = rootAvailable ?: false

    fun setRootCached(granted: Boolean) {
        rootAvailable = granted
    }

    fun runSu(command: String, timeoutSec: Long = 30): CmdResult {
        return try {
            val pb = ProcessBuilder("su", "-c", command)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().use(BufferedReader::readText)
            val err = p.errorStream.bufferedReader().use(BufferedReader::readText)
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                CmdResult(-1, out, err + "\ntimeout")
            } else {
                CmdResult(p.exitValue(), out.trim(), err.trim())
            }
        } catch (t: Throwable) {
            CmdResult(-2, "", t.message ?: "su failed")
        }
    }

    fun resolveAcc(): String {
        cachedAcc?.let { return it }
        if (rootAvailable == false) return "acc"
        val r = runSu(
            "if [ -x /dev/acc ]; then echo /dev/acc; " +
                "elif [ -x /data/adb/vr25/acc/acc ]; then echo /data/adb/vr25/acc/acc; " +
                "elif command -v acc >/dev/null 2>&1; then command -v acc; " +
                "else echo acc; fi",
        )
        val path = r.out.lines().firstOrNull()?.trim().orEmpty().ifBlank { "acc" }
        if (r.code == 0) cachedAcc = path
        return path
    }

    fun resolveAccd(): String {
        cachedAccd?.let { return it }
        if (rootAvailable == false) return "accd"
        val r = runSu(
            "if [ -x /dev/accd ]; then echo /dev/accd; " +
                "elif [ -x /data/adb/vr25/acc/accd ]; then echo /data/adb/vr25/acc/accd; " +
                "else echo accd; fi",
        )
        val path = r.out.lines().firstOrNull()?.trim().orEmpty().ifBlank { "accd" }
        if (r.code == 0) cachedAccd = path
        return path
    }

    fun applyHysteresis(resume: Int, pause: Int, mccMa: Int): CmdResult {
        if (rootAvailable == false) return CmdResult(-3, "", "root denied")
        val capacity = readBattery().capacity
        val acc = resolveAcc()
        val accd = resolveAccd()
        // Use -d when already at/above pause so we do not beep ON then OFF.
        val chargeCmd = if (capacity >= 0 && capacity >= pause) {
            "$acc -d >/dev/null 2>&1 || true"
        } else {
            "$acc -e >/dev/null 2>&1 || true"
        }
        val script = listOf(
            """$acc -s "charging_switch=battery/input_suspend 0 1 --" >/dev/null 2>&1 || true""",
            "$acc -s pc=$pause rc=$resume",
            "$acc -s mcc=$mccMa",
            chargeCmd,
            "$accd >/dev/null 2>&1 || true",
            "echo OK",
        ).joinToString("; ")
        return runSu(script, 50)
    }

    fun setCurrentMa(mccMa: Int): CmdResult {
        if (rootAvailable == false) return CmdResult(-3, "", "root denied")
        val acc = resolveAcc()
        return runSu("$acc -s mcc=$mccMa; echo SET:$mccMa")
    }

    fun enableCharging(): CmdResult {
        if (rootAvailable == false) return CmdResult(-3, "", "root denied")
        val acc = resolveAcc()
        return runSu("$acc -e; echo ON")
    }

    fun disableCharging(): CmdResult {
        if (rootAvailable == false) return CmdResult(-3, "", "root denied")
        val acc = resolveAcc()
        return runSu("$acc -d; echo OFF")
    }

    fun disableControl(): CmdResult {
        if (rootAvailable == false) return CmdResult(-3, "", "root denied")
        val acc = resolveAcc()
        val script = listOf(
            "$acc -D >/dev/null 2>&1 || true",
            "/dev/accd. >/dev/null 2>&1 || true",
            "$acc -s mcc=- >/dev/null 2>&1 || true",
            "$acc -e >/dev/null 2>&1 || true",
            "echo DISABLED",
        ).joinToString("; ")
        return runSu(script, 40)
    }

    fun startDaemon(): CmdResult {
        if (rootAvailable == false) return CmdResult(-3, "", "root denied")
        val accd = resolveAccd()
        return runSu("$accd >/dev/null 2>&1 || true; echo STARTED")
    }

    /** Prefer non-root sources; never call su for routine widget polls. */
    fun readBattery(): BatterySnapshot {
        readViaBatteryManager()?.let { return it }
        readViaSysfs()?.let { return it }
        return BatterySnapshot(-1, "Unknown", 0, false, 0)
    }

    private fun readViaBatteryManager(): BatterySnapshot? {
        return try {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val sticky = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null

            val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (capacity == Int.MIN_VALUE || capacity < 0) return null

            val rawCurProp = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val curUa = if (rawCurProp == Long.MIN_VALUE) 0L else rawCurProp

            val statusCode = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val status = statusName(statusCode)
            val pluggedType = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val plugged = pluggedType != 0 ||
                statusCode == BatteryManager.BATTERY_STATUS_CHARGING ||
                statusCode == BatteryManager.BATTERY_STATUS_FULL

            val suspend = readSysfsInt("/sys/class/power_supply/battery/input_suspend") ?: 0

            buildSnapshot(
                capacity = capacity.coerceIn(0, 100),
                status = status,
                rawCurUa = curUa,
                plugged = plugged,
                inputSuspend = suspend,
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun readViaSysfs(): BatterySnapshot? {
        val capacity = readSysfsInt("/sys/class/power_supply/battery/capacity") ?: return null
        val rawCurUa = readSysfsLong("/sys/class/power_supply/battery/current_now") ?: 0L
        val status = readSysfsText("/sys/class/power_supply/battery/status") ?: "Unknown"
        val usbOnline = (readSysfsInt("/sys/class/power_supply/usb/online") ?: 0) == 1
        val plugged = usbOnline ||
            status.contains("Charging", true) ||
            status.contains("Full", true) ||
            status.equals("Not charging", true)
        val suspend = readSysfsInt("/sys/class/power_supply/battery/input_suspend") ?: 0
        return buildSnapshot(capacity, status, rawCurUa, plugged, suspend)
    }

    private fun buildSnapshot(
        capacity: Int,
        status: String,
        rawCurUa: Long,
        plugged: Boolean,
        inputSuspend: Int,
    ): BatterySnapshot {
        val absMa = (abs(rawCurUa) / 1000L).toInt()
        // Exact status names — do NOT use contains("charging"): "Discharging" matches it.
        val isChargingStatus = status.equals("Charging", ignoreCase = true)
        val isFull = status.equals("Full", ignoreCase = true)
        val isDischarging = status.equals("Discharging", ignoreCase = true)

        // Display convention: + = into battery, - = out of battery.
        // Qualcomm current_now is typically negative while charging.
        val intoBattery = when {
            isChargingStatus -> true
            isDischarging -> false
            isFull && plugged && inputSuspend == 0 -> true
            inputSuspend == 1 || !plugged -> false
            // Not charging / unknown while plugged: trust kernel polarity
            else -> rawCurUa < 0
        }
        val signedMa = when {
            intoBattery && absMa < 15 && isFull -> 0
            intoBattery -> absMa
            else -> -absMa
        }
        return BatterySnapshot(
            capacity = capacity,
            status = status,
            currentMaSigned = signedMa,
            plugged = plugged,
            inputSuspend = inputSuspend,
        )
    }

    private fun statusName(code: Int): String = when (code) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        else -> "Unknown"
    }

    private fun readSysfsText(path: String): String? = try {
        val f = File(path)
        if (!f.canRead()) null else f.readText().trim().ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    private fun readSysfsInt(path: String): Int? = readSysfsText(path)?.toIntOrNull()

    private fun readSysfsLong(path: String): Long? = readSysfsText(path)?.toLongOrNull()
}
