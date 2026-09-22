package com.example.myandroidime

import android.app.Application

/**
 * Telemetry belongs to the application process, not the IME service lifecycle.
 * InputMethodService instances can be destroyed/recreated while the app process
 * remains alive; keeping the HTTP server here makes 127.0.0.1:8765 stable.
 */
class MyAndroidImeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ImeTelemetry.start()
    }
}