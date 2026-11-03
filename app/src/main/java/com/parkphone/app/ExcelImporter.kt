package com.parkphone.app

import org.w3c.dom.Element
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 零依赖的最小 .xlsx 解析器。
 * 同时兼容：本 App 导出的内联字符串格式、Excel/WPS 手建表的共享字符串与数值单元格。
 */
object ExcelImporter {

    data class ParsedRow(
        val row: Int,          // 表格中的行号（1 基），仅用于提示
        val title: String,
        val phone: String,
        val time: Long,
        val callCount: Int,
        val color: Int,
        val note: String
    )

    class Parsed(val rows: List<ParsedRow>, val badRows: List<Int>)

    private const val R_NS =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** 返回 null 表示不是有效表格或找不到「号码」列 */
    fun parse(f: File): Parsed? {
        val zip = try { ZipFile(f) } catch (t: Throwable) { return null }
        try {
            val sheetName = resolveSheet(zip) ?: return null
            val shared = readShared(zip)
            val root = parseXml(zip, sheetName) ?: return null

            val rowNodes = root.getElementsByTagName("row")
            val all = ArrayList<Pair<Int, List<String>>>()
            for (i in 0 until rowNodes.length) {
                val row = rowNodes.item(i) as? Element ?: continue
                val num = row.getAttribute("r").toIntOrNull() ?: (all.size + 1)
                all.add(num to readCells(row, shared))
            }

            val headerIdx = all.indexOfFirst { p ->
                p.second.any { it.isNotBlank() } && phoneCol(p.second) >= 0
            }
            if (headerIdx < 0) return null
            val cols = headerCols(all[headerIdx].second)

            val rows = ArrayList<ParsedRow>()
            val bad = ArrayList<Int>()
            val now = System.currentTimeMillis()
            for (k in (headerIdx + 1) until all.size) {
                val (num, cells) = all[k]
                if (cells.all { it.isBlank() }) continue
                val phone = digitsOf(cellAt(cols.phone, cells))
                if (phone.isEmpty()) { bad.add(num); continue }
                val cRaw = cellAt(cols.count, cells).trim()
                rows.add(
                    ParsedRow(
                        row = num,
                        title = cellAt(cols.title, cells).trim(),
                        phone = phone,
                        time = parseTime(cellAt(cols.date, cells)) ?: now,
                        callCount = (cRaw.toDoubleOrNull()?.toInt()
                            ?: digitsOf(cRaw).toIntOrNull())?.coerceIn(0, 9999) ?: 0,
                        color = colorOf(cellAt(cols.color, cells)),
                        note = cellAt(cols.note, cells).trim()
                    )
                )
            }
            return Parsed(rows, bad)
        } catch (t: Throwable) {
            return null
        } finally {
            try { zip.close() } catch (_: Throwable) {}
        }
    }

    // ---------- 列定位 ----------

    private class Cols(
        val title: Int, val date: Int, val phone: Int,
        val count: Int, val color: Int, val note: Int
    )

    private fun phoneCol(cells: List<String>) =
        findCol(cells, "号码", "电话", "phone", "mobile", "tel")

    private fun headerCols(h: List<String>) = Cols(
        title = findCol(h, "标题", "位置", "地点"),
        date = findCol(h, "日期", "时间"),
        phone = phoneCol(h),
        count = findCol(h, "拨打", "次数"),
        color = findCol(h, "颜色", "标记"),
        note = findCol(h, "备注")
    )

    private fun findCol(cells: List<String>, vararg keys: String): Int {
        cells.forEachIndexed { i, s ->
            val low = s.lowercase(Locale.US)
            if (keys.any { low.contains(it.lowercase(Locale.US)) }) return i
        }
        return -1
    }

    private fun cellAt(i: Int, cells: List<String>) =
        if (i in cells.indices) cells[i] else ""

    private fun colorOf(s: String) = when {
        s.contains("蓝") -> 0
        s.contains("绿") -> 1
        s.contains("橙") -> 2
        else -> 3
    }

    /** 号码/次数通用清洗：科学计数法与 x.0 结尾的数字串先还原，再剔除所有非数字 */
    private fun digitsOf(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return ""
        if (t.contains('.') || t.contains('e') || t.contains('E')) {
            val d = t.toDoubleOrNull()
            if (d != null && d >= 0 && !d.isNaN()) return d.toLong().toString()
        }
        return t.filter { it.isDigit() }
    }

    private val DATE_RE = Regex("""(20\d{2})\D{1,2}(\d{1,2})\D{1,2}(\d{1,2})""")

