package com.parkphone.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
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
import org.json.JSONObject

/**
 * 临时同步诊断页：验证通过后连同本页、入口按钮与 SyncClient.raw 一起删除。
 * 发送诊断：登录 → 目录 → 真实上传本机普通区 → 回读校验；
 * 拉取诊断：列出网盘设备并逐台试拉解密。
 */
class SyncDiagActivity : AppCompatActivity() {

    private lateinit var result: TextView
    @Volatile private var lastText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun p(v: Int) = (v * d).toInt()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(16), p(16), p(16), p(16))
        }
        box.addView(TextView(this).apply {
            text = "同步诊断（临时页）"
            textSize = 18f
            setTextColor(ContextCompat.getColor(this@SyncDiagActivity, R.color.light_text))
        })
        box.addView(TextView(this).apply {
            text = "当前设备名：${SyncEngine.deviceName(this@SyncDiagActivity)}\n" +
                "本机角色：${if (SyncEngine.isReceiver(this@SyncDiagActivity)) "接收端" else "上传端"}\n\n" +
                "点「开始诊断」会真实上传本机「号码记录」普通区全部记录（隐藏区不会），" +
                "并列出网盘上所有设备逐一验证拉取。全程结果可复制。"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@SyncDiagActivity, R.color.light_sub))
            setPadding(0, p(8), 0, p(8))
        })
        val start = Button(this).apply { text = "开始诊断" }
        box.addView(start)
        result = TextView(this).apply {
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(this@SyncDiagActivity, R.color.light_text))
            setTextIsSelectable(true)
            setPadding(0, p(12), 0, p(12))
        }
        box.addView(result)
        val copy = Button(this).apply { text = "复制结果" }
        box.addView(copy)
        setContentView(ScrollView(this).apply { addView(box) })

        start.setOnClickListener { runDiag() }
        copy.setOnClickListener {
            if (lastText.isBlank()) {
                Toast.makeText(this, "还没有结果", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("diag", lastText))
            Toast.makeText(this, "已复制，粘贴发我即可", Toast.LENGTH_SHORT).show()
        }
    }

    private fun line(s: String) {
        lastText = if (lastText.isEmpty()) s else "$lastText\n$s"
        runOnUiThread { result.text = lastText }
    }

    private fun runDiag() {
        lastText = ""
        result.text = ""
        val ctx = applicationContext
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()

                // ① 登录与凭据
                val (c1, m1) = SyncClient.raw("PROPFIND", "")
                line("① 登录与凭据：" + when (c1) {
                    207 -> "通过"
                    401 -> "不通过：账号或应用密码错误"
                    -1 -> "不通过：无法连接服务器（$m1）"
                    else -> "不通过：HTTP $c1"
                })
                if (c1 != 207) { line("—— 凭据不通，后续步骤中止 ——"); return@withContext }

                // ② sync 目录
                val (c2, _) = SyncClient.raw("PROPFIND", "sync")
                if (c2 == 207) line("② sync 目录：已存在")
                else {
                    val (c2b, _) = SyncClient.raw("MKCOL", "sync")
                    line("② sync 目录：" + when {
                        c2b == 201 || c2b == 405 -> "原本缺失，已自动创建，通过"
                        else -> "自动创建失败：HTTP $c2b"
                    })
                }

                // ③ 真实上传本机普通区快照
                val name = SyncEngine.deviceName(ctx)
                val plain = SyncEngine.snapshot(ctx)
                val count = runCatching {
                    JSONObject(plain).getJSONArray("records").length()
                }.getOrDefault(-1)
                val (c3, m3) = SyncClient.raw(
                    "PUT", "sync/$name.json", SyncClient.encrypt(plain)
                )
                line("③ 上传 $count 条记录 → sync/$name.json：" + when {
                    c3 in 200..299 -> "成功"
                    c3 == -1 -> "失败：$m3"
                    else -> "失败：HTTP $c3"
                })

                // ④ 回读校验
                if (c3 in 200..299) {
                    val (c4, body) = SyncClient.raw("GET", "sync/$name.json")
                    val back = if (c4 == 200) SyncClient.decrypt(body) else null
                    val n2 = runCatching {
                        JSONObject(back ?: "{}").getJSONArray("records").length()
                    }.getOrDefault(-1)
                    line("④ 回读校验：" + when {
                        c4 != 200 -> "失败：HTTP $c4"
                        back == null -> "失败：无法解密（口令不一致或内容损坏）"
                        n2 == count -> "通过（$n2 条一致）"
                        else -> "警告：上传 $count 条、回读 $n2 条"
                    })
                } else line("④ 回读校验：跳过（上传未成功）")

                // ⑤ 拉取诊断：网盘设备列表
                val devs = SyncClient.list()
                line("⑤ 网盘设备列表：" + when {
                    devs == null -> "读取失败（网络或目录问题）"
                    devs.isEmpty() -> "（空，还没有设备上传过）"
                    else -> devs.joinToString("、")
                })

                // ⑥ 逐台试拉解密（跳过本机自己那份）
                val others = devs?.filter { it != name }.orEmpty()
                if (others.isEmpty()) {
                    line("⑥ 其他设备试拉：暂无可测设备")
                } else others.forEach { dev ->
                    val rawBody = SyncClient.get(dev)
                    val plain2 = rawBody?.let { SyncClient.decrypt(it) }
                    val n3 = runCatching {
                        JSONObject(plain2 ?: "{}").getJSONArray("records").length()
                    }.getOrDefault(-1)
                    line("⑥ 拉取 $dev：" + when {
                        rawBody == null -> "失败：下载不到"
                        plain2 == null -> "失败：解密不成功（口令不一致）"
                        else -> "通过（$n3 条）"
                    })
                }

                line("—— 全部完成，耗时 ${(System.currentTimeMillis() - t0) / 1000.0} 秒 ——")
            }
        }
    }
}
