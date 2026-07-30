package com.dp.accwidget

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import com.dp.accwidget.acc.AccController
import com.dp.accwidget.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AccWidgetApp : Application(), Configuration.Provider {
    lateinit var settings: SettingsRepository
        private set
    lateinit var acc: AccController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)
        acc = AccController(this)
        CoroutineScope(Dispatchers.IO).launch {
            ensureRootProbed()
        }
        com.dp.accwidget.widget.WidgetTicker.ensureRunning(this)
    }

    /** Probe Magisk/root once; persist result. */
    suspend fun ensureRootProbed() {
        val s = settings.get()
        if (s.rootGranted != null) {
            acc.setRootCached(s.rootGranted)
            return
        }
        val granted = acc.probeRoot(force = true)
        settings.update { it.copy(rootGranted = granted) }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    companion object {
        @Volatile
        private var instance: AccWidgetApp? = null

        fun get(context: Context): AccWidgetApp {
            instance?.let { return it }
            val app = context.applicationContext as? AccWidgetApp
                ?: error("AccWidgetApp not initialized")
            instance = app
            return app
        }

        fun getOrNull(): AccWidgetApp? = instance
    }
}
