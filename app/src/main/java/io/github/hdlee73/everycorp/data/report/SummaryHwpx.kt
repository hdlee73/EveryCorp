package io.github.hdlee73.everycorp.data.report

import io.github.hdlee73.everycorp.data.api.dto.AuditReportDto
import io.github.hdlee73.everycorp.data.api.dto.FinTableDto
import io.github.hdlee73.everycorp.ui.common.Fmt
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 반기·분기 검토보고서는 빼고 결산(기말) 감사보고서만 남긴다. PDF·HWPX 공통. */
internal fun yearEndAuditReports(reports: List<AuditReportDto>): List<AuditReportDto> =
    reports.filter { it.kind == "AUDIT" && it.periodLabel.endsWith("결산") }

/**
 * 요약을 한글(HWPX) 문서로 만든다. PDF 요약본과 같은 구성(제목·구분선 있는 소제목·표·주가 그래프)으로 만든다.
 * 글꼴·문단·테두리 정의는 assets/hwpx/header.xml(python-hwpx 의 Skeleton, Apache-2.0 에서 가져와 모양을 더함)을 쓴다.
 * 모양 번호: 글자 0 본문 · 8 굵게 · 9 회색 작은 글 · 10 기업명 · 11 소제목 · 12 표 본문,
 * 문단 20 본문 · 21 표 안 오른쪽 · 22 표 안 왼쪽 · 23 소제목(아래 구분선) · 24 제목 아래 작은 글, 테두리 3 구분선 · 4 표 줄.
 * [asset]은 assets/hwpx/ 아래 파일 이름 → 내용.
 */
class SummaryHwpxWriter(private val asset: (String) -> ByteArray) {
    private companion object {
        const val PAGE_W = 48190   // 본문 폭(HWPUNIT). section0_head.xml 의 쪽 여백과 맞춘다.
        const val C_BODY = 0; const val C_BOLD = 8; const val C_GRAY = 9; const val C_TITLE = 10; const val C_HEAD = 11; const val C_CELL = 12
        const val P_BODY = 20; const val P_RIGHT = 21; const val P_LEFT = 22; const val P_HEAD = 23; const val P_SUB = 24
        const val B_NONE = 1; const val B_HEAD_RULE = 3; const val B_ROW_RULE = 4
    }

    private class Cell(val text: String, val right: Boolean = false, val charPr: Int = C_CELL, val border: Int = B_NONE)

    private var nextId = 1_000
    private val body = StringBuilder()
    private var chartRef: ByteArray? = null
    private var first = true

    fun write(input: SummaryPdfInput, out: OutputStream, chartPng: ByteArray? = null) = writeDocument(listOf(input to chartPng), out)

    fun writeAll(inputs: List<SummaryPdfInput>, out: OutputStream) = writeDocument(inputs.map { it to null }, out)

    private fun writeDocument(inputs: List<Pair<SummaryPdfInput, ByteArray?>>, out: OutputStream) {
        body.setLength(0); nextId = 1_000; first = true; chartRef = null
        inputs.forEachIndexed { i, (input, png) ->
            if (i > 0) para(P_BODY, C_BODY, "")
            build(input, png)
        }
        val title = inputs.firstOrNull()?.first?.corpName.orEmpty().let { if (inputs.size > 1) "$it 외 ${inputs.size - 1}곳" else it }
        zip(title, out)
    }

    // ───── XML 조각 ─────

