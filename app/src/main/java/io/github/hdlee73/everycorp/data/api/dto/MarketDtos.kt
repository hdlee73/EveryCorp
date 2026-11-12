package io.github.hdlee73.everycorp.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 키움 REST API 기반 시장 데이터(조회 전용). 계좌·잔고·주문 정보는 서버도 앱도 다루지 않는다.

/** 캔들 하나. [t]는 일봉 이상 "YYYY-MM-DD", 분봉 "YYYY-MM-DDTHH:MM". */
@Serializable
data class CandleDto(val t: String, val o: Long, val h: Long, val l: Long, val c: Long, val v: Long = 0) {
    /** 공시 마커 위치 비교용 YYYYMMDD. */
    val day: String get() = t.take(10).replace("-", "")
}

@Serializable
data class CandlesDto(
    @SerialName("stock_code") val stockCode: String = "",
    val name: String = "",
    /** m1 | m5 | m15 | m30 | m60 | D | W | M */
    val period: String = "D",
    val source: String = "",
    val candles: List<CandleDto> = emptyList(),
    val warning: String? = null,
)

@Serializable
data class OrderLevelDto(val price: Long, val qty: Long = 0)

@Serializable
data class OrderbookDto(
    @SerialName("stock_code") val stockCode: String = "",
    val source: String = "",
    /** 매도호가 1호가(가장 낮은 매도가)부터 */
    val asks: List<OrderLevelDto> = emptyList(),
    /** 매수호가 1호가(가장 높은 매수가)부터 */
    val bids: List<OrderLevelDto> = emptyList(),
    @SerialName("total_ask") val totalAsk: Long? = null,
    @SerialName("total_bid") val totalBid: Long? = null,
    val time: String? = null,
)

// ───── 수급 ─────
@Serializable
data class InvestorRowDto(
    val date: String,
    val close: Long? = null,
    @SerialName("change_rate") val changeRate: Double? = null,
    val individual: Long? = null,
    val foreign: Long? = null,
    val institution: Long? = null,
    @SerialName("financial_invest") val financialInvest: Long? = null,
    val insurance: Long? = null,
    val trust: Long? = null,
    val bank: Long? = null,
    val pension: Long? = null,
    @SerialName("private_fund") val privateFund: Long? = null,
    @SerialName("etc_corp") val etcCorp: Long? = null,
)

@Serializable
data class ProgramRowDto(
    val date: String,
    @SerialName("sell_amt") val sellAmt: Long? = null,
    @SerialName("buy_amt") val buyAmt: Long? = null,
    /** 백만원 */
    @SerialName("net_amt") val netAmt: Long? = null,
    @SerialName("net_qty") val netQty: Long? = null,
)

@Serializable
data class ShortRowDto(
    val date: String,
    val close: Long? = null,
    val volume: Long? = null,
    @SerialName("short_qty") val shortQty: Long? = null,
    /** 거래량 대비 공매도 비중(%) */
    @SerialName("short_ratio") val shortRatio: Double? = null,
    @SerialName("short_amt") val shortAmt: Long? = null,
    @SerialName("short_avg_price") val shortAvgPrice: Long? = null,
)

@Serializable
data class CreditRowDto(
    val date: String,
    val new: Long? = null,
    val repay: Long? = null,
    val balance: Long? = null,
    val change: Long? = null,
    @SerialName("balance_rate") val balanceRate: Double? = null,
    @SerialName("supply_rate") val supplyRate: Double? = null,
)

@Serializable
data class FlowSumDto(
    val individual: Long? = null,
    val foreign: Long? = null,
    val institution: Long? = null,
    @SerialName("program_amt") val programAmt: Long? = null,
)

@Serializable
data class FlowSummaryDto(val d5: FlowSumDto = FlowSumDto(), val d20: FlowSumDto = FlowSumDto())

@Serializable
data class FlowsDto(
    @SerialName("stock_code") val stockCode: String = "",
    val source: String = "",
    val investors: List<InvestorRowDto> = emptyList(),
    val program: List<ProgramRowDto> = emptyList(),
    val short: List<ShortRowDto> = emptyList(),
    val credit: List<CreditRowDto> = emptyList(),
    val summary: FlowSummaryDto = FlowSummaryDto(),
    val warnings: List<String> = emptyList(),
)

// ───── 랭킹 스크리너 ─────
@Serializable
data class RankingItemDto(
    val rank: Int,
    @SerialName("stock_code") val stockCode: String,
    val name: String = "",
    @SerialName("corp_code") val corpCode: String? = null,
    @SerialName("corp_name") val corpName: String = "",
    val price: Long? = null,
    val change: Long? = null,
    @SerialName("change_rate") val changeRate: Double? = null,
    val direction: String = "FLAT",
    val volume: Long? = null,
    /** 백만원(거래량·거래대금 순위일 때) */
    @SerialName("trade_value") val tradeValue: Long? = null,
    val tags: List<String> = emptyList(),
    val disclosures: List<DisclosureDto> = emptyList(),
    @SerialName("disclosure_count") val disclosureCount: Int = 0,
) {
    fun toCorp(): CorpItemDto? = corpCode?.let { CorpItemDto(corpCode = it, corpName = corpName.ifBlank { name }, stockCode = stockCode, listing = "LISTED") }
}

@Serializable
data class RankingsDto(
    val kind: String = "volume",
    @SerialName("kind_label") val kindLabel: String = "",
    val market: String = "all",
    val source: String = "",
    @SerialName("tag_days") val tagDays: Int = 0,
    val items: List<RankingItemDto> = emptyList(),
    val warnings: List<String> = emptyList(),
)

// ───── 실시간(서버 WebSocket) ─────
/** 서버가 보내는 실시간 메시지 하나. type: status | trade | orderbook | subscribed | error | pong */
@Serializable
data class RealtimeMessageDto(
    val type: String,
    val code: String? = null,
    /** status: kiwoom(실시간) | polling(3초 간격 현재가) */
    val mode: String? = null,
    val price: Long? = null,
    val change: Long? = null,
    @SerialName("change_rate") val changeRate: Double? = null,
    val direction: String? = null,
    val volume: Long? = null,
    @SerialName("acc_volume") val accVolume: Long? = null,
    val open: Long? = null,
    val high: Long? = null,
    val low: Long? = null,
    val strength: Double? = null,
    val time: String? = null,
    val asks: List<OrderLevelDto> = emptyList(),
    val bids: List<OrderLevelDto> = emptyList(),
    @SerialName("total_ask") val totalAsk: Long? = null,
    @SerialName("total_bid") val totalBid: Long? = null,
    val message: String? = null,
)
