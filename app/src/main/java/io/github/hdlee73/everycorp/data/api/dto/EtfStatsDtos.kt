package io.github.hdlee73.everycorp.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ETF 통계(운용사별·일자별 ETF 수, 순자산총액, 시가총액). 서버 /api/v1/etfs/stats/*, /api/v1/etfs/{code}/stats 응답.
// 금액은 모두 원 단위.

@Serializable
data class EtfStatsProgressDto(val done: Int = 0, val total: Int = 0)

@Serializable
data class EtfStatsCoverageDto(
    @SerialName("first_date") val firstDate: String = "",
    @SerialName("last_date") val lastDate: String = "",
    /** 저장한 영업일 수. */
    val days: Int = 0,
    val source: String = "",
    /** pending · running · done · failed */
    val backfill: String = "pending",
    val progress: EtfStatsProgressDto = EtfStatsProgressDto(),
    /** 과거 자료를 받지 못해 첫 수집일부터 쌓고 있으면 true. */
    @SerialName("since_first_collect") val sinceFirstCollect: Boolean = false,
    val collecting: Boolean = false,
    val note: String = "",
)

@Serializable
data class EtfTotalSeriesDto(
    val count: List<Int> = emptyList(),
    @SerialName("net_assets") val netAssets: List<Long> = emptyList(),
    @SerialName("market_cap") val marketCap: List<Long> = emptyList(),
)

@Serializable
data class EtfManagerSeriesDto(
    val name: String = "",
    val count: List<Int> = emptyList(),
    @SerialName("net_assets") val netAssets: List<Long> = emptyList(),
    @SerialName("market_cap") val marketCap: List<Long> = emptyList(),
)

@Serializable
data class EtfManagerRowDto(
    val name: String = "",
    val count: Int = 0,
    @SerialName("net_assets") val netAssets: Long = 0,
    @SerialName("market_cap") val marketCap: Long = 0,
    /** 순자산 점유율(%). */
    val share: Double? = null,
    @SerialName("count_change") val countChange: Int = 0,
    @SerialName("net_assets_change") val netAssetsChange: Long = 0,
)

@Serializable
data class EtfStatsOverviewDto(
    val dates: List<String> = emptyList(),
    val total: EtfTotalSeriesDto = EtfTotalSeriesDto(),
    val managers: List<EtfManagerSeriesDto> = emptyList(),
    @SerialName("manager_table") val managerTable: List<EtfManagerRowDto> = emptyList(),
    val coverage: EtfStatsCoverageDto = EtfStatsCoverageDto(),
    val note: String = "",
)

@Serializable
data class EtfRankItemDto(
    val rank: Int = 0,
    val code: String = "",
    val name: String = "",
    val manager: String = "",
    val close: Long? = null,
    val nav: Double? = null,
    @SerialName("net_assets") val netAssets: Long? = null,
    @SerialName("market_cap") val marketCap: Long? = null,
    @SerialName("listed_shares") val listedShares: Long? = null,
    @SerialName("net_assets_change") val netAssetsChange: Long? = null,
    @SerialName("net_assets_change_pct") val netAssetsChangePct: Double? = null,
    @SerialName("market_cap_change") val marketCapChange: Long? = null,
    /** 기준일 이후 상장. */
    @SerialName("new") val isNew: Boolean = false,
)

@Serializable
data class EtfRankingDto(
    val date: String = "",
    @SerialName("base_date") val baseDate: String = "",
    val sort: String = "net_assets",
    val total: Int = 0,
    val items: List<EtfRankItemDto> = emptyList(),
    val managers: List<String> = emptyList(),
    val coverage: EtfStatsCoverageDto = EtfStatsCoverageDto(),
    val note: String = "",
)

@Serializable
data class EtfValueChangeDto(val from: Double = 0.0, val to: Double = 0.0, val diff: Double = 0.0, val pct: Double? = null)

@Serializable
data class EtfPeriodChangeDto(
    val label: String = "",
    @SerialName("net_assets") val netAssets: EtfValueChangeDto? = null,
    @SerialName("market_cap") val marketCap: EtfValueChangeDto? = null,
    @SerialName("listed_shares") val listedShares: EtfValueChangeDto? = null,
)

@Serializable
data class EtfStatsDto(
    val code: String = "",
    val name: String = "",
    val manager: String = "",
    val dates: List<String> = emptyList(),
    val close: List<Long?> = emptyList(),
    val nav: List<Double?> = emptyList(),
    @SerialName("net_assets") val netAssets: List<Long?> = emptyList(),
    @SerialName("market_cap") val marketCap: List<Long?> = emptyList(),
    @SerialName("listed_shares") val listedShares: List<Long?> = emptyList(),
    val rank: Int? = null,
    @SerialName("rank_total") val rankTotal: Int = 0,
    @SerialName("manager_rank") val managerRank: Int? = null,
    @SerialName("manager_total") val managerTotal: Int = 0,
    val changes: List<EtfPeriodChangeDto> = emptyList(),
    val coverage: EtfStatsCoverageDto = EtfStatsCoverageDto(),
    val note: String = "",
)