    private fun esc(s: String) = buildString {
        for (c in s) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c.code < 0x20 && c != '\t' -> Unit   // XML 에 쓸 수 없는 제어문자
                else -> append(c)
            }
        }
    }

    private fun run(charPr: Int, text: String) = "<hp:run charPrIDRef=\"$charPr\">" + (if (text.isEmpty()) "<hp:t/>" else "<hp:t>${esc(text)}</hp:t>") + "</hp:run>"

    private fun pOpen(paraPr: Int) = "<hp:p id=\"${nextId++}\" paraPrIDRef=\"$paraPr\" styleIDRef=\"0\" pageBreak=\"0\" columnBreak=\"0\" merged=\"0\">"

    /** 문단 하나. 맨 처음 문단은 쪽 설정이 든 기본 문단에 이어 붙인다. */
    private fun para(paraPr: Int, charPr: Int, text: String) {
        if (first) { first = false; body.append(run(charPr, text)).append("</hp:p>"); return }
        body.append(pOpen(paraPr)).append(run(charPr, text)).append("</hp:p>")
    }

    private fun lines(paraPr: Int, charPr: Int, text: String) { text.split('\n').forEach { para(paraPr, charPr, it) } }

    private fun cellXml(c: Cell, col: Int, row: Int, width: Int): String =
        "<hp:tc name=\"\" header=\"0\" hasMargin=\"0\" protect=\"0\" editable=\"0\" dirty=\"0\" borderFillIDRef=\"${c.border}\">" +
            "<hp:subList id=\"\" textDirection=\"HORIZONTAL\" lineWrap=\"BREAK\" vertAlign=\"CENTER\" linkListIDRef=\"0\" linkListNextIDRef=\"0\" textWidth=\"0\" textHeight=\"0\" hasTextRef=\"0\" hasNumRef=\"0\">" +
            pOpen(if (c.right) P_RIGHT else P_LEFT) + run(c.charPr, c.text) + "</hp:p></hp:subList>" +
            "<hp:cellAddr colAddr=\"$col\" rowAddr=\"$row\"/><hp:cellSpan colSpan=\"1\" rowSpan=\"1\"/><hp:cellSz width=\"$width\" height=\"1000\"/>" +
            "<hp:cellMargin left=\"300\" right=\"300\" top=\"100\" bottom=\"100\"/></hp:tc>"

    /** 표. [widths]는 칸 너비(합이 본문 폭). 첫 문단이면 안 되므로 앞에 빈 문단을 둔다. */
    private fun table(widths: List<Int>, rows: List<List<Cell>>) {
        if (rows.isEmpty()) return
        if (first) para(P_BODY, C_BODY, "")
        val total = widths.sum()
        val sb = StringBuilder()
        sb.append(pOpen(P_BODY)).append("<hp:run charPrIDRef=\"0\">")
        sb.append("<hp:tbl id=\"${nextId++}\" zOrder=\"0\" numberingType=\"TABLE\" textWrap=\"TOP_AND_BOTTOM\" textFlow=\"BOTH_SIDES\" lock=\"0\" dropcapstyle=\"None\" pageBreak=\"CELL\" repeatHeader=\"0\" rowCnt=\"${rows.size}\" colCnt=\"${widths.size}\" cellSpacing=\"0\" borderFillIDRef=\"$B_NONE\" noAdjust=\"0\">")
        sb.append("<hp:sz width=\"$total\" widthRelTo=\"ABSOLUTE\" height=\"${rows.size * 1000}\" heightRelTo=\"ABSOLUTE\" protect=\"0\"/>")
        sb.append("<hp:pos treatAsChar=\"1\" affectLSpacing=\"0\" flowWithText=\"1\" allowOverlap=\"0\" holdAnchorAndSO=\"0\" vertRelTo=\"PARA\" horzRelTo=\"COLUMN\" vertAlign=\"TOP\" horzAlign=\"LEFT\" vertOffset=\"0\" horzOffset=\"0\"/>")
        sb.append("<hp:outMargin left=\"0\" right=\"0\" top=\"0\" bottom=\"0\"/><hp:inMargin left=\"300\" right=\"300\" top=\"100\" bottom=\"100\"/>")
        rows.forEachIndexed { r, cells ->
            sb.append("<hp:tr>")
            widths.forEachIndexed { c, w -> sb.append(cellXml(cells.getOrElse(c) { Cell("") }, c, r, w)) }
            sb.append("</hp:tr>")
        }
        sb.append("</hp:tbl></hp:run></hp:p>")
        body.append(sb)
    }

    /** 가로로 꽉 차게 그림 한 장(BinData/BIN0001.png). [h]는 너비 [PAGE_W] 기준 높이. */
    private fun picture(h: Int) {
        if (first) para(P_BODY, C_BODY, "")
        val id = nextId++
        body.append(pOpen(P_BODY)).append("<hp:run charPrIDRef=\"0\">")
        body.append("<hp:pic textWrap=\"SQUARE\" textFlow=\"BOTH_SIDES\" reverse=\"0\" id=\"$id\" zOrder=\"0\" numberingType=\"PICTURE\" lock=\"0\" dropcapstyle=\"None\" href=\"\" groupLevel=\"0\" instid=\"$id\">")
        body.append("<hp:offset x=\"0\" y=\"0\"/><hp:orgSz width=\"$PAGE_W\" height=\"$h\"/><hp:curSz width=\"$PAGE_W\" height=\"$h\"/><hp:flip horizontal=\"0\" vertical=\"0\"/>")
        body.append("<hp:rotationInfo angle=\"0\" centerX=\"${PAGE_W / 2}\" centerY=\"${h / 2}\" rotateimage=\"1\"/>")
        body.append("<hp:renderingInfo><hc:transMatrix e1=\"1\" e2=\"0\" e3=\"0\" e4=\"0\" e5=\"1\" e6=\"0\"/><hc:scaMatrix e1=\"1\" e2=\"0\" e3=\"0\" e4=\"0\" e5=\"1\" e6=\"0\"/><hc:rotMatrix e1=\"1\" e2=\"0\" e3=\"0\" e4=\"0\" e5=\"1\" e6=\"0\"/></hp:renderingInfo>")
        body.append("<hp:imgRect><hc:pt0 x=\"0\" y=\"0\"/><hc:pt1 x=\"$PAGE_W\" y=\"0\"/><hc:pt2 x=\"$PAGE_W\" y=\"$h\"/><hc:pt3 x=\"0\" y=\"$h\"/></hp:imgRect>")
        body.append("<hp:imgClip left=\"0\" right=\"$PAGE_W\" top=\"0\" bottom=\"$h\"/><hp:inMargin left=\"0\" right=\"0\" top=\"0\" bottom=\"0\"/><hp:imgDim dimwidth=\"$PAGE_W\" dimheight=\"$h\"/>")
        body.append("<hc:img binaryItemIDRef=\"BIN0001\" bright=\"0\" contrast=\"0\" effect=\"REAL_PIC\" alpha=\"0\"/><hp:effects/>")
        body.append("<hp:sz width=\"$PAGE_W\" widthRelTo=\"ABSOLUTE\" height=\"$h\" heightRelTo=\"ABSOLUTE\" protect=\"0\"/>")
        body.append("<hp:pos treatAsChar=\"1\" affectLSpacing=\"0\" flowWithText=\"1\" allowOverlap=\"0\" holdAnchorAndSO=\"0\" vertRelTo=\"PARA\" horzRelTo=\"COLUMN\" vertAlign=\"TOP\" horzAlign=\"LEFT\" vertOffset=\"0\" horzOffset=\"0\"/>")
        body.append("<hp:outMargin left=\"0\" right=\"0\" top=\"0\" bottom=\"0\"/><hp:shapeComment/></hp:pic></hp:run></hp:p>")
    }

    // ───── 내용(PDF 요약본과 같은 순서) ─────

    private fun build(input: SummaryPdfInput, chartPng: ByteArray?) {
        val ov = input.overview
        para(P_SUB, C_TITLE, input.corpName)
        para(P_SUB, C_GRAY, listOfNotNull(input.badge.ifBlank { null }, ov?.stockCode, "작성 ${input.generatedAt}").joinToString(" · "))
        var no = 0
        fun heading(sec: SummarySection, extra: String = "") = para(P_HEAD, C_HEAD, "${++no}. ${sec.label}$extra")
        fun note(text: String) = lines(P_BODY, C_GRAY, text)
        fun kv(pairs: List<Pair<String, String?>>) {
            val rows = pairs.filter { !it.second.isNullOrBlank() }.map { (k, v) -> listOf(Cell(k, charPr = C_GRAY), Cell(v.orEmpty())) }
            table(listOf(9000, PAGE_W - 9000), rows)
        }
        fun finTable(t: FinTableDto) {
            if (t.columns.isEmpty() || t.rows.isEmpty()) return
            val n = t.columns.size
            val labelW = 12000
            val colW = (PAGE_W - labelW) / n
            val widths = listOf(labelW) + List(n) { colW }
            val head = listOf(Cell("", border = B_HEAD_RULE)) + t.columns.map { Cell(listOfNotNull(it.label, it.sub).joinToString(" "), right = true, charPr = C_GRAY, border = B_HEAD_RULE) }
            val data = t.rows.map { r ->
                listOf(Cell(r.label, border = B_ROW_RULE)) + (0 until n).map { i ->
                    val v = r.values.getOrNull(i)
                    Cell(if (r.unit == "%") Fmt.percent(v) else Fmt.eok(v), right = true, border = B_ROW_RULE)
                }
            }
            table(widths, listOf(head) + data)
        }
        val want = input.sections

        if (SummarySection.Overview in want) {
            heading(SummarySection.Overview)
            if (ov == null) note("기업개황을 불러오지 못했습니다.") else kv(listOf(
                "대표이사" to ov.ceoName,
                "설립일" to ov.established?.let { Fmt.date(it) },
                "결산월" to ov.fiscalMonth?.let { "${it.toIntOrNull() ?: it}월" },
                "주소" to ov.address, "홈페이지" to ov.homepage, "전화" to ov.phone,
                "최대주주" to ov.largestShareholder.display.ifBlank { null },
            ))
        }
        if (SummarySection.Financials in want) {
            val f = ov?.financials
            val tables = f?.tables
            if (f != null && f.available && tables != null) {
                heading(SummarySection.Financials, " (${f.fsLabel.orEmpty()}, 단위 ${tables.unit})")
                para(P_BODY, C_BOLD, "재무상태"); finTable(tables.balance)
                para(P_BODY, C_BOLD, "손익"); finTable(tables.income)
                f.latestBasis?.let { note("기준: ${it.reportName} · 출처 DART 정기보고서") }
            } else {
                heading(SummarySection.Financials)
                note(f?.message ?: "재무정보가 없습니다.")
            }
        }
        if (SummarySection.Debt in want) {
            heading(SummarySection.Debt)
            val d = input.debt
            if (d == null) note("채권 정보를 불러오지 못했습니다.")
            else if (!d.available) note(d.message ?: "채권 잔액 정보가 없습니다.")
            else {
                if (d.unitNote.isNotBlank()) note(d.unitNote)
                for (s in d.sections) {
                    para(P_BODY, C_BOLD, "${s.title}  합계 ${s.totalDisplay ?: Fmt.number(s.total)}")
                    for (r in s.rows) {
                        val buckets = r.buckets.filter { it.amount != null }.joinToString(" · ") { "${it.label} ${Fmt.number(it.amount)}" }
                        para(P_BODY, C_CELL, "  ${r.label}: ${Fmt.number(r.total)}" + if (buckets.isNotEmpty()) "  ($buckets)" else "")
                    }
                }
                if (d.missing.isNotEmpty()) note("잔액 표 없음: ${d.missing.joinToString(", ")}")
                if (d.source.isNotBlank()) note("출처: ${d.source}")
            }
        }
        if (SummarySection.Stock in want) {
            heading(SummarySection.Stock)
            val q = input.quote
            if (q == null && chartPng == null) {
                note(if (ov?.isListed == false) "비상장 기업이라 주가 정보가 없습니다." else "주가 정보를 불러오지 못했습니다.")
            } else {
                q?.let {
                    kv(listOf(
                        "현재가" to "${Fmt.price(it.price)}원  ${Fmt.change(it.change, it.changeRate, it.direction)}",
                        "시가/고가/저가" to "${Fmt.price(it.open)} / ${Fmt.price(it.high)} / ${Fmt.price(it.low)}",
                        "거래량" to Fmt.number(it.volume),
                        "52주 최고/최저" to "${Fmt.price(it.week52High)} / ${Fmt.price(it.week52Low)}",
                        "PER / PBR" to if (it.per != null || it.pbr != null) "${it.per?.let { v -> String.format(java.util.Locale.US, "%.2f", v) } ?: "-"} / ${it.pbr?.let { v -> String.format(java.util.Locale.US, "%.2f", v) } ?: "-"}" else null,
                    ))
                }
                if (chartPng != null) { chartRef = chartPng; picture(PAGE_W * CHART_H / CHART_W) }
            }
        }
        if (SummarySection.Audit in want) {
            heading(SummarySection.Audit, " (연결·별도)")
            val all = input.auditReports
            val ar = all?.let(::yearEndAuditReports)
            if (all == null) note("감사보고서 정보를 불러오지 못했습니다.")
            else if (ar.isNullOrEmpty()) note("조회 기간 내 기말(결산) 감사보고서가 없습니다.")
            else {
                val widths = listOf(8000, 4500, 5500, 16000, PAGE_W - 34000)
                val head = listOf("기간", "구분", "재무제표", "감사(검토)인", "의견").map { Cell(it, charPr = C_GRAY, border = B_HEAD_RULE) }
                val data = ar.map { r ->
                    listOf(r.periodLabel.ifBlank { r.period }, "감사", if (r.consolidated) "연결" else "별도", r.auditor.ifBlank { "-" }, r.opinion.ifBlank { "-" }).map { Cell(it, border = B_ROW_RULE) }
                }
                table(widths, listOf(head) + data)
                note("출처: DART 정기보고서·감사보고서. 반기·분기 검토는 제외하고 결산(기말) 기준만 넣었습니다.")
            }
        }
        para(P_BODY, C_BODY, "")
        note("제공 정보는 지연되거나 오류가 있을 수 있으며 투자 판단의 책임은 이용자에게 있습니다. 출처: DART, 한국거래소/키움증권/네이버 금융, 공공데이터포털.")
        note("${input.appName} v${input.versionName} · 만든이 ${input.author}")
    }

    // ───── 패키지 ─────

    private fun section(): String = String(asset("section0_head.xml"), Charsets.UTF_8) + body + "</hs:sec>"

    private fun contentHpf(title: String, now: String, withImage: Boolean): String {
        var src = String(asset("content.hpf"), Charsets.UTF_8)
            .replace("<opf:title/>", "<opf:title>${esc(title)}</opf:title>")
            .replace("synthetic-fixture-author", "Every Corp")
            .replace(Regex("(CreatedDate\" content=\"text\">)[^<]*"), "$1$now")
            .replace(Regex("(ModifiedDate\" content=\"text\">)[^<]*"), "$1$now")
        if (withImage) src = src.replace("</opf:manifest>", "<opf:item id=\"BIN0001\" href=\"BinData/BIN0001.png\" media-type=\"image/png\" isEmbeded=\"1\"/></opf:manifest>")
        return src
    }

    private fun zip(title: String, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            // mimetype 은 첫 항목이고 압축하지 않는다(한글이 파일 형식을 알아보는 규칙).
            val mime = asset("mimetype.txt")
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = mime.size.toLong()
                crc = CRC32().also { it.update(mime) }.value
            })
            z.write(mime); z.closeEntry()
            fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
            val now = java.time.Instant.now().toString().substringBefore('.').let { if (it.endsWith("Z")) it else it + "Z" }
            put("version.xml", asset("version.xml"))
            put("settings.xml", asset("settings.xml"))
            put("META-INF/container.xml", asset("container.xml"))
            put("META-INF/container.rdf", asset("container.rdf"))
            put("META-INF/manifest.xml", asset("manifest.xml"))
            put("Contents/content.hpf", contentHpf(title, now, chartRef != null).toByteArray(Charsets.UTF_8))
            put("Contents/header.xml", asset("header.xml"))
            put("Contents/section0.xml", section().toByteArray(Charsets.UTF_8))
            chartRef?.let { put("BinData/BIN0001.png", it) }
            put("Preview/PrvText.txt", title.toByteArray(Charsets.UTF_8))
        }
    }
}

/** 주가 그래프 그림의 가로·세로 픽셀(SummaryChartImage 가 이 크기로 그린다). */
const val CHART_W = 1600
const val CHART_H = 560
