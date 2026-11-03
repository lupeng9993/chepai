package com.parkphone.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.parkphone.app.databinding.ActivityRecordsBinding
import com.parkphone.app.databinding.DialogExportBinding
import com.parkphone.app.databinding.DialogNoteBinding
import com.parkphone.app.databinding.DialogPasswordBinding
import com.parkphone.app.databinding.ItemGroupHeaderBinding
import com.parkphone.app.databinding.ItemRecordBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordsBinding
    private val adapter = RecAdapter()

    /** false=正常区（浅色），true=隐藏区（深色） */
    private var hiddenZone = false

    /** true=正在查看对端（孩子）数据分区，仅接收端可达 */
    private var peerZone = false

    // 连点标题计数
    private var tapCount = 0
    private var lastTapAt = 0L

    private val COLOR_RES = intArrayOf(R.color.mark_blue, R.color.mark_green, R.color.mark_orange)

    // ------- 对端只读展示的本机覆盖层：拨打加次 / 颜色 / 备注 / 隐藏 -------
    private val ovPrefs by lazy { getSharedPreferences("peer_ov", MODE_PRIVATE) }

    private data class Override(
        val add: Int, val color: Int, val hasColor: Boolean,
        val note: String?, val hide: Boolean
    )

    private fun overrideOf(id: Long): Override {
        val s = ovPrefs.getString("o$id", null) ?: return Override(0, 3, false, null, false)
        val p = s.split("~")
        return Override(
            add = p.getOrNull(0)?.toIntOrNull() ?: 0,
            color = p.getOrNull(1)?.toIntOrNull() ?: 3,
            hasColor = p.getOrNull(2) == "1",
            note = p.getOrNull(3)?.ifBlank { null },
            hide = p.getOrNull(4) == "1"
        )
    }

    private fun saveOverride(id: Long, o: Override) {
        ovPrefs.edit().putString("o$id", "${o.add}~${o.color}~${if (o.hasColor) 1 else 0}~${o.note ?: ""}~${if (o.hide) 1 else 0}").apply()
    }

    /** 把对端记录套上本机覆盖层后的显示值 */
    private fun applied(rec: ParkRecord): ParkRecord {
        val o = overrideOf(rec.id)
        return rec.copy(
            callCount = rec.callCount + o.add,
            color = if (o.hasColor) o.color else rec.color,
            note = o.note ?: rec.note
        )
    }

    private val createDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
            if (uri != null) writeExport(uri)
        }

    private val openDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityRecordsBinding.inflate(layoutInflater)
            setContentView(binding.root)

            binding.recycler.layoutManager = LinearLayoutManager(this)
            binding.recycler.adapter = adapter
            binding.btnBack.setOnClickListener {
                if (hiddenZone) exitHidden() else finish()
            }
            binding.btnExport.setOnClickListener { showExportMenu() }
            binding.btnImport.setOnClickListener {
                openDoc.launch(arrayOf("*/*"))
            }
            binding.tvHead.setOnClickListener { onHeadTap() }
        binding.tvHead.setOnLongClickListener {
            if (hiddenZone) { showHiddenAdminDialog(); true } else false
        }

            binding.tabLocal.setOnClickListener { setZone(peer = false) }
            binding.tabPeer.setOnClickListener { setZone(peer = true) }

            applyTheme()
            refresh()
        } catch (t: Throwable) {
            CrashGuard.record(this, t)
            val tv = TextView(this).apply {
                text = "记录页初始化失败：${t.javaClass.simpleName} ${t.message}"
                setPadding(40, 120, 40, 40)
                textSize = 13f
                setTextIsSelectable(true)
            }
            setContentView(tv)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------- 主题（明/暗两套手动切换） ----------------

    private fun applyTheme() {
        val bg = color(if (hiddenZone) R.color.dark_bg else R.color.light_bg)
        binding.root.setBackgroundColor(bg)
        binding.tvHead.setTextColor(color(if (hiddenZone) R.color.dark_text else R.color.light_text))
        binding.btnBack.setTextColor(color(if (hiddenZone) R.color.dark_group_text else R.color.mark_blue))
        binding.tvEmpty.setTextColor(color(if (hiddenZone) R.color.dark_sub else R.color.light_hint))
        binding.tvEmpty.setText(if (hiddenZone) R.string.hidden_empty else R.string.records_empty)
        binding.tvHead.text = getString(if (hiddenZone) R.string.hidden_title else R.string.records_title)

        // 标签行：仅接收端且非隐藏区
        binding.tabRow.visibility = if (SyncEngine.isReceiver(this) && !hiddenZone) View.VISIBLE else View.GONE
        binding.tabLocal.setBackgroundResource(
            if (peerZone) R.drawable.tag_add_bg else R.drawable.pill_bg
        )
        binding.tabLocal.setTextColor(
            if (peerZone) color(R.color.mark_blue) else 0xFFFFFFFF.toInt()
        )
        binding.tabPeer.setBackgroundResource(
            if (peerZone) R.drawable.pill_bg else R.drawable.tag_add_bg
        )
        binding.tabPeer.setTextColor(
            if (peerZone) 0xFFFFFFFF.toInt() else color(R.color.mark_blue)
        )
        binding.tabPeer.text = getString(R.string.tab_peer)
        // 隐藏导入按钮：接收端在隐藏区外不需要手动导入也保留功能，此处不改动
    }

    private fun setZone(peer: Boolean) {
        peerZone = peer
        applyTheme()
        refresh()
    }

    /** 隐藏区长按标题：接收令牌与同步名设置 */
    private fun showHiddenAdminDialog() {
        val hasToken = RecordStore.all(this, true)
            .any { it.phone.filter { c -> c.isDigit() } == SyncConfig.RECEIVER_TOKEN }
        val actions = arrayOf(
            if (hasToken) getString(R.string.token_exists)
            else getString(R.string.token_add),
            "${getString(R.string.devname_title)}（${SyncEngine.deviceName(this)}）"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hidden_admin_title)
            .setItems(actions) { _, which ->
                if (which == 0) {
                    if (hasToken) Toast.makeText(this, R.string.token_exists, Toast.LENGTH_SHORT).show()
                    else addToken()
                } else askDeviceName()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addToken() {
        RecordStore.save(this, getString(R.string.token_title), SyncConfig.RECEIVER_TOKEN, hidden = true)
        Toast.makeText(this, R.string.token_added, Toast.LENGTH_LONG).show()
        refresh()
    }

    /** 隐藏区长按标题：设置本机同步名（孩子手机尾号后4位） */
    private fun askDeviceName() {
        val et = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            hint = getString(R.string.devname_hint)
            setText(SyncEngine.deviceName(this@RecordsActivity))
            setSelectAllOnFocus(true)
            maxLines = 1
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        wrap.addView(TextView(this).apply {
            text = getString(R.string.devname_hint)
            textSize = 12f
            setTextColor(color(R.color.light_sub))
        })
        wrap.addView(et)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.devname_title)
            .setView(wrap)
            .setPositiveButton(R.string.save) { _, _ ->
                val v = et.text.toString().filter { it.isLetterOrDigit() }
                if (v.length < 4) {
                    Toast.makeText(this, R.string.devname_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                SyncEngine.saveDeviceName(this, v)
                Toast.makeText(this, getString(R.string.devname_saved, v), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    // ---------------- 数据 ----------------

    private data class Row(
        val isHeader: Boolean,
        val headerTitle: String = "",
        val headerTime: Long = 0,
        val rec: ParkRecord? = null,
        val peer: Boolean = false
    )

    private var rows: List<Row> = emptyList()

    private val dayFmt by lazy { SimpleDateFormat("yyyy年M月d日", Locale.CHINA) }

    private fun refresh() {
        try {
            rows = if (peerZone) peerRows() else localRows()
            adapter.notifyDataSetChanged()
            binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            if (peerZone && rows.isEmpty()) {
                binding.tvEmpty.text = getString(R.string.peer_empty)
            } else if (!hiddenZone) {
                binding.tvEmpty.setText(R.string.records_empty)
            } else {
                binding.tvEmpty.setText(R.string.hidden_empty)
            }
            val n = rows.count { !it.isHeader }
            if (peerZone) {
                binding.tabPeer.text = "${getString(R.string.tab_peer)} $n"
            } else {
                binding.tabLocal.text = "${getString(R.string.tab_local)}（$n）"
            }
        } catch (t: Throwable) {
            CrashGuard.record(this, t)
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = "列表刷新失败：${t.javaClass.simpleName} ${t.message}"
            binding.tvEmpty.setTextIsSelectable(true)
        }
    }

    private fun localRows(): List<Row> {
        val data = RecordStore.all(this, hiddenZone)
        val today = dayFmt.format(Date())
        val grouped = LinkedHashMap<String, MutableList<ParkRecord>>()
        data.sortedByDescending { it.time }.forEach { r ->
            val day = dayFmt.format(Date(r.time))
            val key = r.title + "｜" + if (day == today) "$day（今天）" else day
            grouped.getOrPut(key) { mutableListOf() }.add(r)
        }
        val out = mutableListOf<Row>()
        grouped.forEach { (key, list) ->
            out.add(Row(true, key, list.first().time))
            list.forEach { out.add(Row(false, rec = it)) }
        }
        return out
    }

    /** 对端分区：一台孩子设备一个组头，组内按设备自己的日期再分 */
    private fun peerRows(): List<Row> {
        val out = mutableListOf<Row>()
        val today = dayFmt.format(Date())
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        PeerStore.peers(this).forEach { peer ->
            val list = peer.records.map { applied(it) }.filterNot { overrideOf(it.id).hide }
                .sortedByDescending { it.time }
            if (list.isEmpty()) return@forEach
            val head = "📱 ${peer.device} · ${list.size} 条" +
                if (peer.updatedAt > 0) "（对方上传于 ${stamp.format(Date(peer.updatedAt))}）" else ""
            out.add(Row(true, head, peer.updatedAt))
            val grouped = LinkedHashMap<String, MutableList<ParkRecord>>()
            list.forEach { r ->
                val day = dayFmt.format(Date(r.time))
                val key = r.title + "｜" + if (day == today) "$day（今天）" else day
                grouped.getOrPut(key) { mutableListOf() }.add(r)
            }
            grouped.forEach { (key, recs) ->
                out.add(Row(true, "　" + key, recs.first().time, peer = true))
                recs.forEach { out.add(Row(false, rec = it, peer = true)) }
            }
        }
        return out
    }

    // ---------------- 连点标题 → 密码 → 隐藏区 ----------------

    private fun onHeadTap() {
        if (hiddenZone) return // 隐藏区内用返回键退出
        val now = System.currentTimeMillis()
        tapCount = if (now - lastTapAt > 1000) 1 else tapCount + 1
        lastTapAt = now
        if (tapCount >= 5) {
            tapCount = 0
            openPasswordFlow()
        }
    }

    private fun openPasswordFlow() {
        val lockedUntil = RecordStore.lockUntil(this)
        val remain = (lockedUntil - System.currentTimeMillis()) / 1000
        if (remain > 0) {
            Toast.makeText(this, getString(R.string.pwd_locked, remain), Toast.LENGTH_SHORT).show()
            return
        }
        showPasswordDialog(RecordStore.hasPassword(this))
    }

    private var pendingPwd = ""
    private var firstSetPwd = ""

    /** verify=false 表示首次设置模式（输入两遍） */
    private fun showPasswordDialog(verify: Boolean) {
        pendingPwd = ""
        firstSetPwd = ""
        val dp = DialogPasswordBinding.inflate(layoutInflater)
        dp.tvPwdTip.text = getString(
            if (!verify) R.string.pwd_set
            else R.string.pwd_input
        )
        dp.tvPwdWarn.visibility = if (verify) View.GONE else View.VISIBLE
        dp.tvPwdWarn.text = getString(R.string.pwd_tip)
        dp.tvPwdMsg.text = ""

        val dialog = MaterialAlertDialogBuilder(this).setView(dp.root).create()

        fun render() {
            dp.dot0.setBackgroundResource(if (pendingPwd.length >= 1) R.drawable.pwd_dot_on else R.drawable.pwd_dot_off)
            dp.dot1.setBackgroundResource(if (pendingPwd.length >= 2) R.drawable.pwd_dot_on else R.drawable.pwd_dot_off)
            dp.dot2.setBackgroundResource(if (pendingPwd.length >= 3) R.drawable.pwd_dot_on else R.drawable.pwd_dot_off)
            dp.dot3.setBackgroundResource(if (pendingPwd.length >= 4) R.drawable.pwd_dot_on else R.drawable.pwd_dot_off)
        }

        var handleFn: () -> Unit = {}

        fun onKey(k: String) {
            when (k) {
                "del" -> if (pendingPwd.isNotEmpty()) pendingPwd = pendingPwd.dropLast(1)
                "clear" -> pendingPwd = ""
                else -> if (pendingPwd.length < 4) pendingPwd += k
            }
            dp.tvPwdMsg.text = ""
            render()
            if (pendingPwd.length == 4) handleFn()
        }

        handleFn = fun() {
            if (!verify) {
                // 设置流程：第一遍暂存，第二遍比对
                if (firstSetPwd.isEmpty()) {
                    firstSetPwd = pendingPwd
                    pendingPwd = ""
                    dp.tvPwdTip.text = getString(R.string.pwd_set_again)
                    render()
                } else {
                    if (firstSetPwd == pendingPwd) {
                        RecordStore.setPassword(this, pendingPwd)
                        RecordStore.clearWrong(this)
                        dialog.dismiss()
                        enterHidden()
                    } else {
                        dp.tvPwdMsg.text = getString(R.string.pwd_mismatch)
                        firstSetPwd = ""
                        pendingPwd = ""
                        dp.tvPwdTip.text = getString(R.string.pwd_set)
                        render()
                    }
                }
            } else {
                if (RecordStore.checkPassword(this, pendingPwd)) {
                    RecordStore.clearWrong(this)
                    dialog.dismiss()
                    enterHidden()
                } else {
                    val wrong = RecordStore.addWrong(this)
                    pendingPwd = ""
                    render()
                    if (wrong >= 5) {
                        RecordStore.setLock(this, System.currentTimeMillis() + 60_000)
                        RecordStore.clearWrong(this)
                        dialog.dismiss()
                        Toast.makeText(this, getString(R.string.pwd_locked, 60), Toast.LENGTH_LONG).show()
                    } else {
                        dp.tvPwdMsg.text = getString(R.string.pwd_bad)
                    }
                }
            }
        }

        // 数字键：按 GridLayout 子 View 文本绑定
        (0 until dp.keypad.childCount).forEach { i ->
            val v = dp.keypad.getChildAt(i) as? TextView ?: return@forEach
            when (v.id) {
                dp.keyClear.id -> v.setOnClickListener { onKey("clear") }
                dp.keyDel.id -> v.setOnClickListener { onKey("del") }
                else -> v.setOnClickListener { onKey(v.text.toString()) }
            }
        }
        render()
        dialog.show()
    }

    private fun enterHidden() {
        hiddenZone = true
        applyTheme()
        refresh()
    }

    private fun exitHidden() {
        hiddenZone = false
        applyTheme()
        refresh()
    }

    // ---------------- 列表 ----------------

    private inner class RecAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int) = if (rows[position].isHeader) 0 else 1

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeadVH(ItemGroupHeaderBinding.inflate(inf, parent, false))
            else RecVH(ItemRecordBinding.inflate(inf, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeadVH -> holder.bind(rows[position].headerTitle)
                is RecVH -> rows[position].rec?.let { holder.bind(it, rows[position].peer) }
            }
        }
    }

    private inner class HeadVH(private val b: ItemGroupHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(title: String) {
            b.tvGroup.text = title
            (b.tvGroup.background.mutate() as? GradientDrawable)?.setColor(
                color(if (hiddenZone) R.color.dark_group_bg else R.color.group_bg)
            )
            b.tvGroup.setTextColor(color(if (hiddenZone) R.color.dark_group_text else R.color.group_text))
        }
    }

    private inner class RecVH(private val b: ItemRecordBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(rec: ParkRecord, peer: Boolean) {
            val dark = hiddenZone
            b.tvPhone.text = rec.phone
            b.tvPhone.setTextColor(color(if (dark) R.color.dark_text else R.color.light_text))
            b.tvCount.text = getString(R.string.call_count, rec.callCount)
            b.tvCount.setTextColor(color(if (dark) R.color.dark_sub else R.color.light_sub))

            // 卡片底色
            (b.root.background.mutate() as? GradientDrawable)?.setColor(
                color(if (dark) R.color.dark_card else R.color.light_card)
            )
            (b.root.background.mutate() as? GradientDrawable)?.setStroke(
                (1 * resources.displayMetrics.density).toInt(),
                color(if (dark) R.color.dark_stroke else R.color.light_stroke)
            )

            // 左侧色条 + 圆点
            val bar = b.colorBar.background.mutate()
            if (rec.color in 0..2) {
                b.colorBar.visibility = View.VISIBLE
                bar.setColorFilter(color(COLOR_RES[rec.color]), PorterDuff.Mode.SRC_ATOP)
            } else {
                b.colorBar.visibility = View.INVISIBLE
            }
            bindDot(b.dotBlue, 0, rec, peer)
            bindDot(b.dotGreen, 1, rec, peer)
            bindDot(b.dotOrange, 2, rec, peer)

            // 备注行
            if (rec.note.isNotBlank()) {
                b.tvNote.visibility = View.VISIBLE
                b.tvNote.text = getString(R.string.note_display, rec.note)
                b.tvNote.setTextColor(color(if (dark) R.color.dark_sub else R.color.light_note))
            } else b.tvNote.visibility = View.GONE

            // 恢复按钮：本机隐藏区 或 对端被本机隐藏后
            b.btnRestore.visibility = if (dark) View.VISIBLE else View.GONE
            b.btnRestore.setTextColor(color(R.color.dark_group_text))
            b.btnRestore.setOnClickListener {
                if (peer) {
                    val o = overrideOf(rec.id).copy(hide = false)
                    saveOverride(rec.id, o)
                } else {
                    RecordStore.setHidden(this@RecordsActivity, rec.id, false)
                }
                Toast.makeText(this@RecordsActivity, R.string.restored, Toast.LENGTH_SHORT).show()
                refresh()
            }

            b.btnNote.setTextColor(color(if (dark) R.color.dark_group_text else R.color.mark_blue))
            b.btnNote.setOnClickListener { showNoteDialog(rec, peer) }
            b.btnDial.setOnClickListener { dial(rec, peer) }
            b.btnDelete.setOnClickListener {
                if (peer) hidePeerLocally(rec) else confirmDelete(rec)
            }

            // 长按 3 秒 → 隐藏菜单；双击号码 → 修改号码（对端禁改）
            // 本机隐藏区的 114 令牌：双击/长按都不生效，改由备注入口承载同步功能
            val isToken = !peer && hiddenZone &&
                rec.phone.filter { it.isDigit() } == SyncConfig.RECEIVER_TOKEN
            if (isToken) {
                b.root.setOnTouchListener(null)
            } else {
                b.root.setOnTouchListener(
                    LongPress3(rec, peer) { if (!peer) showEditPhoneDialog(rec) }
                )
            }
        }

        /** 对端记录：仅在本机隐藏，不动对方源数据 */
        private fun hidePeerLocally(rec: ParkRecord) {
            MaterialAlertDialogBuilder(this@RecordsActivity)
                .setMessage(getString(R.string.peer_hide_confirm, rec.phone))
                .setPositiveButton(R.string.hide_this) { _, _ ->
                    saveOverride(rec.id, overrideOf(rec.id).copy(hide = true))
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun bindDot(dot: View, idx: Int, rec: ParkRecord, peer: Boolean) {
            val selected = rec.color == idx
            dot.setBackgroundResource(
                if (selected) R.drawable.dot_filled else R.drawable.dot_outline
            )
            if (selected) dot.background.mutate().setColorFilter(
                color(COLOR_RES[idx]), PorterDuff.Mode.SRC_ATOP
            )
            else (dot.background.mutate() as? GradientDrawable)?.setStroke(
                (1.5f * resources.displayMetrics.density).toInt(), 0xFFBBBBBB.toInt()
            )
            dot.setOnClickListener {
                if (peer) {
                    val cur = overrideOf(rec.id)
                    val hasC = if (rec.color == idx && cur.hasColor) false else true
                    val newC = if (rec.color == idx && cur.hasColor) 3 else idx
                    saveOverride(rec.id, cur.copy(color = newC, hasColor = hasC))
                } else {
                    RecordStore.setColor(this@RecordsActivity, rec.id, idx)
                }
                refresh()
            }
        }
    }

    /**
     * 长按 3000ms 触发隐藏菜单；
     * 落点在号码上且两下间隔 < 400ms、每下按住 < 3s 触发双击回调。
     */
    private inner class LongPress3(
        val rec: ParkRecord,
        val peer: Boolean,
        val onDoubleTapPhone: () -> Unit
    ) : View.OnTouchListener {
        private val handler = Handler(Looper.getMainLooper())
        private var startX = 0f
        private var startY = 0f
        private var fired = false
        private var lastTapUp = 0L

        private val timer = Runnable {
            fired = true
            showLongPressMenu(rec, peer)
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY; fired = false
                    handler.postDelayed(timer, 3000)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.rawX - startX) > 40 || kotlin.math.abs(e.rawY - startY) > 40) {
                        handler.removeCallbacks(timer)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(timer)
                    if (!fired && e.action == MotionEvent.ACTION_UP) {
                        // 号码位于卡片上部约 40%，用相对本视图的触点坐标判定
                        val inPhone = e.y < v.height * 0.40f
                        val now = System.currentTimeMillis()
                        if (inPhone && now - lastTapUp < 400) {
                            lastTapUp = 0
                            fired = true
                            onDoubleTapPhone()
                        } else {
                            lastTapUp = now
                        }
                    }
                    return fired
                }
            }
            return false
        }
    }

    private fun showLongPressMenu(rec: ParkRecord, peer: Boolean) {
        if (peer) {
            val o = overrideOf(rec.id)
            val options = arrayOf(
                if (o.hide) getString(R.string.restore_show) else getString(R.string.peer_hide_only)
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(rec.phone)
                .setItems(options) { _, _ ->
                    saveOverride(rec.id, o.copy(hide = !o.hide))
                    Toast.makeText(
                        this,
                        if (o.hide) R.string.restored else R.string.hidden_ok,
                        Toast.LENGTH_SHORT
                    ).show()
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val option = if (hiddenZone) getString(R.string.restore_show) else getString(R.string.hide_this)
        MaterialAlertDialogBuilder(this)
            .setTitle(rec.phone)
            .setItems(arrayOf(option)) { _, _ ->
                RecordStore.setHidden(this, rec.id, !hiddenZone)
                Toast.makeText(
                    this,
                    if (hiddenZone) R.string.restored else R.string.hidden_ok,
                    Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 拨打 / 删除 ----------------

    private fun dial(rec: ParkRecord, peer: Boolean) {
        if (peer) {
            saveOverride(rec.id, overrideOf(rec.id).copy(add = overrideOf(rec.id).add + 1))
        } else {
            RecordStore.addCall(this, rec.id)
        }
        refresh()
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${rec.phone}")))
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.no_dialer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(rec: ParkRecord) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, rec.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                RecordStore.delete(this, rec)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 双击修改号码 ----------------

    private fun showEditPhoneDialog(rec: ParkRecord) {
        val dayFmt = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
        val et = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(rec.phone)
            setSelection(rec.phone.length)
            textSize = 17f
            maxLines = 1
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val sub = TextView(this).apply {
            text = getString(R.string.edit_phone_sub, rec.title, dayFmt.format(Date(rec.time)), rec.callCount)
            textSize = 12f
            setTextColor(color(R.color.light_sub))
        }
        val warn = TextView(this).apply {
            textSize = 11f
            setTextColor(color(R.color.mark_orange))
        }
        wrap.addView(sub)
        wrap.addView(et)
        wrap.addView(warn)

        // 实时重复提醒：同区内出现相同号码时提示，但仍允许保存
        val others = RecordStore.all(this, hiddenZone).map { it.phone }.toHashSet()
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val d = s?.toString()?.filter { it.isDigit() } ?: ""
                val full = et.text.toString().filter { it.isDigit() || it == '+' }
                warn.text = if (d.length in 7..12 && full != rec.phone && full in others)
                    getString(R.string.edit_dup_warn, full) else ""
            }
        })

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_phone_title)
            .setView(wrap)
            .setPositiveButton(R.string.save) { _, _ ->
                val phone = et.text.toString().filter { it.isDigit() || it == '+' }
                val digits = phone.filter { it.isDigit() }
                if (digits.length !in 7..12) {
                    Toast.makeText(this, R.string.phone_too_short, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (phone == rec.phone) return@setPositiveButton
                RecordStore.setPhone(this, rec.id, phone)
                Toast.makeText(this, R.string.edit_saved, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 备注（含可编辑快捷标签；114 令牌为同步入口） ----------------

    private fun showNoteDialog(rec: ParkRecord, peer: Boolean) {
        // 隐藏区里的 114 令牌：备注即同步入口
        if (!peer && hiddenZone &&
            rec.phone.filter { it.isDigit() } == SyncConfig.RECEIVER_TOKEN
        ) {
            showSyncEntryDialog()
            return
        }

        val dn = DialogNoteBinding.inflate(layoutInflater)
        dn.tvNoteSub.text = getString(R.string.note_sub, rec.phone, rec.title)
        dn.etNote.setText(rec.note)
        buildTags(dn, rec)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.note_title)
            .setView(dn.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = dn.etNote.text.toString().trim()
                if (peer) saveOverride(rec.id, overrideOf(rec.id).copy(note = text))
                else RecordStore.setNote(this, rec.id, text)
                Toast.makeText(this, R.string.note_saved, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 114 备注弹出的同步入口 */
    private fun showSyncEntryDialog() {
        val selected = SyncEngine.selectedDevices(this)
        val msg = StringBuilder()
            .append(getString(R.string.sync_selected, selected.size))
        if (lastPullAt > 0) {
            msg.append("\n")
                .append(getString(R.string.sync_last_pull, lastPullFmt.format(Date(lastPullAt))))
        }
        val tv = TextView(this).apply {
            text = msg
            textSize = 13f
            setPadding(32, 16, 32, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_entry_title)
            .setView(tv)
            .setPositiveButton(R.string.sync_pick_senders) { _, _ ->
                startActivity(Intent(this, SyncDevicesActivity::class.java))
            }
            .setNeutralButton(R.string.sync_download_now) { _, _ -> manualDownload() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var lastPullAt = 0L
    private val lastPullFmt by lazy { SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA) }

    private fun manualDownload() {
        if (SyncEngine.selectedDevices(this).isEmpty()) {
            Toast.makeText(this, R.string.sync_no_selection, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.sync_downloading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching { SyncEngine.pullAll(this@RecordsActivity) }.getOrDefault(-1)
            }
            if (n < 0) {
                Toast.makeText(this@RecordsActivity, R.string.sync_pull_fail, Toast.LENGTH_LONG).show()
            } else {
                lastPullAt = System.currentTimeMillis()
                Toast.makeText(this@RecordsActivity, getString(R.string.sync_pull_ok, n), Toast.LENGTH_LONG).show()
                setZone(peer = true)
            }
        }
    }

    private fun buildTags(dn: DialogNoteBinding, rec: ParkRecord) {
        dn.tagWrap.removeAllViews()
        val density = resources.displayMetrics.density
        RecordStore.tags(this).forEachIndexed { index, tag ->
            val tv = TextView(this).apply {
                text = tag
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFF3D5A75.toInt())
                background = ContextCompat.getDrawable(this@RecordsActivity, R.drawable.tag_bg)
                val h = (10 * density).toInt()
                val v = (5 * density).toInt()
                setPadding(h, v, h, v)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (6 * density).toInt()
                layoutParams = lp
                setOnClickListener {
                    val cur = dn.etNote.text.toString()
                    dn.etNote.setText(if (cur.isBlank()) tag else "$cur $tag")
                    dn.etNote.setSelection(dn.etNote.text.length)
                }
                setOnLongClickListener {
                    editTag(index)
                    true
                }
            }
            dn.tagWrap.addView(tv)
        }
        val add = TextView(this).apply {
            text = getString(R.string.tag_add)
            textSize = 11f
            setTextColor(color(R.color.mark_blue))
            setBackgroundResource(R.drawable.tag_add_bg)
            val h = (10 * density).toInt()
            val v = (5 * density).toInt()
            setPadding(h, v, h, v)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
            setOnClickListener { addTag() }
        }
        dn.tagWrap.addView(add)
    }

    private fun editTag(index: Int) {
        val tags = RecordStore.tags(this).toMutableList()
        val old = tags[index]
        val actions = arrayOf(getString(R.string.tag_rename), getString(R.string.delete))
        MaterialAlertDialogBuilder(this)
            .setTitle(old)
            .setItems(actions) { _, which ->
                if (which == 0) renameTag(index, old)
                else {
                    tags.removeAt(index)
                    RecordStore.setTags(this, tags)
                }
            }
            .show()
    }

    private fun renameTag(index: Int, old: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.tag_new_name)
            setText(old)
            setSelection(old.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_rename)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val nv = input.text.toString().trim()
                if (nv.isNotEmpty()) {
                    val tags = RecordStore.tags(this).toMutableList()
                    tags[index] = nv
                    RecordStore.setTags(this, tags)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addTag() {
        val input = EditText(this).apply { hint = getString(R.string.tag_new_name) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_add)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val nv = input.text.toString().trim()
                if (nv.isNotEmpty()) {
                    val tags = RecordStore.tags(this).toMutableList()
                    tags.add(nv)
                    RecordStore.setTags(this, tags)
                    Toast.makeText(this, R.string.tag_added, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 对端分区：单独导出孩子数据 */
    private fun exportPeerData() {
        val list = PeerStore.peers(this).flatMap { p ->
            p.records.map { applied(it) }.filterNot { overrideOf(it.id).hide }
        }.sortedByDescending { it.time }
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val tag = PeerStore.peers(this).joinToString("-") { it.device }.take(20)
        val de = DialogExportBinding.inflate(layoutInflater)
        de.tvExportTitle.text = getString(R.string.export_peer_title)
        de.tvExportCount.text = getString(R.string.export_count, list.size)
        de.tvExportCount.setTextColor(color(R.color.light_hint))

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(de.root)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun bytes(): Pair<String, ByteArray> = ExcelExporter.export(list, false, tag)

        de.rowSave.setOnClickListener {
            val (name, data) = bytes()
            pendingName = name
            pendingBytes = data
            dialog.dismiss()
            try {
                createDoc.launch(name)
            } catch (t: ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.export_failed, t.message), Toast.LENGTH_SHORT).show()
            }
        }
        de.rowShare.setOnClickListener {
            val (name, data) = bytes()
            dialog.dismiss()
            shareBytes(name, data)
        }
        dialog.show()
    }

    // ---------------- 导入 ----------------

    private fun importFrom(uri: Uri) {
        val zoneName = getString(if (hiddenZone) R.string.hidden_title else R.string.records_title)
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                try {
                    val tmp = File(cacheDir, "import_tmp.xlsx")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    if (!tmp.exists() || tmp.length() <= 0) null
                    else ExcelImporter.parse(tmp).also { tmp.delete() }
                } catch (t: Throwable) {
                    null
                }
            }
            if (parsed == null) {
                Toast.makeText(this@RecordsActivity, R.string.import_invalid, Toast.LENGTH_LONG).show()
                return@launch
            }

            // 当前区已有号码集合 → 统计重复
            val existing = RecordStore.all(this@RecordsActivity, hiddenZone)
                .map { it.phone }.toHashSet()
            val seen = HashSet<String>()
            val good = ArrayList<ExcelImporter.ParsedRow>()
            var dup = 0
            parsed.rows.forEach { r ->
                if (r.phone in existing || !seen.add(r.phone)) dup++ else good.add(r)
            }

            if (good.isEmpty()) {
                Toast.makeText(this@RecordsActivity, R.string.import_none, Toast.LENGTH_LONG).show()
                return@launch
            }

            val sb = StringBuilder()
            sb.append(getString(R.string.import_will, good.size)).append(" ｜ ")
                .append(getString(R.string.import_skip, dup + parsed.badRows.size, dup, parsed.badRows.size))
                .append('\n')
            if (parsed.badRows.isNotEmpty()) {
                val shown = parsed.badRows.take(8).joinToString("、")
                sb.append(getString(R.string.import_bad_rows, shown))
            }

            val msg = TextView(this@RecordsActivity).apply {
                text = sb.toString()
                textSize = 13f
                setPadding(24, 12, 24, 12)
            }
            MaterialAlertDialogBuilder(this@RecordsActivity)
                .setTitle(getString(R.string.import_title, zoneName))
                .setView(android.widget.ScrollView(this@RecordsActivity).apply { addView(msg) })
                .setPositiveButton(R.string.import_confirm) { _, _ ->
                    val (ok, skipped) = RecordStore.importBatch(
                        this@RecordsActivity, good, hiddenZone, existing
                    )
                    Toast.makeText(
                        this@RecordsActivity,
                        getString(R.string.import_done, ok),
                        Toast.LENGTH_SHORT
                    ).show()
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ---------------- 导出 ----------------

    private var pendingBytes: ByteArray? = null
    private var pendingName: String = ""

    private fun showExportMenu() {
        if (peerZone) { exportPeerData(); return }
        val list = RecordStore.all(this, hiddenZone)
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val de = DialogExportBinding.inflate(layoutInflater)
        val head = getString(if (hiddenZone) R.string.hidden_title else R.string.records_title)
        de.tvExportTitle.text = getString(R.string.export_title, head)
        de.tvExportCount.text = getString(R.string.export_count, list.size)
        de.tvExportCount.setTextColor(color(if (hiddenZone) R.color.dark_sub else R.color.light_hint))

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(de.root)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun bytes(): Pair<String, ByteArray> = ExcelExporter.export(list, hiddenZone)

        de.rowSave.setOnClickListener {
            val (name, data) = bytes()
            pendingName = name
            pendingBytes = data
            dialog.dismiss()
            try {
                createDoc.launch(name)
            } catch (t: ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.export_failed, t.message), Toast.LENGTH_SHORT).show()
            }
        }
        de.rowShare.setOnClickListener {
            val (name, data) = bytes()
            dialog.dismiss()
            shareBytes(name, data)
        }
        dialog.show()
    }

    private fun writeExport(uri: Uri) {
        val data = pendingBytes ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(data) }
            Toast.makeText(this, getString(R.string.export_ok, pendingName), Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.export_failed, t.message), Toast.LENGTH_SHORT).show()
        }
        pendingBytes = null
    }

    private fun shareBytes(name: String, data: ByteArray) {
        try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val f = File(dir, name)
            f.writeBytes(data)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, name))
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.export_failed, t.message), Toast.LENGTH_SHORT).show()
        }
    }
}
