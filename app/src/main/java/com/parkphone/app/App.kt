package com.parkphone.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }
}
