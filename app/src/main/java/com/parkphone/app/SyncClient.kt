package com.parkphone.app

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 坚果云 WebDAV 客户端：Basic 鉴权 + AES-GCM 加密。
 * 使用 OkHttp 并强制 HTTP/1.1：Android 默认栈走 HTTP/2 时，
 * 坚果云服务器会拒绝 PROPFIND/MKCOL 等 WebDAV 方法（返回 403），
 * HTTP/1.1 下同样的请求已验证通过。
 */
object SyncClient {

    private val KEY: SecretKeySpec by lazy {
        SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(SyncConfig.PASSPHRASE.toByteArray()),
            "AES"
        )
    }

    private val authValue = "Basic " + Base64.encodeToString(
        "${SyncConfig.USER}:${SyncConfig.APP_PWD}".toByteArray(), Base64.NO_WRAP
    )

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(SyncConfig.CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(SyncConfig.READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .build()

    // ---------------- 加密 ----------------

    /** 返回 Base64(IV + 密文) */
    fun encrypt(plain: String): String {
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, KEY, GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(text: String): String? = runCatching {
        val raw = Base64.decode(text.trim(), Base64.NO_WRAP)
        if (raw.size < 13) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE, KEY,
            GCMParameterSpec(128, raw.copyOfRange(0, 12))
        )
        String(c.doFinal(raw.copyOfRange(12, raw.size)))
    }.getOrNull()

    // ---------------- WebDAV ----------------

    private const val ROOT = "https://dav.jianguoyun.com/dav/"
    private const val DIR = "sync/"

    private fun exec(
        method: String, rel: String,
        body: String? = null, depth: String? = null
    ): Pair<Int, String> = try {
        val rb = Request.Builder()
            .url(ROOT + rel)
            .header("Authorization", authValue)
            .header("User-Agent", "xiaozhao-sync")
        if (depth != null) rb.header("Depth", depth)
        rb.method(method, body?.toRequestBody("text/plain".toMediaTypeOrNull()))
        client.newCall(rb.build()).execute().use { resp ->
            resp.code to (resp.body?.string() ?: "")
        }
    } catch (t: Throwable) {
        -1 to (t.message ?: t.javaClass.simpleName)
    }

    /** 上传一个加密快照文件；成功返回 true */
    fun put(name: String, cipherText: String): Boolean =
        exec("PUT", "$DIR$name.json", cipherText).first in 200..299

    /** 下载一个文件；不存在或失败返回 null */
    fun get(name: String): String? {
        val (code, body) = exec("GET", "$DIR$name.json")
        return if (code == 200) body else null
    }

    /** 删除一个文件（诊断清理用） */
    fun delete(name: String): Boolean =
        exec("DELETE", "$DIR$name.json").first in 200..299

    /** 列出同步目录下的设备文件名（不含扩展名）；失败返回 null */
    fun list(): List<String>? {
        val (code, xml) = exec("PROPFIND", "sync", depth = "1")
        if (code !in 200..299) return null
        return runCatching {
            val doc = DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val nodes = doc.documentElement.getElementsByTagName("href")
            val out = LinkedHashSet<String>()
            for (i in 0 until nodes.length) {
                val h = nodes.item(i).textContent ?: continue
                if (!h.endsWith(".json")) continue
                val last = h.substringAfterLast('/')
                out.add(URLDecoder.decode(last.substringBefore(".json"), "UTF-8"))
            }
            out.toList()
        }.getOrNull()
    }

    /** 建目录（坚果云不会自动创建，缺失时上传会失败） */
    fun mkdir(rel: String): Int = exec("MKCOL", rel).first

    // ---------------- 诊断专用（验证通过后随诊断页一起删除） ----------------

    /** 返回 HTTP 状态码与响应文本，供诊断页逐项报告失败原因；rel 从网盘根算起 */
    fun raw(method: String, rel: String, body: String? = null): Pair<Int, String> =
        exec(method, rel, body, if (method == "PROPFIND") "0" else null)
}
