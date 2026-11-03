package com.parkphone.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 定时接收器：到点静默取一次数据/补传，不弹通知、不提示 */
class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val app = ctx.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (SyncEngine.isReceiver(app)) SyncEngine.pullAll(app)
                else SyncEngine.uploadIfDirty(app)
            } catch (t: Throwable) {
                // 静默失败，下次启动或下轮定时再试
            } finally {
                pending.finish()
            }
        }
    }
}
