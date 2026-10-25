package io.github.hdlee73.everycorp.data.repo

import io.github.hdlee73.everycorp.data.api.EveryCorpApi
import io.github.hdlee73.everycorp.data.api.NetworkModule
import io.github.hdlee73.everycorp.data.api.dto.AuditReportsResponse
import io.github.hdlee73.everycorp.data.api.dto.AuditLinkDto
import io.github.hdlee73.everycorp.data.api.dto.AuditScreeningDto
import io.github.hdlee73.everycorp.data.api.dto.CapitalScreeningDto
import io.github.hdlee73.everycorp.data.api.dto.ConvertibleScreeningDto
import io.github.hdlee73.everycorp.data.api.dto.ChartDto
import io.github.hdlee73.everycorp.data.api.dto.CorpItemDto
import io.github.hdlee73.everycorp.data.api.dto.DebtSecuritiesDto
import io.github.hdlee73.everycorp.data.api.dto.DisclosuresResponse
import io.github.hdlee73.everycorp.data.api.dto.NewsDto
import io.github.hdlee73.everycorp.data.api.dto.IntradayDto
import io.github.hdlee73.everycorp.data.api.dto.OverviewDto
import io.github.hdlee73.everycorp.data.api.dto.DisclosureDto
import io.github.hdlee73.everycorp.data.api.dto.QuoteDto
import io.github.hdlee73.everycorp.data.api.dto.EtfDetailDto
import io.github.hdlee73.everycorp.data.api.dto.EtfItemDto
import io.github.hdlee73.everycorp.data.api.dto.ShareholdersDto
import io.github.hdlee73.everycorp.data.api.dto.StakeChangesDto
import io.github.hdlee73.everycorp.data.kind.KindClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 공시 목록 기간 칩(최근 1년 ~ 최대 10년). 서버 `years` 파라미터와 같다. */
enum class DisclosurePeriod(val years: Int, val label: String) {
    Y1(1, "1년"), Y3(3, "3년"), Y5(5, "5년"), Y10(10, "10년");

    companion object { val Default = Y1 }
}

/** 공시 종류 칩. 정기보고서 = 사업·반기·분기보고서. */
enum class DisclosureCategory(val param: String, val label: String) {
    All("all", "전체 공시"), Periodic("periodic", "정기보고서")
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

class CorpRepository(private val api: EveryCorpApi, private val kind: KindClient? = null) {

    suspend fun search(query: String, listedOnly: Boolean = false): Result<List<CorpItemDto>> {
        val q = query.trim()
        if (q.isEmpty()) return Result.success(emptyList())
        return apiCall { api.searchCorps(q, listedOnly = listedOnly).items }
    }

    /** ETF 검색. 키움 설정이 없거나 실패해도 기업 검색에는 영향이 없도록 호출하는 쪽에서 실패를 무시한다. */
    suspend fun searchEtfs(query: String): Result<List<EtfItemDto>> {
        val q = query.trim()
        if (q.isEmpty()) return Result.success(emptyList())
        return apiCall { api.searchEtfs(q).items }
    }

    suspend fun etf(code: String): Result<EtfDetailDto> = apiCall { api.etf(code) }

    suspend fun etfChart(code: String, days: Int = 365): Result<ChartDto> = apiCall { api.etfChart(code, days) }

    suspend fun overview(corpCode: String): Result<OverviewDto> = apiCall { api.overview(corpCode) }

    suspend fun shareholders(corpCode: String): Result<ShareholdersDto> = apiCall { api.shareholders(corpCode) }

    suspend fun auditReports(corpCode: String, years: Int = 3): Result<AuditReportsResponse> =
        apiCall { api.auditReports(corpCode, years) }

    suspend fun disclosures(
        corpCode: String,
        period: DisclosurePeriod = DisclosurePeriod.Default,
        source: DisclosureSource = DisclosureSource.All,
        dedupe: Boolean = false,
        query: String? = null,
        category: DisclosureCategory = DisclosureCategory.All,
        page: Int = 1,
    ): Result<DisclosuresResponse> =
        apiCall { api.disclosures(corpCode, period.years, source.param, dedupe, query?.trim()?.ifEmpty { null }, category.param, page) }

    /** KIND 공시는 서버(해외 IP는 KIND가 차단)가 아니라 폰에서 직접 불러온다. 비상장사는 KIND 공시가 없다. */
    suspend fun kindDisclosures(corp: CorpItemDto, period: DisclosurePeriod): Result<List<DisclosureDto>> {
        val client = kind ?: return Result.success(emptyList())
        val stock = corp.stockCode?.takeIf { it.length == 6 } ?: return Result.success(emptyList())
        return try {
            Result.success(client.disclosures(corp.corpName, stock, period.years))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(java.io.IOException("KIND 공시를 불러오지 못했습니다. (${e.message ?: "네트워크 오류"})"))
        }
    }

    /** 알림 탭용: 최근 DART 공시 몇 건만. */
    suspend fun recentDisclosures(corpCode: String, size: Int = 5): Result<List<DisclosureDto>> =
        apiCall { api.disclosures(corpCode, years = 1, source = "dart", size = size).items }

    suspend fun stakeChanges(corpCode: String): Result<StakeChangesDto> = apiCall { api.stakeChanges(corpCode) }

    suspend fun screeningAudit(year: Int, start: Boolean = false): Result<AuditScreeningDto> = apiCall { api.screeningAudit(year, start) }

    suspend fun screeningAuditLink(rcpNo: String): Result<AuditLinkDto> = apiCall { api.screeningAuditLink(rcpNo) }

    suspend fun screeningCapital(year: Int, reprtCode: String, start: Boolean = false): Result<CapitalScreeningDto> =
        apiCall { api.screeningCapital(year, reprtCode, start) }

    suspend fun screeningConvertible(year: Int, start: Boolean = false): Result<ConvertibleScreeningDto> = apiCall { api.screeningConvertible(year, start) }

    suspend fun news(corpCode: String): Result<NewsDto> = apiCall { api.news(corpCode) }

    suspend fun debtSecurities(corpCode: String): Result<DebtSecuritiesDto> = apiCall { api.debtSecurities(corpCode) }

    suspend fun quote(corpCode: String): Result<QuoteDto> = apiCall { api.quote(corpCode) }

    suspend fun intraday(corpCode: String): Result<IntradayDto> = apiCall { api.intraday(corpCode) }

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
