package dev.hapax.watch

import android.app.Application
import android.content.Intent
import android.util.Log
import dev.hapax.watch.sensor.SensorService

class HapaxWatchApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HapaxWatch starting — launching SensorService")
        val intent = Intent(this, SensorService::class.java)
        startForegroundService(intent)
    }

    companion object {
        const val TAG = "HapaxWatch"
    }
}
