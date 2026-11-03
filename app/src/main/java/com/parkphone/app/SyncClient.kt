package com.parkphone.app

import android.util.Base64
import org.w3c.dom.Element
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 坚果云 WebDAV 客户端：Basic 鉴权 + AES-GCM 加密，零第三方依赖 */
object SyncClient {

    private val KEY: SecretKeySpec by lazy {
        SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(SyncConfig.PASSPHRASE.toByteArray()),
            "AES"
        )
    }

    private fun authHeader(): String =
        "Basic " + Base64.encodeToString(
            "${SyncConfig.USER}:${SyncConfig.APP_PWD}".toByteArray(), Base64.NO_WRAP
        )

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

    private fun open(method: String, url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setMethod(this, method)
            connectTimeout = SyncConfig.CONNECT_TIMEOUT_MS
            readTimeout = SyncConfig.READ_TIMEOUT_MS
            doInput = true
            setRequestProperty("Authorization", authHeader())
            setRequestProperty("User-Agent", "xiaozhao-sync")
        }

    /**
     * Java 标准 HttpURLConnection 的方法白名单不含 WebDAV 动词（PROPFIND/MKCOL），
     * setRequestMethod 会直接抛异常；按安卓 WebDAV 客户端通用做法反射设置。
     */
    private fun setMethod(conn: HttpURLConnection, method: String) {
        try {
            conn.setRequestMethod(method)
            return
        } catch (_: Throwable) {
        }
        var c: Class<*>? = conn.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField("method")
                f.isAccessible = true
                f.set(conn, method)
                return
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        throw IllegalStateException("不支持的方法: $method")
    }

    // ---------------- 诊断专用（临时，验证通过后随诊断页一起删除） ----------------

    /** 返回 HTTP 状态码与响应文本，供诊断页逐项报告失败原因 */
    fun raw(method: String, rel: String, body: String? = null): Pair<Int, String> = try {
        val conn = open(method, "https://dav.jianguoyun.com/dav/" + rel)
        if (method == "PROPFIND") conn.setRequestProperty("Depth", "0")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val txt = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        }.getOrDefault("")
        conn.disconnect()
        code to txt
    } catch (t: Throwable) {
        -1 to (t.message ?: t.javaClass.simpleName)
    }

    /** 上传一个加密快照文件；成功返回 true */
    fun put(name: String, cipherText: String): Boolean = runCatching {
        val conn = open("PUT", SyncConfig.BASE_URL + name + ".json")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(cipherText) }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..299
    }.getOrDefault(false)

    /** 下载一个文件；不存在或失败返回 null */
    fun get(name: String): String? = runCatching {
        val conn = open("GET", SyncConfig.BASE_URL + name + ".json")
        val code = conn.responseCode
        val body = if (code in 200..299)
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
        conn.disconnect()
        body
    }.getOrNull()

    /** 列出同步目录下的设备文件名（不含扩展名）；失败返回 null */
    fun list(): List<String>? = runCatching {
        val conn = open("PROPFIND", SyncConfig.BASE_URL.trimEnd('/'))
        conn.setRequestProperty("Depth", "1")
        conn.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
        val code = conn.responseCode
        if (code !in 200..299) { conn.disconnect(); return null }
        val xml = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
            it.readText()
        }
        conn.disconnect()
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }.newDocumentBuilder().parse(
            java.io.ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
        )
        val nodes = doc.getElementsByTagName("href")
        val out = LinkedHashSet<String>()
        for (i in 0 until nodes.length) {
            val h = nodes.item(i).textContent ?: continue
            if (!h.endsWith(".json")) continue
            val last = h.substringAfterLast('/')
            out.add(URLDecoder.decode(last.substringBeforeLast(".json"), "UTF-8"))
        }
        out.toList()
    }.getOrNull()
}
