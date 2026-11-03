package com.parkphone.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ParkRecord(
    val id: Long,
    val title: String,
    val phone: String,
    val time: Long,
    val callCount: Int,
    val color: Int,   // 0蓝 1绿 2橙 3无
    val note: String,
    val hidden: Boolean
)

/** 本地记录存储：元数据存 SharedPreferences；含隐藏标记、拨打次数、颜色、备注 */
object RecordStore {

    private const val PREFS = "records"
    private const val KEY = "json"

    private const val PREFS_TAG = "tags"
    private const val KEY_TAG = "list"
    private val DEFAULT_TAGS = listOf("无人接听", "已处理", "稍后再拨", "态度差")

    private const val PREFS_SECRET = "secret"

    fun all(ctx: Context, hidden: Boolean): List<ParkRecord> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val list = mutableListOf<ParkRecord>()
        // 全程容错：任何一条脏数据只跳过该条，不影响整个列表加载
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val phone = o.optString("phone")
                if (phone.isBlank()) continue
                val r = ParkRecord(
                    id = o.optLong("id", System.currentTimeMillis() + i),
                    title = o.optString("title").ifBlank { ctx.getString(R.string.untitled) },
                    phone = phone,
                    time = o.optLong("time", System.currentTimeMillis()),
                    callCount = o.optInt("callCount", 0).coerceAtLeast(0),
                    color = o.optInt("color", 3).let { if (it in 0..3) it else 3 },
                    note = o.optString("note"),
                    hidden = o.optBoolean("hidden", false)
                )
                if (r.hidden == hidden) list.add(r)
            }
        }
        return list
    }

    /** 历史用过的标题，按最近使用排序、去重，供拍摄确认弹窗下拉 */
    fun titles(ctx: Context): List<String> {
        val out = LinkedHashSet<String>()
        (all(ctx, false) + all(ctx, true)).sortedByDescending { it.time }
            .forEach { if (it.title.isNotBlank()) out.add(it.title) }
        return out.toList()
    }

    fun save(ctx: Context, title: String, phone: String) {
        val arr = dump(ctx)
        arr.put(
            JSONObject()
                .put("id", System.currentTimeMillis())
                .put("title", title)
                .put("phone", phone)
                .put("time", System.currentTimeMillis())
                .put("callCount", 0)
                .put("color", 3)
                .put("note", "")
                .put("hidden", false)
        )
        write(ctx, arr)
    }

    /** 批量导入：目标区内已有相同号码则跳过；返回 Pair(实际导入, 重复跳过) */
    fun importBatch(
        ctx: Context,
        parsed: List<ExcelImporter.ParsedRow>,
        hidden: Boolean,
        existingPhones: Set<String>
    ): Pair<Int, Int> {
        var dup = 0
        val arr = dump(ctx)
        val seen = HashSet(existingPhones)
        val base = System.currentTimeMillis()
        parsed.forEachIndexed { i, p ->
            if (p.phone in seen) { dup++; return@forEachIndexed }
            seen.add(p.phone)
            arr.put(
                JSONObject()
                    .put("id", base + i)
                    .put("title", p.title.ifBlank { ctx.getString(R.string.untitled) })
                    .put("phone", p.phone)
                    .put("time", p.time)
                    .put("callCount", p.callCount)
                    .put("color", p.color)
                    .put("note", p.note)
                    .put("hidden", hidden)
            )
        }
        write(ctx, arr)
        return (parsed.size - dup) to dup
    }

    fun delete(ctx: Context, rec: ParkRecord) {
        val arr = dump(ctx)
        keepIf(arr) { it.id != rec.id }
        write(ctx, arr)
    }

    fun update(ctx: Context, id: Long, block: (ParkRecord) -> ParkRecord) {
        val arr = dump(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getLong("id") == id) {
                val old = fromJson(o)
                arr.put(i, toJson(block(old)))
                break
            }
        }
        write(ctx, arr)
    }

    fun setHidden(ctx: Context, id: Long, hidden: Boolean) =
        update(ctx, id) { it.copy(hidden = hidden) }

    fun setColor(ctx: Context, id: Long, color: Int) =
        update(ctx, id) { it.copy(color = if (it.color == color) 3 else color) }

    fun addCall(ctx: Context, id: Long) =
        update(ctx, id) { it.copy(callCount = it.callCount + 1) }

    fun setNote(ctx: Context, id: Long, note: String) =
        update(ctx, id) { it.copy(note = note) }

    // ---------- 快捷标签 ----------

    fun tags(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS_TAG, Context.MODE_PRIVATE)
            .getString(KEY_TAG, "") ?: ""
        if (raw.isBlank()) return DEFAULT_TAGS
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(DEFAULT_TAGS)
    }

    fun setTags(ctx: Context, tags: List<String>) {
        val arr = JSONArray()
        tags.forEach { arr.put(it) }
        ctx.getSharedPreferences(PREFS_TAG, Context.MODE_PRIVATE)
            .edit().putString(KEY_TAG, arr.toString()).apply()
    }

    // ---------- 隐藏区密码（本机存储，4位数字） ----------

    fun hasPassword(ctx: Context) = !pwd(ctx).isNullOrEmpty()

    fun setPassword(ctx: Context, pwd: String) {
        ctx.getSharedPreferences(PREFS_SECRET, Context.MODE_PRIVATE)
            .edit().putString("pwd", pwd).apply()
    }

    fun checkPassword(ctx: Context, input: String) = pwd(ctx) == input

    fun lockUntil(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS_SECRET, Context.MODE_PRIVATE)
            .getLong("lockUntil", 0L)

    fun setLock(ctx: Context, until: Long) {
        ctx.getSharedPreferences(PREFS_SECRET, Context.MODE_PRIVATE)
            .edit().putLong("lockUntil", until).apply()
    }

    fun wrongCount(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_SECRET, Context.MODE_PRIVATE)
            .getInt("wrong", 0)

    fun addWrong(ctx: Context): Int {
        val p = ctx.getSharedPreferences(PREFS_SECRET, Context.MODE_PRIVATE)
        val n = p.getInt("wrong", 0) + 1
        p.edit().putInt("wrong", n).apply()
        return n
    }

    fun clearWrong(ctx: Context) {
        ctx.getSharedPreferences(PREFS_SECRET, Context.MODE_PRIVATE)
            .edit().putInt("wrong", 0).apply()
    }

    private fun pwd(ctx: Context) =
        ctx.getSharedPreferences(PREFS_SECRET, Context.MODE_PRIVATE)
            .getString("pwd", null)

    // ---------- 内部 ----------

    private fun dump(ctx: Context): JSONArray {
        val arr = JSONArray()
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        runCatching {
            val old = JSONArray(raw)
            for (i in 0 until old.length()) {
                old.optJSONObject(i)?.let { arr.put(it) }
            }
        }
        return arr
    }

    private fun keepIf(arr: JSONArray, keep: (ParkRecord) -> Boolean) {
        val items = mutableListOf<JSONObject>()
        while (arr.length() > 0) {
            val o = arr.optJSONObject(0)
            arr.remove(0)
            if (o != null && keep(fromJson(o))) items.add(o)
        }
        items.forEach { arr.put(it) }
    }

    private fun fromJson(o: JSONObject) = ParkRecord(
        id = o.optLong("id", System.currentTimeMillis()),
        title = o.optString("title"),
        phone = o.optString("phone"),
        time = o.optLong("time", System.currentTimeMillis()),
        callCount = o.optInt("callCount", 0).coerceAtLeast(0),
        color = o.optInt("color", 3).let { if (it in 0..3) it else 3 },
        note = o.optString("note"),
        hidden = o.optBoolean("hidden", false)
    )

    private fun toJson(r: ParkRecord) = JSONObject()
        .put("id", r.id)
        .put("title", r.title)
        .put("phone", r.phone)
        .put("time", r.time)
        .put("callCount", r.callCount)
        .put("color", r.color)
        .put("note", r.note)
        .put("hidden", r.hidden)

    private fun write(ctx: Context, arr: JSONArray) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
