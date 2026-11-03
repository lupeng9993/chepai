package com.parkphone.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        // 启动即：上传端补传被杀期间的改动；接收端挂上每6小时定时拉取
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { SyncEngine.catchUpOnLaunch(this@App) }
        }
    }
}
