package io.github.hdlee73.everycorp.data.api

import io.github.hdlee73.everycorp.data.api.dto.AppMetaDto
import io.github.hdlee73.everycorp.data.api.dto.AuditReportsResponse
import io.github.hdlee73.everycorp.data.api.dto.AuditLinkDto
import io.github.hdlee73.everycorp.data.api.dto.AuditScreeningDto
import io.github.hdlee73.everycorp.data.api.dto.CapitalScreeningDto
import io.github.hdlee73.everycorp.data.api.dto.ConvertibleScreeningDto
import io.github.hdlee73.everycorp.data.api.dto.ChartDto
import io.github.hdlee73.everycorp.data.api.dto.CorpSearchResponse
import io.github.hdlee73.everycorp.data.api.dto.DebtSecuritiesDto
import io.github.hdlee73.everycorp.data.api.dto.DisclosuresResponse
import io.github.hdlee73.everycorp.data.api.dto.EtfDetailDto
import io.github.hdlee73.everycorp.data.api.dto.EtfSearchResponse
import io.github.hdlee73.everycorp.data.api.dto.NewsDto
import io.github.hdlee73.everycorp.data.api.dto.IntradayDto
import io.github.hdlee73.everycorp.data.api.dto.OverviewDto
import io.github.hdlee73.everycorp.data.api.dto.QuoteDto
import io.github.hdlee73.everycorp.data.api.dto.ShareholdersDto
import io.github.hdlee73.everycorp.data.api.dto.StakeChangesDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** 백엔드 REST API(docs/API_SPEC.md). 모든 호출은 NetworkModule 의 인터셉터가 X-App-Token 헤더를 붙인다. */
interface EveryCorpApi {
    @GET("api/v1/app/meta")
    suspend fun appMeta(@Query("current_version") currentVersion: String): AppMetaDto

    @GET("api/v1/corps/search")
    suspend fun searchCorps(
        @Query("q") query: String,
        @Query("limit") limit: Int = 20,
        @Query("listed_only") listedOnly: Boolean = false,
    ): CorpSearchResponse

    @GET("api/v1/corps/{corpCode}/overview")
    suspend fun overview(@Path("corpCode") corpCode: String): OverviewDto

    @GET("api/v1/corps/{corpCode}/audit-reports")
    suspend fun auditReports(
        @Path("corpCode") corpCode: String,
        @Query("years") years: Int = 3,
    ): AuditReportsResponse

    @GET("api/v1/corps/{corpCode}/shareholders")
    suspend fun shareholders(@Path("corpCode") corpCode: String): ShareholdersDto

    @GET("api/v1/corps/{corpCode}/disclosures")
    suspend fun disclosures(
        @Path("corpCode") corpCode: String,
        @Query("years") years: Int = 1,
        @Query("source") source: String = "all",
        @Query("dedupe") dedupe: Boolean = false,
        @Query("q") query: String? = null,
        @Query("category") category: String = "all",
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 50,
    ): DisclosuresResponse

    @GET("api/v1/corps/{corpCode}/stock/quote")
    suspend fun quote(@Path("corpCode") corpCode: String): QuoteDto

    @GET("api/v1/corps/{corpCode}/stock/intraday")
    suspend fun intraday(@Path("corpCode") corpCode: String): IntradayDto

    @GET("api/v1/corps/{corpCode}/stock/chart")
    suspend fun chart(
        @Path("corpCode") corpCode: String,
        @Query("days") days: Int = 365,
    ): ChartDto

    @GET("api/v1/corps/{corpCode}/news")
    suspend fun news(@Path("corpCode") corpCode: String, @Query("limit") limit: Int = 30): NewsDto

    @GET("api/v1/corps/{corpCode}/debt-securities")
    suspend fun debtSecurities(@Path("corpCode") corpCode: String): DebtSecuritiesDto

    @GET("api/v1/etfs/search")
    suspend fun searchEtfs(@Query("q") query: String, @Query("limit") limit: Int = 10): EtfSearchResponse

    @GET("api/v1/etfs/{code}")
    suspend fun etf(@Path("code") code: String): EtfDetailDto

    @GET("api/v1/etfs/{code}/chart")
    suspend fun etfChart(@Path("code") code: String, @Query("days") days: Int = 365): ChartDto

    @GET("api/v1/corps/{corpCode}/stake-changes")
    suspend fun stakeChanges(@Path("corpCode") corpCode: String): StakeChangesDto

    @GET("api/v1/screening/audit-opinion")
    suspend fun screeningAudit(@Query("year") year: Int): AuditScreeningDto

    @GET("api/v1/screening/audit-link")
    suspend fun screeningAuditLink(@Query("rcp_no") rcpNo: String): AuditLinkDto

    @GET("api/v1/screening/capital-impairment")
    suspend fun screeningCapital(@Query("year") year: Int, @Query("reprt_code") reprtCode: String = "11011"): CapitalScreeningDto

    @GET("api/v1/screening/convertible-bonds")
    suspend fun screeningConvertible(@Query("year") year: Int): ConvertibleScreeningDto
}
