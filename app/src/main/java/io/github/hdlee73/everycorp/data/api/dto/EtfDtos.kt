package io.github.hdlee73.everycorp.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ETF 기초자료(순자산·상장좌수·운용사·총보수), 구성종목(PDF), ETF 간 관련성. 서버 /api/v1/etfs/* 응답.

@Serializable
data class EtfCheckDto(val label: String = "", val ok: Boolean = true, val detail: String = "")

@Serializable
data class EtfBasicsDto(
    @SerialName("base_date") val baseDate: String = "",
    /** 순자산총액(원). */
    @SerialName("net_assets") val netAssets: Long? = null,
    /** 순자산을 공시값이 아니라 NAV × 상장좌수로 계산했으면 true. */
    @SerialName("net_assets_computed") val netAssetsComputed: Boolean = false,
    @SerialName("listed_shares") val listedShares: Long? = null,
    @SerialName("market_cap") val marketCap: Long? = null,
    @SerialName("nav_krx") val navKrx: Double? = null,
    val manager: String = "",
    @SerialName("manager_source") val managerSource: String = "",
    /** 총보수(연 %). */
    @SerialName("total_fee") val totalFee: Double? = null,
    @SerialName("fee_source") val feeSource: String = "",
    @SerialName("listed_date") val listedDate: String = "",
    val replication: String = "",
    @SerialName("asset_class") val assetClass: String = "",
    val isin: String = "",
    val checks: List<EtfCheckDto> = emptyList(),
    val source: String = "",
)

@Serializable
data class EtfHoldingDto(
    val code: String = "",
    val name: String = "",
    val qty: Double? = null,
    val value: Long? = null,
    /** 구성비중(%). */
    val weight: Double? = null,
    /** STOCK · ETF · BOND · CASH · DERIVATIVE · FOREIGN · UNMATCHED */
    val kind: String = "UNMATCHED",
    @SerialName("kind_label") val kindLabel: String = "미매칭",
    /** 매칭 근거: code · isin · pref(우선주→보통주 회사) · name · type */
    val match: String = "",
    @SerialName("corp_code") val corpCode: String? = null,
    @SerialName("corp_name") val corpName: String? = null,
    @SerialName("stock_code") val stockCode: String? = null,
    @SerialName("etf_code") val etfCode: String? = null,
    @SerialName("etf_name") val etfName: String? = null,
) {
    val displayName: String get() = corpName?.takeIf { it.isNotBlank() && match != "pref" } ?: etfName?.takeIf { it.isNotBlank() } ?: name.ifBlank { code }
}

@Serializable
data class KindWeightDto(val kind: String = "", val label: String = "", val weight: Double = 0.0)

@Serializable
data class DonutSliceDto(val label: String = "", val weight: Double = 0.0)

@Serializable
data class EtfPortfolioDto(
    val code: String = "",
    val date: String = "",
    val source: String = "",
    /** 상위 구성종목만 받았으면 true(전체 PDF 아님). */
    val partial: Boolean = false,
    val count: Int = 0,
    @SerialName("total_weight") val totalWeight: Double = 0.0,
    @SerialName("unmatched_count") val unmatchedCount: Int = 0,
    @SerialName("by_kind") val byKind: List<KindWeightDto> = emptyList(),
    val donut: List<DonutSliceDto> = emptyList(),
    val holdings: List<EtfHoldingDto> = emptyList(),
    val note: String = "",
)

@Serializable
data class EtfRefDto(val code: String = "", val name: String = "")

@Serializable
data class EtfWeightRefDto(val code: String = "", val name: String = "", val manager: String = "", val weight: Double? = null)

@Serializable
data class EtfHoldingsSummaryDto(
    @SerialName("own_weight") val ownWeight: Double = 0.0,
    @SerialName("other_weight") val otherWeight: Double = 0.0,
    val own: List<EtfWeightRefDto> = emptyList(),
    val other: List<EtfWeightRefDto> = emptyList(),
)

@Serializable
data class EtfHolderDto(val code: String = "", val name: String = "", val manager: String = "", val weight: Double = 0.0, val own: Boolean = false)

@Serializable
data class EtfSimilarDto(
    val code: String = "",
    val name: String = "",
    val manager: String = "",
    @SerialName("overlap_weight") val overlapWeight: Double = 0.0,
    @SerialName("common_count") val commonCount: Int = 0,
)

@Serializable
data class EtfStoreProgressDto(val done: Int = 0, val total: Int = 0)

@Serializable
data class EtfStoreStatusDto(
    val stored: Int = 0,
    @SerialName("crawled_on") val crawledOn: String = "",
    val crawling: Boolean = false,
    val progress: EtfStoreProgressDto = EtfStoreProgressDto(),
)

@Serializable
data class EtfRelationsDto(
    val code: String = "",
    val manager: String = "",
    @SerialName("etf_holdings") val etfHoldings: EtfHoldingsSummaryDto = EtfHoldingsSummaryDto(),
    /** 각 경로는 [이 ETF, …, 이 ETF]. */
    val cycles: List<List<EtfRefDto>> = emptyList(),
    val mutual: List<EtfRefDto> = emptyList(),
    @SerialName("held_by") val heldBy: List<EtfHolderDto> = emptyList(),
    val similar: List<EtfSimilarDto> = emptyList(),
    val coverage: EtfStoreStatusDto = EtfStoreStatusDto(),
    val partial: Boolean = false,
    val note: String = "",
)

@Serializable
data class EtfOverlapSideDto(val code: String = "", val name: String = "", val date: String = "", val partial: Boolean = false)

@Serializable
data class EtfCommonDto(
    val key: String = "",
    val name: String = "",
    @SerialName("weight_a") val weightA: Double = 0.0,
    @SerialName("weight_b") val weightB: Double = 0.0,
)

@Serializable
data class EtfOverlapDto(
    val a: EtfOverlapSideDto = EtfOverlapSideDto(),
    val b: EtfOverlapSideDto = EtfOverlapSideDto(),
    @SerialName("overlap_weight") val overlapWeight: Double = 0.0,
    @SerialName("common_count") val commonCount: Int = 0,
    @SerialName("count_a") val countA: Int = 0,
    @SerialName("count_b") val countB: Int = 0,
    val common: List<EtfCommonDto> = emptyList(),
    val note: String = "",
)
