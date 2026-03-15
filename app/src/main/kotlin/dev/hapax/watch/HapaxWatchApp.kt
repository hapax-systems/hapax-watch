package dev.hapax.watch

import android.app.Application
import android.util.Log

class HapaxWatchApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HapaxWatch starting")
    }

    companion object {
        const val TAG = "HapaxWatch"
    }
}
