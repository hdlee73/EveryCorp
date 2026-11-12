package io.github.hdlee73.everycorp.ui.common

/**
 * 보조지표 계산(순수 함수, 단위 테스트 대상). 입력은 종가 등 오래된 것부터. 값이 정의되지 않는 앞부분은 null.
 * - SMA(n): 단순이동평균
 * - EMA(n): 지수이동평균, 첫 값은 앞 n개 SMA 로 시작
 * - 볼린저밴드(20, 2σ): 중심 SMA20, 표준편차는 모집단(n) 기준(일반 HTS 방식)
 * - RSI(14): Wilder 평활
 * - MACD(12, 26, 9): EMA12-EMA26, 시그널 EMA9, 히스토그램
 */
object Indicators {
    fun sma(values: List<Double>, n: Int): List<Double?> {
        if (n <= 0) return values.map { null }
        val out = ArrayList<Double?>(values.size)
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= n) sum -= values[i - n]
            out += if (i >= n - 1) sum / n else null
        }
        return out
    }

    fun ema(values: List<Double>, n: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (n <= 0 || values.size < n) return out
        val k = 2.0 / (n + 1)
        var prev = values.take(n).average()
        out[n - 1] = prev
        for (i in n until values.size) {
            prev = values[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** EMA 를 null 이 섞인 입력에 적용(MACD 시그널용): 첫 n개 유효값의 평균으로 시작. */
    private fun emaOfNullable(values: List<Double?>, n: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        val first = values.indexOfFirst { it != null }
        if (first < 0 || values.size - first < n) return out
        val k = 2.0 / (n + 1)
        var prev = (first until first + n).sumOf { values[it]!! } / n
        out[first + n - 1] = prev
        for (i in first + n until values.size) {
            val v = values[i] ?: continue
            prev = v * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    data class Band(val upper: List<Double?>, val middle: List<Double?>, val lower: List<Double?>)

    fun bollinger(values: List<Double>, n: Int = 20, k: Double = 2.0): Band {
        val mid = sma(values, n)
        val up = MutableList<Double?>(values.size) { null }
        val lo = MutableList<Double?>(values.size) { null }
        for (i in values.indices) {
            val m = mid[i] ?: continue
            var s = 0.0
            for (j in i - n + 1..i) s += (values[j] - m) * (values[j] - m)
            val sd = kotlin.math.sqrt(s / n)
            up[i] = m + k * sd
            lo[i] = m - k * sd
        }
        return Band(up, mid, lo)
    }

    fun rsi(values: List<Double>, n: Int = 14): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (values.size <= n) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..n) {
            val d = values[i] - values[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        gain /= n; loss /= n
        out[n] = rsiOf(gain, loss)
        for (i in n + 1 until values.size) {
            val d = values[i] - values[i - 1]
            gain = (gain * (n - 1) + maxOf(d, 0.0)) / n
            loss = (loss * (n - 1) + maxOf(-d, 0.0)) / n
            out[i] = rsiOf(gain, loss)
        }
        return out
    }

    private fun rsiOf(gain: Double, loss: Double): Double = when {
        loss == 0.0 && gain == 0.0 -> 50.0
        loss == 0.0 -> 100.0
        else -> 100.0 - 100.0 / (1.0 + gain / loss)
    }

    data class Macd(val macd: List<Double?>, val signal: List<Double?>, val hist: List<Double?>)

    fun macd(values: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): Macd {
        val f = ema(values, fast)
        val s = ema(values, slow)
        val m = values.indices.map { i -> val a = f[i]; val b = s[i]; if (a != null && b != null) a - b else null }
        val sig = emaOfNullable(m, signal)
        val h = values.indices.map { i -> val a = m[i]; val b = sig[i]; if (a != null && b != null) a - b else null }
        return Macd(m, sig, h)
    }
}
