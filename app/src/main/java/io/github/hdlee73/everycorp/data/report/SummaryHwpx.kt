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
 * 요약을 한글(HWPX) 문서로 만든다. 글 모양·쪽 설정은 assets/hwpx 의 기본 문서(python-hwpx 의 Skeleton, Apache-2.0)를 쓰고,
 * 본문은 문단(제목·소제목·본문·작은 글)으로만 채운다. 표는 칸을 " | "로 나눈 한 줄 글로 넣는다.
 * [asset]은 assets/hwpx/ 아래 파일 이름 → 내용.
 */
class SummaryHwpxWriter(private val asset: (String) -> ByteArray) {
    private enum class Style(val charPr: Int) { Title(5), Heading(7), Bold(8), Body(0), Small(2) }

    private class Para(val style: Style, val text: String)

    fun write(input: SummaryPdfInput, out: OutputStream) = writeAll(listOf(input), out)

    fun writeAll(inputs: List<SummaryPdfInput>, out: OutputStream) {
        val paras = mutableListOf<Para>()
        inputs.forEachIndexed { i, input -> if (i > 0) paras += Para(Style.Body, ""); build(input, paras) }
        val title = inputs.firstOrNull()?.corpName.orEmpty().let { if (inputs.size > 1) "$it 외 ${inputs.size - 1}곳" else it }
        zip(title, paras, out)
    }

    private fun build(input: SummaryPdfInput, p: MutableList<Para>) {
        fun add(style: Style, text: String) { text.split('\n').forEach { p += Para(style, it) } }
        fun lv(label: String, value: String?) { if (!value.isNullOrBlank()) add(Style.Body, "$label: $value") }
        fun table(t: FinTableDto) {
            if (t.columns.isEmpty() || t.rows.isEmpty()) return
            add(Style.Small, listOf("항목") .plus(t.columns.map { listOfNotNull(it.label, it.sub).joinToString(" ") }).joinToString(" | "))
            t.rows.forEach { r ->
                val vals = r.values.take(t.columns.size).map { v -> if (r.unit == "%") Fmt.percent(v) else Fmt.eok(v) }
                add(Style.Body, (listOf(r.label) + vals).joinToString(" | "))
            }
        }

        val ov = input.overview
        add(Style.Title, input.corpName)
        add(Style.Small, listOfNotNull(input.badge.ifBlank { null }, ov?.stockCode, "작성 ${input.generatedAt}").joinToString(" · "))
        var no = 0
        fun heading(sec: SummarySection, extra: String = "") { add(Style.Heading, "${++no}. ${sec.label}$extra") }
        val want = input.sections

        if (SummarySection.Overview in want) {
            heading(SummarySection.Overview)
            if (ov == null) add(Style.Small, "기업개황을 불러오지 못했습니다.") else {
                lv("대표이사", ov.ceoName)
                lv("설립일", ov.established?.let { Fmt.date(it) })
                lv("결산월", ov.fiscalMonth?.let { "${it.toIntOrNull() ?: it}월" })
                lv("주소", ov.address)
                lv("홈페이지", ov.homepage)
                lv("전화", ov.phone)
                lv("최대주주", ov.largestShareholder.display.ifBlank { null })
            }
        }
        if (SummarySection.Financials in want) {
            val f = ov?.financials
            val tables = f?.tables
            if (f != null && f.available && tables != null) {
                heading(SummarySection.Financials, " (${f.fsLabel.orEmpty()}, 단위 ${tables.unit})")
                add(Style.Bold, "재무상태"); table(tables.balance)
                add(Style.Bold, "손익"); table(tables.income)
                f.latestBasis?.let { add(Style.Small, "기준: ${it.reportName} · 출처 DART 정기보고서") }
            } else {
                heading(SummarySection.Financials)
                add(Style.Small, f?.message ?: "재무정보가 없습니다.")
            }
        }
        if (SummarySection.Debt in want) {
            heading(SummarySection.Debt)
            val d = input.debt
            if (d == null) add(Style.Small, "채권 정보를 불러오지 못했습니다.")
            else if (!d.available) add(Style.Small, d.message ?: "채권 잔액 정보가 없습니다.")
            else {
                if (d.unitNote.isNotBlank()) add(Style.Small, d.unitNote)
                for (s in d.sections) {
                    add(Style.Bold, "${s.title}  합계 ${s.totalDisplay ?: Fmt.number(s.total)}")
                    for (r in s.rows) {
                        val buckets = r.buckets.filter { it.amount != null }.joinToString(" · ") { "${it.label} ${Fmt.number(it.amount)}" }
                        add(Style.Body, "${r.label}: ${Fmt.number(r.total)}" + if (buckets.isNotEmpty()) "  ($buckets)" else "")
                    }
                }
                if (d.missing.isNotEmpty()) add(Style.Small, "잔액 표 없음: ${d.missing.joinToString(", ")}")
                if (d.source.isNotBlank()) add(Style.Small, "출처: ${d.source}")
            }
        }
        if (SummarySection.Stock in want) {
            heading(SummarySection.Stock)
            val q = input.quote
            if (q == null) {
                add(Style.Small, if (ov?.isListed == false) "비상장 기업이라 주가 정보가 없습니다." else "주가 정보를 불러오지 못했습니다.")
            } else {
                lv("현재가", "${Fmt.price(q.price)}원  ${Fmt.change(q.change, q.changeRate, q.direction)}")
                lv("시가/고가/저가", "${Fmt.price(q.open)} / ${Fmt.price(q.high)} / ${Fmt.price(q.low)}")
                lv("거래량", Fmt.number(q.volume))
                lv("52주 최고/최저", "${Fmt.price(q.week52High)} / ${Fmt.price(q.week52Low)}")
                if (q.per != null || q.pbr != null) {
                    lv("PER / PBR", "${q.per?.let { v -> String.format(java.util.Locale.US, "%.2f", v) } ?: "-"} / ${q.pbr?.let { v -> String.format(java.util.Locale.US, "%.2f", v) } ?: "-"}")
                }
            }
        }
        if (SummarySection.Audit in want) {
            heading(SummarySection.Audit, " (연결·별도)")
            val all = input.auditReports
            val ar = all?.let(::yearEndAuditReports)
            if (all == null) add(Style.Small, "감사보고서 정보를 불러오지 못했습니다.")
            else if (ar.isNullOrEmpty()) add(Style.Small, "조회 기간 내 기말(결산) 감사보고서가 없습니다.")
            else {
                add(Style.Small, "기간 | 구분 | 재무제표 | 감사(검토)인 | 의견")
                ar.forEach { r ->
                    add(Style.Body, listOf(r.periodLabel.ifBlank { r.period }, "감사", if (r.consolidated) "연결" else "별도", r.auditor.ifBlank { "-" }, r.opinion.ifBlank { "-" }).joinToString(" | "))
                }
                add(Style.Small, "출처: DART 정기보고서·감사보고서. 반기·분기 검토는 제외하고 결산(기말) 기준만 넣었습니다.")
            }
        }
        p += Para(Style.Body, "")
        add(Style.Small, "제공 정보는 지연되거나 오류가 있을 수 있으며 투자 판단의 책임은 이용자에게 있습니다. 출처: DART, 한국거래소/키움증권/네이버 금융, 공공데이터포털.")
        add(Style.Small, "${input.appName} v${input.versionName} · 만든이 ${input.author}")
    }

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

