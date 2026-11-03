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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.parkphone.app.databinding.ActivityRecordsBinding
import com.parkphone.app.databinding.DialogExportBinding
import com.parkphone.app.databinding.DialogNoteBinding
import com.parkphone.app.databinding.DialogPasswordBinding
import com.parkphone.app.databinding.ItemGroupHeaderBinding
import com.parkphone.app.databinding.ItemRecordBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordsBinding
    private val adapter = RecAdapter()

    /** false=正常区（浅色），true=隐藏区（深色） */
    private var hiddenZone = false

    // 连点标题计数
    private var tapCount = 0
    private var lastTapAt = 0L

    private val COLOR_RES = intArrayOf(R.color.mark_blue, R.color.mark_green, R.color.mark_orange)

    private val createDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
            if (uri != null) writeExport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.btnBack.setOnClickListener {
            if (hiddenZone) exitHidden() else finish()
        }
        binding.btnExport.setOnClickListener { showExportMenu() }
        binding.tvHead.setOnClickListener { onHeadTap() }

        applyTheme()
        refresh()
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
        binding.tvEmpty.setTextColor(color(if (hiddenZone) R.color.dark_sub else 0xFF999999.toInt()))
        binding.tvEmpty.setText(if (hiddenZone) R.string.hidden_empty else R.string.records_empty)
        binding.tvHead.text = getString(if (hiddenZone) R.string.hidden_title else R.string.records_title)
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    // ---------------- 数据 ----------------

    private data class Row(val isHeader: Boolean, val headerTitle: String = "", val headerTime: Long = 0, val rec: ParkRecord? = null)

    private var rows: List<Row> = emptyList()

    private fun refresh() {
        val data = RecordStore.all(this, hiddenZone)
        val fmt = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
        val dayFmt = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
        val today = dayFmt.format(Date())
        val grouped = LinkedHashMap<String, MutableList<ParkRecord>>()
        data.sortedByDescending { it.time }.forEach { r ->
            val day = fmt.format(Date(r.time))
            val key = r.title + "｜" + if (day == today) "$day（今天）" else day
            grouped.getOrPut(key) { mutableListOf() }.add(r)
        }
        val out = mutableListOf<Row>()
        grouped.forEach { (key, list) ->
            out.add(Row(true, key, list.first().time))
            list.forEach { out.add(Row(false, rec = it)) }
        }
        rows = out
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
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

        fun onKey(k: String) {
            when (k) {
                "del" -> if (pendingPwd.isNotEmpty()) pendingPwd = pendingPwd.dropLast(1)
                "clear" -> pendingPwd = ""
                else -> if (pendingPwd.length < 4) pendingPwd += k
            }
            dp.tvPwdMsg.text = ""
            render()
            if (pendingPwd.length == 4) handle()
        }

        fun handle() {
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
                is RecVH -> rows[position].rec?.let { holder.bind(it) }
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

        fun bind(rec: ParkRecord) {
            val dark = hiddenZone
            b.tvPhone.text = rec.phone
            b.tvPhone.setTextColor(color(if (dark) R.color.dark_text else R.color.light_text))
            b.tvCount.text = getString(R.string.call_count, rec.callCount)
            b.tvCount.setTextColor(color(if (dark) R.color.dark_sub else 0xFF888888.toInt()))

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
            bindDot(b.dotBlue, 0, rec)
            bindDot(b.dotGreen, 1, rec)
            bindDot(b.dotOrange, 2, rec)

            // 备注行
            if (rec.note.isNotBlank()) {
                b.tvNote.visibility = View.VISIBLE
                b.tvNote.text = getString(R.string.note_display, rec.note)
                b.tvNote.setTextColor(color(if (dark) R.color.dark_sub else 0xFF666666.toInt()))
            } else b.tvNote.visibility = View.GONE

            // 恢复按钮仅在隐藏区显示
            b.btnRestore.visibility = if (dark) View.VISIBLE else View.GONE
            b.btnRestore.setTextColor(color(R.color.dark_group_text))
            b.btnRestore.setOnClickListener {
                RecordStore.setHidden(this@RecordsActivity, rec.id, false)
                Toast.makeText(this@RecordsActivity, R.string.restored, Toast.LENGTH_SHORT).show()
                refresh()
            }

            b.btnNote.setTextColor(color(if (dark) R.color.dark_group_text else R.color.mark_blue))
            b.btnNote.setOnClickListener { showNoteDialog(rec) }
            b.btnDial.setOnClickListener { dial(rec) }
            b.btnDelete.setOnClickListener { confirmDelete(rec) }

            // 长按 3 秒 → 隐藏/恢复菜单
            b.root.setOnTouchListener(LongPress3(rec))
        }

        private fun bindDot(dot: View, idx: Int, rec: ParkRecord) {
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
                RecordStore.setColor(this@RecordsActivity, rec.id, idx)
                refresh()
            }
        }
    }

    /** 按住满 3000ms 触发；提前抬起或移动过多则取消 */
    private inner class LongPress3(val rec: ParkRecord) : View.OnTouchListener {
        private val handler = Handler(Looper.getMainLooper())
        private var startX = 0f
        private var startY = 0f
        private var fired = false
        private val timer = Runnable {
            fired = true
            showLongPressMenu(rec)
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
                    return fired
                }
            }
            return false
        }
    }

    private fun showLongPressMenu(rec: ParkRecord) {
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

    private fun dial(rec: ParkRecord) {
        RecordStore.addCall(this, rec.id)
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

    // ---------------- 备注（含可编辑快捷标签） ----------------

    private fun showNoteDialog(rec: ParkRecord) {
        val dn = DialogNoteBinding.inflate(layoutInflater)
        dn.tvNoteSub.text = getString(R.string.note_sub, rec.phone, rec.title)
        dn.etNote.setText(rec.note)
        buildTags(dn, rec)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.note_title)
            .setView(dn.root)
            .setPositiveButton(R.string.save) { _, _ ->
                RecordStore.setNote(this, rec.id, dn.etNote.text.toString().trim())
                Toast.makeText(this, R.string.note_saved, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    // ---------------- 导出 ----------------

    private var pendingBytes: ByteArray? = null
    private var pendingName: String = ""

    private fun showExportMenu() {
        val list = RecordStore.all(this, hiddenZone)
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val de = DialogExportBinding.inflate(layoutInflater)
        val head = getString(if (hiddenZone) R.string.hidden_title else R.string.records_title)
        de.tvExportTitle.text = getString(R.string.export_title, head)
        de.tvExportCount.text = getString(R.string.export_count, list.size)
        de.tvExportCount.setTextColor(color(if (hiddenZone) R.color.dark_sub else 0xFF999999.toInt()))

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
