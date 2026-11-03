package com.parkphone.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject

data class ParkRecord(
    val id: Long,
    val title: String,
    val phone: String,
    val time: Long,
    val hasPhoto: Boolean
)

/** 简单的本地记录存储：元数据存 SharedPreferences，照片存应用私有目录 */
object RecordStore {

    private const val PREFS = "records"
    private const val KEY = "json"

    fun all(ctx: android.content.Context): MutableList<ParkRecord> {
        val raw = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val list = mutableListOf<ParkRecord>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                ParkRecord(
                    o.getLong("id"),
                    o.getString("title"),
                    o.getString("phone"),
                    o.getLong("time"),
                    o.optBoolean("hasPhoto", false)
                )
            )
        }
        // 最新的排前面
        list.sortByDescending { it.time }
        return list
    }

    fun save(ctx: android.content.Context, title: String, phone: String, photo: Bitmap?) {
        val id = System.currentTimeMillis()
        var hasPhoto = false
        if (photo != null) {
            val f = photoFile(ctx, id)
            f.outputStream().use { out ->
                photo.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            hasPhoto = true
        }
        val arr = JSONArray()
        all(ctx).forEach { arr.put(toJson(it)) }
        arr.put(
            JSONObject()
                .put("id", id)
                .put("title", title)
                .put("phone", phone)
                .put("time", System.currentTimeMillis())
                .put("hasPhoto", hasPhoto)
        )
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun delete(ctx: android.content.Context, rec: ParkRecord) {
        val arr = JSONArray()
        all(ctx).filter { it.id != rec.id }.forEach { arr.put(toJson(it)) }
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
        photoFile(ctx, rec.id).delete()
    }

    fun loadPhoto(ctx: android.content.Context, id: Long): Bitmap? {
        val f = photoFile(ctx, id)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    private fun photoFile(ctx: android.content.Context, id: Long) =
        java.io.File(ctx.filesDir, "photo_$id.jpg")

    private fun toJson(r: ParkRecord) = JSONObject()
        .put("id", r.id)
        .put("title", r.title)
        .put("phone", r.phone)
        .put("time", r.time)
        .put("hasPhoto", r.hasPhoto)
}