    /** 支持 2026年11月3日 / 2026-11-3 / 2026/11/3 / Excel 日期序列号 */
    private fun parseTime(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        DATE_RE.find(t)?.let { m ->
            val (y, mo, d) = m.destructured
            val mi = mo.toIntOrNull() ?: return@let
            val di = d.toIntOrNull() ?: return@let
            val c = Calendar.getInstance()
            c.clear()
            c.set(y.toInt(), mi - 1, di, 12, 0, 0)
            return c.timeInMillis
        }
        t.toDoubleOrNull()?.let { n ->
            if (n in 20000.0..80000.0) return ((n - 25569.0) * 86400000.0).toLong()
        }
        return null
    }

    // ---------- zip / XML ----------

    private fun entryMap(zip: ZipFile): Map<String, String> {
        val m = HashMap<String, String>()
        val it = zip.entries()
        while (it.hasMoreElements()) {
            val n = it.nextElement().name.trimStart('/').replace('\\', '/')
            m[n.lowercase(Locale.US)] = n
        }
        return m
    }

    private fun resolveSheet(zip: ZipFile): String? {
        val m = entryMap(zip)
        val wb = parseXml(zip, m["xl/workbook.xml"])
        if (wb != null) {
            val sheets = wb.getElementsByTagName("sheet")
            if (sheets.length > 0) {
                val s = sheets.item(0) as Element
                val rid = s.getAttributeNS(R_NS, "id").ifBlank { s.getAttribute("r:id") }
                if (rid.isNotBlank()) {
                    val rels = parseXml(zip, m["xl/_rels/workbook.xml.rels"])
                    if (rels != null) {
                        val rl = rels.getElementsByTagName("Relationship")
                        for (i in 0 until rl.length) {
                            val r = rl.item(i) as Element
                            if (r.getAttribute("Id") == rid) {
                                var t = r.getAttribute("Target").trimStart('/')
                                    .replace('\\', '/')
                                if (!t.lowercase(Locale.US).startsWith("xl/")) t = "xl/$t"
                                m[t.lowercase(Locale.US)]?.let { return it }
                            }
                        }
                    }
                }
            }
        }
        return m["xl/worksheets/sheet1.xml"]
            ?: m.entries.firstOrNull { it.key.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }?.value
    }

    private fun readShared(zip: ZipFile): List<String> {
        val names = entryMap(zip)
        val root = parseXml(zip, names["xl/sharedstrings.xml"]) ?: return emptyList()
        val out = ArrayList<String>()
        val si = root.getElementsByTagName("si")
        for (i in 0 until si.length) {
            val e = si.item(i) as? Element ?: continue
            val ts = e.getElementsByTagName("t")
            val sb = StringBuilder()
            for (j in 0 until ts.length) sb.append(ts.item(j).textContent)
            out.add(sb.toString())
        }
        return out
    }

    private fun parseXml(zip: ZipFile, name: String?): Element? {
        if (name == null) return null
        return try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Throwable) {
            }
            zip.getInputStream(zip.getEntry(name)).use {
                f.newDocumentBuilder().parse(it).documentElement
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun readCells(row: Element, shared: List<String>): List<String> {
        val cs = row.getElementsByTagName("c")
        val map = HashMap<Int, String>()
        var max = -1
        for (i in 0 until cs.length) {
            val c = cs.item(i) as? Element ?: continue
            val idx = colIndex(c.getAttribute("r"))
            if (idx < 0 || idx > 4095) continue
            map[idx] = cellText(c, shared)
            if (idx > max) max = idx
        }
        if (max < 0) return emptyList()
        return MutableList(max + 1) { map[it] ?: "" }
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch.isLetter()) n = n * 26 + (ch.lowercaseChar() - 'a' + 1) else break
        }
        return n - 1
    }

    private fun cellText(c: Element, shared: List<String>): String {
        return when (c.getAttribute("t")) {
            "inlineStr" -> concatT(c)
            "s" -> {
                val v = firstV(c) ?: return ""
                val n = v.toIntOrNull() ?: return ""
                shared.getOrNull(n) ?: ""
            }
            else -> firstV(c) ?: concatT(c)
        }
    }

    private fun firstV(c: Element): String? {
        val l = c.getElementsByTagName("v")
        return if (l.length > 0) l.item(0).textContent else null
    }

    private fun concatT(c: Element): String {
        val l = c.getElementsByTagName("t")
        val sb = StringBuilder()
        for (i in 0 until l.length) sb.append(l.item(i).textContent)
        return sb.toString()
    }
}
