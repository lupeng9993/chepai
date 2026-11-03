package com.parkphone.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 接收端存放各对端拉来的只读快照 */
object PeerStore {

    private const val PREFS = "peer"

    data class Peer(val device: String, val model: String, val updatedAt: Long, val records: List<ParkRecord>)

    /** 写入一份解析后的快照，返回记录条数 */
    fun replace(ctx: Context, device: String, plain: String): Int {
        val o = JSONObject(plain)
        val arr = o.getJSONArray("records")
        val list = ArrayList<ParkRecord>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val phone = r.optString("phone")
            if (phone.isBlank()) continue
            list.add(
                ParkRecord(
                    id = "${device}_$i".hashCode().toLong() and 0x7fffffffL,
                    title = r.optString("title").ifBlank { ctx.getString(R.string.untitled) },
                    phone = phone,
                    time = r.optLong("time", System.currentTimeMillis()),
                    callCount = r.optInt("callCount", 0).coerceAtLeast(0),
                    color = r.optInt("color", 3).let { if (it in 0..3) it else 3 },
                    note = r.optString("note"),
                    hidden = false
                )
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("data_$device", plain)
            .putLong("at_$device", o.optLong("updatedAt", System.currentTimeMillis()))
            .putString("model_$device", o.optString("model", ""))
            .apply()
        return list.size
    }

    fun peers(ctx: Context): List<Peer> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SyncEngine.selectedDevices(ctx).mapNotNull { dev ->
            val plain = p.getString("data_$dev", null) ?: return@mapNotNull null
            val list = runCatching {
                val arr = JSONObject(plain).getJSONArray("records")
                (0 until arr.length()).mapNotNull { i ->
                    val r = arr.optJSONObject(i) ?: return@mapNotNull null
                    val phone = r.optString("phone")
                    if (phone.isBlank()) return@mapNotNull null
                    ParkRecord(
                        id = "${dev}_$i".hashCode().toLong() and 0x7fffffffL,
                        title = r.optString("title").ifBlank { ctx.getString(R.string.untitled) },
                        phone = phone,
                        time = r.optLong("time", System.currentTimeMillis()),
                        callCount = r.optInt("callCount", 0).coerceAtLeast(0),
                        color = r.optInt("color", 3).let { if (it in 0..3) it else 3 },
                        note = r.optString("note"),
                        hidden = false
                    )
                }
            }.getOrDefault(emptyList())
            Peer(dev, p.getString("model_$dev", "") ?: "", p.getLong("at_$dev", 0L), list)
        }
    }

    fun allPeerRecords(ctx: Context): List<ParkRecord> = peers(ctx).flatMap { it.records }

    fun alias(ctx: Context, device: String): String =
        ctx.getSharedPreferences("peer_alias", Context.MODE_PRIVATE).getString(device, device) ?: device

    fun setAlias(ctx: Context, device: String, alias: String) {
        ctx.getSharedPreferences("peer_alias", Context.MODE_PRIVATE).edit()
            .putString(device, alias).apply()
    }
}
