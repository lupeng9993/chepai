package com.parkphone.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录器：异常退出时把堆栈写入应用专属目录；
 * 下次启动读出并交给界面展示，可一键复制日志文本。
 */
object CrashGuard {

    private const val PREFS = "crash"
    private var prefs: SharedPreferences? = null

    fun install(app: android.app.Application) {
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                prefs?.edit()?.putString(
                    "last",
                    "时间：$time\n线程：${thread.name}\n$sw"
                )?.apply()
            }
            prev?.uncaughtException(thread, e)
        }
    }

    /** 取出上次崩溃日志并清空 */
    fun take(): String? {
        val s = prefs?.getString("last", null)
        prefs?.edit()?.remove("last")?.apply()
        return s
    }

    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("crash", text))
    }
}
