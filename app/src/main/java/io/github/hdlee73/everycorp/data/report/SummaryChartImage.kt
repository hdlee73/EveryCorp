package io.github.hdlee73.everycorp.data.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import io.github.hdlee73.everycorp.data.api.dto.ChartDto
import io.github.hdlee73.everycorp.ui.common.Fmt
import java.io.ByteArrayOutputStream

/** HWPX 에 넣을 주가 차트 그림(PNG). PDF 의 priceChart 와 같은 모양(종가선 + 거래량 막대)이다. */
object SummaryChartImage {
    fun render(chart: ChartDto): ByteArray? {
        val pts = chart.points.filter { it.close != null }
        if (pts.size < 2) return null
        val w = CHART_W
        val h = CHART_H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Color.rgb(110, 110, 110) }
        val left = 150f
        val right = w - 10f
        val top = 10f
        val ph = 400f
        val volH = 100f
        val closes = pts.map { it.close!!.toFloat() }
        val lo = closes.min()
        val hi = closes.max()
        val span = (hi - lo).takeIf { it > 0f } ?: 1f
        val frame = Paint().apply { color = Color.rgb(210, 210, 210); strokeWidth = 2f; style = Paint.Style.STROKE }
        c.drawRect(left, top, right, top + ph, frame)
        val path = Path()
        closes.forEachIndexed { i, v ->
            val x = left + (right - left) * i / (closes.size - 1)
            val y = top + ph - (v - lo) / span * ph
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f; color = Color.rgb(20, 100, 190) })
        c.drawText(Fmt.price(hi.toLong()), 4f, top + 22f, small)
        c.drawText(Fmt.price(lo.toLong()), 4f, top + ph, small)
        val vols = pts.map { (it.volume ?: 0L).toFloat() }
        val vMax = (vols.maxOrNull() ?: 0f).takeIf { it > 0f } ?: 1f
        val vTop = top + ph + 10f
        val barW = ((right - left) / pts.size).coerceAtLeast(1.5f)
        val bar = Paint().apply { color = Color.rgb(170, 190, 215) }
        vols.forEachIndexed { i, v ->
            val x = left + (right - left) * i / (pts.size - 1)
            c.drawRect(x - barW / 2, vTop + volH - v / vMax * volH, x + barW / 2, vTop + volH, bar)
        }
        val ty = vTop + volH + 26f
        c.drawText(Fmt.date(pts.first().date), left, ty, small)
        val last = Fmt.date(pts.last().date)
        c.drawText(last, right - small.measureText(last), ty, small)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
