package com.parkphone.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** 角色、设备名、快照序列化、静默上传、定时拉取 */
object SyncEngine {

    private const val PREFS = "sync"
    private const val KEY_NAME = "dev_name"
    private const val KEY_DIRTY = "dirty"
    private const val ALARM_ID = 7301

    /**
     * 角色判定：隐藏区里存在号码为 114 的记录 → 接收端；否则 → 上传端。
     * 不手动添加 114 时，软件界面与行为跟没有同步功能时完全一样。
     */
    fun isReceiver(ctx: Context): Boolean = runCatching {
        RecordStore.all(ctx, true).any {
            it.phone.filter { c -> c.isDigit() } == SyncConfig.RECEIVER_TOKEN
        }
    }.getOrDefault(false)

    // ---------------- 设备名（家长手填：孩子手机尾号后4位） ----------------

    /** 已手填则用手填值；否则用稳定的占位名（不读号码，无任何权限弹窗） */
    fun deviceName(ctx: Context): String {
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null)
        if (!saved.isNullOrEmpty()) return saved
        val name = "DEV" + MessageDigest.getInstance("SHA-1")
            .digest((Build.MODEL + Build.MANUFACTURER).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(4)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NAME, name).apply()
        return name
    }

    /** 是否已由家长手填过 */
    fun nameManualSet(ctx: Context): Boolean =
        !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, "").isNullOrEmpty()

    fun saveDeviceName(ctx: Context, name: String) {
        val clean = name.filter { it.isLetterOrDigit() }.take(12)
        if (clean.isEmpty()) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NAME, clean).apply()
        // 改名后旧文件作废，标记为待重新上传
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DIRTY, true).apply()
        if (!isReceiver(ctx)) uploadIfDirty(ctx)
    }

    // ---------------- 快照 ----------------

    fun snapshot(ctx: Context): String {
        val arr = JSONArray()
        RecordStore.all(ctx, false).forEach { r ->
            arr.put(
                JSONObject()
                    .put("title", r.title).put("phone", r.phone).put("time", r.time)
                    .put("callCount", r.callCount).put("color", r.color).put("note", r.note)
            )
        }
        return JSONObject()
            .put("device", deviceName(ctx))
            .put("model", Build.MODEL)
            .put("updatedAt", System.currentTimeMillis())
            .put("records", arr)
            .toString()
    }

    /** 所有本地写操作后由 RecordStore 调用：上传端静默发送，接收端不动 */
    fun onLocalChanged(ctx: Context) {
        if (isReceiver(ctx)) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DIRTY, true).apply()
        CoroutineScope(Dispatchers.IO).launch { uploadIfDirty(ctx) }
    }

    fun uploadIfDirty(ctx: Context): Boolean {
        if (isReceiver(ctx)) return false
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_DIRTY, false)) return false
        val ok = runCatching {
            SyncClient.put(deviceName(ctx), SyncClient.encrypt(snapshot(ctx)))
        }.getOrDefault(false)
        if (ok) p.edit().putBoolean(KEY_DIRTY, false).apply()
        return ok
    }

    /** App 启动时补传：被杀期间改过的数据，一打开就静默送出 */
    fun catchUpOnLaunch(ctx: Context) {
        if (isReceiver(ctx)) scheduleAutoPull(ctx) else uploadIfDirty(ctx)
    }

    // ---------------- 拉取（接收端） ----------------

    fun selectedDevices(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("selected", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    fun setSelectedDevices(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("selected", list.joinToString(",")).apply()
    }

    /** 拉取所有已选设备，返回本次成功条数 */
    fun pullAll(ctx: Context): Int {
        var n = 0
        selectedDevices(ctx).forEach { dev ->
            val raw = SyncClient.get(dev) ?: return@forEach
            val plain = SyncClient.decrypt(raw) ?: return@forEach
            val count = runCatching { PeerStore.replace(ctx, dev, plain) }.getOrDefault(0)
            if (count > 0 || plain.contains("\"records\":[]")) n += count
        }
        return n
    }

    fun scheduleAutoPull(ctx: Context) {
        if (!isReceiver(ctx)) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pullIntent(ctx)
        runCatching {
            am.cancel(pi)
            am.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 6L * 3600_000,
                6L * 3600_000,
                pi
            )
        }
    }

    private fun pullIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, ALARM_ID, Intent(ctx, SyncReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
