package io.github.hdlee73.everycorp.data.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import io.github.hdlee73.everycorp.data.api.dto.AuditReportDto
import io.github.hdlee73.everycorp.data.api.dto.ChartDto
import io.github.hdlee73.everycorp.data.api.dto.DebtSecuritiesDto
import io.github.hdlee73.everycorp.data.api.dto.FinTableDto
import io.github.hdlee73.everycorp.data.api.dto.OverviewDto
import io.github.hdlee73.everycorp.data.api.dto.QuoteDto
import io.github.hdlee73.everycorp.ui.common.Fmt
import java.io.OutputStream

/** 개황·채권·주가현황을 A4 PDF 한 부로 묶는 데 필요한 입력. 값이 없는 섹션은 "불러오지 못함"으로 표시한다. */
data class SummaryPdfInput(
    val corpName: String,
    val badge: String,
    val overview: OverviewDto?,
    val debt: DebtSecuritiesDto?,
    val quote: QuoteDto?,
    val chart: ChartDto?,
    /** 기수·재무제표(연결/별도)별 감사(검토)인과 의견. 불러오지 못했으면 null. */
    val auditReports: List<AuditReportDto>? = null,
    val appName: String,
    val versionName: String,
    val author: String,
    val generatedAt: String,
)

/** Android 의 PdfDocument 로 A4(595x842pt) 문서를 직접 그린다. 외부 라이브러리 없음. */
class SummaryPdfWriter {
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var pageNo = 0
    private var y = 0f

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = Color.rgb(30, 30, 30) }
    private val bold = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(body).apply { textSize = 8f; color = Color.rgb(110, 110, 110) }
    private val labelPaint = Paint(small).apply { textSize = 9f }
    private val h1 = Paint(body).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
    private val h2 = Paint(body).apply { textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(20, 80, 150) }
    private val line = Paint().apply { color = Color.rgb(210, 210, 210); strokeWidth = 0.6f }

    private fun newPage() {
        finishPage()
        pageNo++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        page = p
        canvas = p.canvas
        y = MARGIN
    }

    private fun finishPage() {
        page?.let { doc.finishPage(it) }
        page = null
        canvas = null
    }

    private fun need(h: Float) {
        if (canvas == null || y + h > PAGE_H - MARGIN) newPage()
    }

    private fun text(s: String, paint: Paint, x: Float = MARGIN, gap: Float = 3f) {
        for (l in wrap(s, paint, PAGE_W - MARGIN - x)) {
            need(paint.textSize + gap)
            y += paint.textSize
            canvas!!.drawText(l, x, y, paint)
            y += gap
        }
    }

    private fun wrap(s: String, paint: Paint, width: Float): List<String> {
        val out = mutableListOf<String>()
        for (para in s.split('\n')) {
            var rest = para
            if (rest.isEmpty()) { out += ""; continue }
            while (rest.isNotEmpty()) {
                val n = paint.breakText(rest, true, width, null).coerceAtLeast(1)
                out += rest.substring(0, n)
                rest = rest.substring(n)
            }
        }
        return out
    }

    private fun heading(s: String) {
        need(40f)
        y += 10f
        text(s, h2)
        canvas!!.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line)
        y += 6f
    }

    private fun labelValue(label: String, value: String?) {
        if (value.isNullOrBlank()) return
        val lines = wrap(value, body, PAGE_W - MARGIN - LABEL_W - MARGIN)
        need(lines.size * 13f)
        y += body.textSize
        canvas!!.drawText(label, MARGIN, y, labelPaint)
        canvas!!.drawText(lines.first(), MARGIN + LABEL_W, y, body)
        for (l in lines.drop(1)) { y += 13f; canvas!!.drawText(l, MARGIN + LABEL_W, y, body) }
        y += 4f
    }

    private fun rightText(s: String, right: Float, paint: Paint) {
        canvas!!.drawText(s, right - paint.measureText(s), y, paint)
    }

    private fun table(t: FinTableDto) {
        if (t.columns.isEmpty() || t.rows.isEmpty()) return
        val labelW = 140f
        val colW = (PAGE_W - 2 * MARGIN - labelW) / t.columns.size
        need(16f * (t.rows.size + 1))
        y += 11f
        t.columns.forEachIndexed { i, c ->
            rightText(listOfNotNull(c.label, c.sub).joinToString(" "), MARGIN + labelW + colW * (i + 1) - 2f, small)
        }
        y += 4f
        canvas!!.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line)
        for (r in t.rows) {
            need(16f)
            y += 13f
            canvas!!.drawText(r.label, MARGIN, y, body)
            r.values.forEachIndexed { i, v ->
                if (i >= t.columns.size) return@forEachIndexed
                val s = if (r.unit == "%") Fmt.percent(v) else Fmt.eok(v)
                rightText(s, MARGIN + labelW + colW * (i + 1) - 2f, body)
            }
            y += 3f
        }
        y += 4f
    }

    private fun priceChart(chart: ChartDto) {
        val pts = chart.points.filter { it.close != null }
        if (pts.size < 2) { text("차트 데이터가 부족합니다.", small); return }
        val h = 130f
        val volH = 28f
        need(h + volH + 30f)
        y += 6f
        val left = MARGIN + 44f
        val right = PAGE_W - MARGIN
        val top = y
        val closes = pts.map { it.close!!.toFloat() }
        val lo = closes.min()
        val hi = closes.max()
        val span = (hi - lo).takeIf { it > 0f } ?: 1f
        val c = canvas!!
        val frame = Paint(line)
        c.drawRect(left, top, right, top + h, Paint(frame).apply { style = Paint.Style.STROKE })
        val path = Path()
        closes.forEachIndexed { i, v ->
            val x = left + (right - left) * i / (closes.size - 1)
            val yy = top + h - (v - lo) / span * h
            if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f; color = Color.rgb(20, 100, 190) })
        c.drawText(Fmt.price(hi.toLong()), MARGIN, top + 8f, small)
        c.drawText(Fmt.price(lo.toLong()), MARGIN, top + h, small)
        val vols = pts.map { (it.volume ?: 0L).toFloat() }
        val vMax = (vols.maxOrNull() ?: 0f).takeIf { it > 0f } ?: 1f
        val vTop = top + h + 4f
        val barW = ((right - left) / pts.size).coerceAtLeast(0.6f)
        val barPaint = Paint().apply { color = Color.rgb(170, 190, 215) }
        vols.forEachIndexed { i, v ->
            val x = left + (right - left) * i / (pts.size - 1)
            val bh = v / vMax * volH
            c.drawRect(x - barW / 2, vTop + volH - bh, x + barW / 2, vTop + volH, barPaint)
        }
        y = vTop + volH + 10f
        c.drawText(Fmt.date(pts.first().date), left, y, small)
        rightText(Fmt.date(pts.last().date), right, small)
        y += 6f
    }

    private fun auditTable(reports: List<AuditReportDto>) {
        val x = floatArrayOf(MARGIN, MARGIN + 92f, MARGIN + 132f, MARGIN + 172f, MARGIN + 330f)
        need(30f)
        y += 11f
        listOf("기간", "구분", "재무제표", "감사(검토)인", "의견").forEachIndexed { i, h -> canvas!!.drawText(h, x[i], y, small) }
        y += 4f
        canvas!!.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line)
        for (r in reports) {
            val auditor = wrap(r.auditor.ifBlank { "-" }, body, x[4] - x[3] - 6f)
            val opinion = wrap(r.opinion.ifBlank { "-" }, body, PAGE_W - MARGIN - x[4])
            val n = maxOf(auditor.size, opinion.size)
            need(13f * n + 4f)
            y += 13f
            canvas!!.drawText(r.periodLabel.ifBlank { r.period }, x[0], y, body)
            canvas!!.drawText(if (r.kind == "REVIEW") "검토" else "감사", x[1], y, body)
            canvas!!.drawText(if (r.consolidated) "연결" else "별도", x[2], y, body)
            auditor.forEachIndexed { i, l -> canvas!!.drawText(l, x[3], y + 13f * i, body) }
            opinion.forEachIndexed { i, l -> canvas!!.drawText(l, x[4], y + 13f * i, body) }
            y += 13f * (n - 1) + 3f
        }
        y += 4f
    }

    fun write(input: SummaryPdfInput, out: OutputStream) {
        newPage()
        text(input.corpName, h1, gap = 4f)
        val ov = input.overview
        text(
            listOfNotNull(input.badge.ifBlank { null }, ov?.stockCode, "작성 ${input.generatedAt}").joinToString(" · "),
            small,
        )

        heading("1. 기업개황")
        if (ov == null) text("기업개황을 불러오지 못했습니다.", small) else {
            labelValue("대표이사", ov.ceoName)
            labelValue("설립일", ov.established?.let { Fmt.date(it) })
            labelValue("결산월", ov.fiscalMonth?.let { "${it.toIntOrNull() ?: it}월" })
            labelValue("주소", ov.address)
            labelValue("홈페이지", ov.homepage)
            labelValue("전화", ov.phone)
            labelValue("최대주주", ov.largestShareholder.display.ifBlank { null })

            val f = ov.financials
            val tables = f.tables
            if (f.available && tables != null) {
                heading("2. 재무현황 (${f.fsLabel.orEmpty()}, 단위 ${tables.unit})")
                text("재무상태", bold)
                table(tables.balance)
                text("손익", bold)
                table(tables.income)
                f.latestBasis?.let { text("기준: ${it.reportName} · 출처 DART 정기보고서", small) }
            } else {
                heading("2. 재무현황")
                text(f.message ?: "재무정보가 없습니다.", small)
            }
        }

        heading("3. 채권·단기증권")
        val d = input.debt
        if (d == null) text("채권 정보를 불러오지 못했습니다.", small)
        else if (!d.available) text(d.message ?: "채권 잔액 정보가 없습니다.", small)
        else {
            if (d.unitNote.isNotBlank()) text(d.unitNote, small)
            for (s in d.sections) {
                need(30f)
                y += 4f
                text("${s.title}  합계 ${s.totalDisplay ?: Fmt.number(s.total)}", bold)
                for (r in s.rows) {
                    val buckets = r.buckets.filter { it.amount != null }.joinToString(" · ") { "${it.label} ${Fmt.number(it.amount)}" }
                    text("${r.label}: ${Fmt.number(r.total)}" + if (buckets.isNotEmpty()) "  ($buckets)" else "", body, x = MARGIN + 8f)
                }
            }
            if (d.missing.isNotEmpty()) text("잔액 표 없음: ${d.missing.joinToString(", ")}", small)
            if (d.source.isNotBlank()) text("출처: ${d.source}", small)
        }

        heading("4. 주가현황")
        val q = input.quote
        if (q == null && input.chart == null) {
            text(if (ov?.isListed == false) "비상장 기업이라 주가 정보가 없습니다." else "주가 정보를 불러오지 못했습니다.", small)
        } else {
            q?.let {
                labelValue("현재가", "${Fmt.price(it.price)}원  ${Fmt.change(it.change, it.changeRate, it.direction)}")
                labelValue("시가/고가/저가", "${Fmt.price(it.open)} / ${Fmt.price(it.high)} / ${Fmt.price(it.low)}")
                labelValue("거래량", Fmt.number(it.volume))
                labelValue("52주 최고/최저", "${Fmt.price(it.week52High)} / ${Fmt.price(it.week52Low)}")
                if (it.per != null || it.pbr != null) {
                    labelValue("PER / PBR", "${it.per?.let { v -> String.format("%.2f", v) } ?: "-"} / ${it.pbr?.let { v -> String.format("%.2f", v) } ?: "-"}")
                }
            }
            input.chart?.let { priceChart(it) }
        }

        heading("5. 감사의견·감사인 (기수·재무제표별)")
        val ar = input.auditReports
        if (ar == null) text("감사보고서 정보를 불러오지 못했습니다.", small)
        else if (ar.isEmpty()) text("조회 기간 내 감사·검토보고서가 없습니다.", small)
        else {
            auditTable(ar)
            text("출처: DART 정기보고서·감사보고서. 연결/별도는 공시된 보고서 기준입니다.", small)
        }

        need(40f)
        y += 14f
        text("제공 정보는 지연되거나 오류가 있을 수 있으며 투자 판단의 책임은 이용자에게 있습니다. 출처: DART, 한국거래소/키움증권/네이버 금융, 공공데이터포털.", small)
        text("${input.appName} v${input.versionName} · 만든이 ${input.author}", small)
        finishPage()
        doc.writeTo(out)
        doc.close()
    }

    private companion object {
        const val PAGE_W = 595
        const val PAGE_H = 842
        const val MARGIN = 36f
        const val LABEL_W = 90f
    }
}
