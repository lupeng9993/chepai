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
    private const val FILE = "crash_log.txt"
    private var prefs: SharedPreferences? = null
    private var dir: File? = null

    fun install(app: android.app.Application) {
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        dir = app.filesDir
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            record(app, e, thread.name)
            prev?.uncaughtException(thread, e)
        }
    }

    /** 主动记录一次异常（同步写入，确保进程被杀也不丢） */
    fun record(ctx: Context, e: Throwable, threadName: String = Thread.currentThread().name) {
        runCatching {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            val text = "时间：$time\n线程：$threadName\n$sw"
            // 通道1：SharedPreferences（同步 commit）
            (prefs ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
                .edit().putString("last", text).commit()
            // 通道2：文件备份
            (dir ?: ctx.filesDir)?.let { File(it, FILE).writeText(text) }
        }
    }

    /** 取出上次崩溃日志并清空 */
    fun take(): String? {
        val fromPref = prefs?.getString("last", null)
        prefs?.edit()?.remove("last")?.apply()
        val fromFile = runCatching {
            dir?.let { File(it, FILE) }?.takeIf { f -> f.exists() }?.readText()
        }.getOrNull()
        val s = fromPref ?: fromFile
        runCatching { dir?.let { File(it, FILE) }?.delete() }
        return s?.takeIf { it.isNotBlank() }
    }

    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("crash", text))
    }
}
