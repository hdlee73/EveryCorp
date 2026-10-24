package io.github.hdlee73.everycorp.data.report

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import io.github.hdlee73.everycorp.ui.compare.CompareColumn
import io.github.hdlee73.everycorp.ui.compare.CompareLogic
import java.io.OutputStream

/** 기업 비교표를 A4 한 쪽 PDF로 그린다(지표 행 × 기업 열). */
object ComparePdfWriter {
    private const val W = 595
    private const val H = 842
    private const val M = 36f

    fun write(cols: List<CompareColumn>, generatedAt: String, appLine: String, out: OutputStream) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val c = page.canvas
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.rgb(30, 30, 30) }
        val bold = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        val small = Paint(body).apply { textSize = 8f; color = Color.rgb(110, 110, 110) }
        val h1 = Paint(body).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        val line = Paint().apply { color = Color.rgb(210, 210, 210); strokeWidth = 0.6f }

        var y = M + h1.textSize
        c.drawText("기업 비교 리포트", M, y, h1)
        y += 16f
        c.drawText("작성 $generatedAt · 최근 사업연도 기준(DART 사업보고서, 연결 우선)", M, y, small)
        y += 26f

        val labelW = 110f
        val colW = (W - 2 * M - labelW) / cols.size.coerceAtLeast(1)
        fun right(s: String, x: Float, p: Paint) = c.drawText(s, x - p.measureText(s), y, p)
        cols.forEachIndexed { i, col ->
            val name = if (bold.measureText(col.name) > colW - 4f) col.name.take(((colW - 4f) / bold.textSize * 1.8f).toInt()) + "…" else col.name
            right(name, M + labelW + colW * (i + 1) - 2f, bold)
        }
        y += 11f
        cols.forEachIndexed { i, col -> right(col.year?.let { "${it}년" } ?: "재무 없음", M + labelW + colW * (i + 1) - 2f, small) }
        y += 6f
        c.drawLine(M, y, W - M, y, line)
        CompareLogic.ROWS.forEach { (key, label) ->
            y += 18f
            c.drawText(label, M, y, body)
            cols.forEachIndexed { i, col -> right(CompareLogic.format(key, col.values[key]), M + labelW + colW * (i + 1) - 2f, body) }
            c.drawLine(M, y + 5f, W - M, y + 5f, line)
        }
        y += 30f
        c.drawText("PER·PBR은 연말 종가로 직접 계산한 추정치이며 재무 미공시·비상장 기업은 '-'로 표시됩니다.", M, y, small)
        y += 12f
        c.drawText("제공 정보는 지연되거나 오류가 있을 수 있으며 투자 판단의 책임은 이용자에게 있습니다.", M, y, small)
        y += 12f
        c.drawText(appLine, M, y, small)
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}