    private fun section(paras: List<Para>): String {
        val sb = StringBuilder(String(asset("section0_head.xml"), Charsets.UTF_8))
        var id = 1_000
        paras.forEachIndexed { i, para ->
            val run = "<hp:run charPrIDRef=\"${para.style.charPr}\">" + (if (para.text.isEmpty()) "<hp:t/>" else "<hp:t>${esc(para.text)}</hp:t>") + "</hp:run>"
            if (i == 0) {
                // 첫 문단은 쪽 설정(secPr)이 든 기본 문단에 이어 붙인다.
                sb.append(run).append("</hp:p>")
            } else {
                sb.append("<hp:p id=\"").append(id++).append("\" paraPrIDRef=\"0\" styleIDRef=\"0\" pageBreak=\"0\" columnBreak=\"0\" merged=\"0\">").append(run).append("</hp:p>")
            }
        }
        sb.append("</hs:sec>")
        return sb.toString()
    }

    private fun contentHpf(title: String, now: String): String {
        val src = String(asset("content.hpf"), Charsets.UTF_8)
        return src.replace("<opf:title/>", "<opf:title>${esc(title)}</opf:title>")
            .replace("synthetic-fixture-author", "Every Corp")
            .replace(Regex("(CreatedDate\" content=\"text\">)[^<]*"), "$1$now")
            .replace(Regex("(ModifiedDate\" content=\"text\">)[^<]*"), "$1$now")
    }

    private fun zip(title: String, paras: List<Para>, out: OutputStream) {
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
            put("Contents/content.hpf", contentHpf(title, now).toByteArray(Charsets.UTF_8))
            put("Contents/header.xml", asset("header.xml"))
            put("Contents/section0.xml", section(paras).toByteArray(Charsets.UTF_8))
            put("Preview/PrvText.txt", paras.take(40).joinToString("\r\n") { it.text }.toByteArray(Charsets.UTF_8))
        }
    }
}
