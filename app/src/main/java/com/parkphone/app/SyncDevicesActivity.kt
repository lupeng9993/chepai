package com.parkphone.app

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 选择发送端页（仅接收端可见，入口在隐藏区 114 记录的备注里）。
 * 列出网盘 sync 目录下所有设备快照，勾选后保存并立即拉取一次。
 */
class SyncDevicesActivity : AppCompatActivity() {

    private val checks = LinkedHashMap<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (18 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.dev_pick_title)
            textSize = 18f
            setTextColor(ContextCompat.getColor(this@SyncDevicesActivity, R.color.light_text))
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        })
        val status = TextView(this).apply {
            text = getString(R.string.dev_loading)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@SyncDevicesActivity, R.color.light_sub))
        }
        box.addView(status)
        val listWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(listWrap)

        box.addView(Button(this).apply {
            text = getString(R.string.dev_save_pull)
            setOnClickListener { saveAndPull() }
        })

        setContentView(ScrollView(this).apply { addView(box) })
        loadDevices(status, listWrap)
    }

    private fun loadDevices(status: TextView, listWrap: LinearLayout) {
        lifecycleScope.launch {
            val devices = withContext(Dispatchers.IO) {
                runCatching { SyncClient.list() }.getOrNull()
            }
            if (devices == null) {
                status.text = getString(R.string.dev_load_fail)
                return@launch
            }
            if (devices.isEmpty()) {
                status.text = getString(R.string.dev_empty)
                return@launch
            }
            status.text = getString(R.string.dev_hint)
            val selected = SyncEngine.selectedDevices(this@SyncDevicesActivity).toHashSet()
            devices.forEach { dev ->
                val cb = CheckBox(this@SyncDevicesActivity).apply {
                    text = dev
                    isChecked = dev in selected
                    textSize = 15f
                }
                checks[dev] = cb
                listWrap.addView(cb)
            }
        }
    }

    private fun saveAndPull() {
        val chosen = checks.filterValues { it.isChecked }.keys.toList()
        SyncEngine.setSelectedDevices(this, chosen)
        if (chosen.isEmpty()) {
            Toast.makeText(this, R.string.dev_none_chosen, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Toast.makeText(this, R.string.sync_downloading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching { SyncEngine.pullAll(this@SyncDevicesActivity) }.getOrDefault(-1)
            }
            Toast.makeText(
                this@SyncDevicesActivity,
                if (n < 0) getString(R.string.sync_pull_fail)
                else getString(R.string.sync_pull_ok, n),
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }
}
