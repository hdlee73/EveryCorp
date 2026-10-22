package io.github.hdlee73.everycorp.data.repo

import io.github.hdlee73.everycorp.data.api.EveryCorpApi
import io.github.hdlee73.everycorp.data.api.NetworkModule
import io.github.hdlee73.everycorp.data.api.dto.AuditReportsResponse
import io.github.hdlee73.everycorp.data.api.dto.ChartDto
import io.github.hdlee73.everycorp.data.api.dto.CorpItemDto
import io.github.hdlee73.everycorp.data.api.dto.DebtSecuritiesDto
import io.github.hdlee73.everycorp.data.api.dto.DisclosuresResponse
import io.github.hdlee73.everycorp.data.api.dto.OverviewDto
import io.github.hdlee73.everycorp.data.api.dto.QuoteDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 공시 목록 기간 칩(최근 1년 ~ 최대 10년). 서버 `years` 파라미터와 같다. */
enum class DisclosurePeriod(val years: Int, val label: String) {
    Y1(1, "1년"), Y3(3, "3년"), Y5(5, "5년"), Y10(10, "10년");

    companion object { val Default = Y1 }
}

enum class DisclosureSource(val param: String, val label: String) {
    All("all", "전체"), Dart("dart", "DART"), Kind("kind", "KIND")
}

/** API 호출을 Result 로 감싸 ViewModel 이 try/catch 없이 쓰게 한다. 취소 예외는 그대로 전파. */
internal suspend inline fun <T> apiCall(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(NetworkModule.toApiException(e))
    }

class CorpRepository(private val api: EveryCorpApi) {

    suspend fun search(query: String, listedOnly: Boolean = false): Result<List<CorpItemDto>> {
        val q = query.trim()
        if (q.isEmpty()) return Result.success(emptyList())
        return apiCall { api.searchCorps(q, listedOnly = listedOnly).items }
    }

    suspend fun overview(corpCode: String): Result<OverviewDto> = apiCall { api.overview(corpCode) }

    suspend fun auditReports(corpCode: String, years: Int = 3): Result<AuditReportsResponse> =
        apiCall { api.auditReports(corpCode, years) }

    suspend fun disclosures(
        corpCode: String,
        period: DisclosurePeriod = DisclosurePeriod.Default,
        source: DisclosureSource = DisclosureSource.All,
        dedupe: Boolean = false,
        query: String? = null,
        page: Int = 1,
    ): Result<DisclosuresResponse> =
        apiCall { api.disclosures(corpCode, period.years, source.param, dedupe, query?.trim()?.ifEmpty { null }, page) }

    suspend fun debtSecurities(corpCode: String): Result<DebtSecuritiesDto> = apiCall { api.debtSecurities(corpCode) }

    suspend fun quote(corpCode: String): Result<QuoteDto> = apiCall { api.quote(corpCode) }

    suspend fun chart(corpCode: String, days: Int = 365): Result<ChartDto> = apiCall { api.chart(corpCode, days) }

    /**
     * 현재가 폴링. 수집하는 쪽(탭이 보이는 동안만 collect)이 취소하면 멈춘다.
     * 서버 캐시가 5초이므로 그보다 짧게 부르지 않는다.
     */
    fun quotePolling(corpCode: String, intervalMs: Long = 5_000L): Flow<Result<QuoteDto>> = flow {
        val interval = intervalMs.coerceAtLeast(5_000L)
        while (true) {
            emit(quote(corpCode))
            delay(interval)
        }
    }
}
