package com.parkphone.app

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 零依赖的最小 .xlsx 生成器。
 * xlsx 本质是 zip 包内若干 XML，这里用 JDK 自带 ZipOutputStream 直接拼装，
 * 生成的文件 Excel / WPS / 手机表格应用均可打开。
 */
object ExcelExporter {

    private val DATE_FMT = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

    fun export(records: List<ParkRecord>, isHidden: Boolean): Pair<String, ByteArray> {
        val head = if (isHidden) "隐藏名单" else "号码记录"
        val name = head + "_" +
            SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date()) + ".xlsx"

        val sorted = records.sortedByDescending { it.time }
        val sb = StringBuilder()
        var row = 1

        // 表头（加粗样式 s="1"）
        val headers = listOf("标题", "日期", "号码", "拨打次数", "颜色标记", "备注")
        sb.append("<row r=\"").append(row).append("\">")
        headers.forEachIndexed { i, h ->
            cell(sb, i, row, "inlineStr", h, "1")
        }
        sb.append("</row>")
        row++

        val colorNames = listOf("蓝", "绿", "橙")
        sorted.forEach { r ->
            sb.append("<row r=\"").append(row).append("\">")
            cell(sb, 0, row, "inlineStr", esc(r.title))
            cell(sb, 1, row, "inlineStr", esc(DATE_FMT.format(Date(r.time))))
            cell(sb, 2, row, "inlineStr", esc(r.phone))
            sb.append("<c r=\"").append(colName(3)).append(row).append("\">")
                .append("<v>").append(r.callCount).append("</v></c>")
            cell(sb, 4, row, "s", if (r.color in 0..2) colorNames[r.color] else "无")
            cell(sb, 5, row, "s", esc(r.note))
            sb.append("</row>")
            row++
        }

        val sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="1" max="1" width="22" customWidth="1"/><col min="2" max="2" width="16" customWidth="1"/><col min="3" max="3" width="18" customWidth="1"/><col min="4" max="4" width="10" customWidth="1"/><col min="5" max="5" width="10" customWidth="1"/><col min="6" max="6" width="40" customWidth="1"/></cols><sheetData>$sb</sheetData></worksheet>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""

        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="号码记录" sheetId="1" r:id="rId1"/></sheets></workbook>"""

        val workbookRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

        // 两套样式：s=0 常规，s=1 加粗
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs></styleSheet>"""

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entry(z, "[Content_Types].xml", contentTypes)
            entry(z, "_rels/.rels", rels)
            entry(z, "xl/workbook.xml", workbook)
            entry(z, "xl/_rels/workbook.xml.rels", workbookRels)
            entry(z, "xl/styles.xml", styles)
            entry(z, "xl/worksheets/sheet1.xml", sheet)
        }
        return name to out.toByteArray()
    }

    private fun entry(z: ZipOutputStream, path: String, content: String) {
        z.putNextEntry(ZipEntry(path))
        z.write(content.toByteArray(Charsets.UTF_8))
        z.closeEntry()
    }

    private fun colName(i: Int) = ('A' + i).toString()

    /** 文本单元格：inline string，号码按文本存防止 Excel 吞前导 0 */
    private fun cell(sb: StringBuilder, col: Int, row: Int, type: String, text: String, style: String = "0") {
        sb.append("<c r=\"").append(colName(col)).append(row).append('"')
        if (style != "0") sb.append(" s=\"").append(style).append('"')
        sb.append(" t=\"").append(type).append("\"><is><t xml:space=\"preserve\">")
            .append(text).append("</t></is></c>")
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        // 去掉 XML1.0 非法控制字符
        .filter { it.code >= 0x20 || it == '\n' || it == '\t' }
}
