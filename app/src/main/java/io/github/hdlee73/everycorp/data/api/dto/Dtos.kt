package io.github.hdlee73.everycorp.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// docs/API_SPEC.md 의 JSON 과 1:1 대응. 서버가 필드를 추가해도 깨지지 않도록 Json{ignoreUnknownKeys=true}로 읽는다.

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail? = null)

@Serializable
data class ApiErrorDetail(val code: String = "", val message: String = "")

// ───────── 앱 정보 ─────────
@Serializable
data class AppMetaDto(
    @SerialName("app_name") val appName: String = "EveryCorp",
    @SerialName("app_name_ko") val appNameKo: String = "",
    val author: AuthorDto = AuthorDto(),
    val repository: String = "",
    @SerialName("min_supported_version") val minSupportedVersion: String = "1.0.0",
    @SerialName("current_version") val currentVersion: String? = null,
    @SerialName("update_available") val updateAvailable: Boolean = false,
    @SerialName("force_update") val forceUpdate: Boolean = false,
    val latest: LatestReleaseDto? = null,
)

@Serializable
data class AuthorDto(val name: String = "", val email: String = "")

@Serializable
data class LatestReleaseDto(
    val version: String,
    @SerialName("published_at") val publishedAt: String? = null,
    val notes: String = "",
    @SerialName("release_url") val releaseUrl: String? = null,
    @SerialName("apk_url") val apkUrl: String? = null,
    @SerialName("apk_size") val apkSize: Long? = null,
    @SerialName("sha256_url") val sha256Url: String? = null,
)

// ───────── 검색 ─────────
@Serializable
data class CorpSearchResponse(val query: String = "", val count: Int = 0, val items: List<CorpItemDto> = emptyList())

@Serializable
data class CorpItemDto(
    @SerialName("corp_code") val corpCode: String,
    @SerialName("corp_name") val corpName: String,
    @SerialName("corp_name_eng") val corpNameEng: String? = null,
    @SerialName("stock_code") val stockCode: String? = null,
    val listing: String = "UNLISTED",
    val badge: String = "",
) {
    val isListed: Boolean get() = listing == "LISTED"
}

// ───────── 탭 1: 기업개황 ─────────
@Serializable
data class OverviewDto(
    @SerialName("corp_code") val corpCode: String,
    @SerialName("corp_name") val corpName: String,
    @SerialName("corp_name_eng") val corpNameEng: String? = null,
    @SerialName("stock_code") val stockCode: String? = null,
    val listing: String = "UNLISTED",
    val market: String = "OTHER",
    val badge: String = "",
    @SerialName("ceo_name") val ceoName: String? = null,
    @SerialName("industry_code") val industryCode: String? = null,
    val established: String? = null,
    @SerialName("fiscal_month") val fiscalMonth: String? = null,
    val address: String? = null,
    val homepage: String? = null,
    val phone: String? = null,
    @SerialName("largest_shareholder") val largestShareholder: ShareholderDto = ShareholderDto(),
    val financials: FinancialsDto = FinancialsDto(),
) {
    val isListed: Boolean get() = listing == "LISTED"
}

@Serializable
data class ShareholderDto(
    val available: Boolean = false,
    val name: String? = null,
    @SerialName("related_count") val relatedCount: Int = 0,
    @SerialName("total_ratio") val totalRatio: Double? = null,
    val display: String = "",
    val holders: List<HolderDto> = emptyList(),
    val basis: BasisDto? = null,
)

@Serializable
data class HolderDto(val name: String, val relation: String? = null, val shares: Long? = null, val ratio: Double? = null)

@Serializable
data class BasisDto(
    @SerialName("report_name") val reportName: String = "",
    @SerialName("rcept_no") val rceptNo: String = "",
    @SerialName("bsns_year") val bsnsYear: String = "",
    @SerialName("reprt_code") val reprtCode: String = "",
    @SerialName("period_end") val periodEnd: String = "",
)

@Serializable
data class FinancialsDto(
    val available: Boolean = false,
    val message: String? = null,
    @SerialName("fs_div") val fsDiv: String? = null,
    @SerialName("fs_label") val fsLabel: String? = null,
    /** '매출액' 또는 '영업수익' — 서버가 account_nm 으로 판별해 내려준다. */
    @SerialName("revenue_label") val revenueLabel: String = "매출액",
    val currency: String = "KRW",
    val basis: BasisDto? = null,
    @SerialName("latest_basis") val latestBasis: BasisDto? = null,
    /** 기간 비교표(재무상태: 최직근 분기 + 3개 연말, 손익: 분기·누적 + 직전 연간). 금액은 원 단위. */
    val tables: FinTablesDto? = null,
    /** 최근 5개 연도 매출·영업이익·순이익·부채비율 추이(원 단위, 부채비율은 %). */
    val trend: TrendDto? = null,
    @SerialName("balance_sheet") val balanceSheet: BalanceSheetDto? = null,
    @SerialName("income_statement") val incomeStatement: IncomeStatementDto? = null,
)

@Serializable
data class TrendDto(val unit: String = "원", val years: List<Int> = emptyList(), val series: List<TrendSeriesDto> = emptyList())

@Serializable
data class TrendSeriesDto(val key: String, val label: String, val values: List<Double?> = emptyList())

@Serializable
data class FinTablesDto(val unit: String = "억원", val balance: FinTableDto = FinTableDto(), val income: FinTableDto = FinTableDto())

@Serializable
data class FinTableDto(val columns: List<FinColumnDto> = emptyList(), val rows: List<FinRowDto> = emptyList())

@Serializable
data class FinColumnDto(val label: String = "", val sub: String? = null)

/** [unit]이 "%"이면 비율, 아니면 원 단위 금액. */
@Serializable
data class FinRowDto(val label: String = "", val unit: String? = null, val values: List<Double?> = emptyList())

@Serializable
data class BalanceSheetDto(
    @SerialName("total_assets") val totalAssets: AmountDto = AmountDto(),
    @SerialName("total_liabilities") val totalLiabilities: AmountDto = AmountDto(),
    @SerialName("total_equity") val totalEquity: AmountDto = AmountDto(),
)

@Serializable
data class IncomeStatementDto(
    val revenue: AmountDto = AmountDto(),
    @SerialName("operating_income") val operatingIncome: AmountDto = AmountDto(),
    @SerialName("net_income") val netIncome: AmountDto = AmountDto(),
)

@Serializable
data class AmountDto(
    val label: String = "",
    @SerialName("account_nm") val accountNm: String? = null,
    val amount: Long? = null,
    val display: String? = null,
    @SerialName("prev_amount") val prevAmount: Long? = null,
    @SerialName("prev2_amount") val prev2Amount: Long? = null,
)

// ───────── 탭 2: 감사/검토보고서 ─────────
@Serializable
data class AuditReportsResponse(
    @SerialName("corp_code") val corpCode: String = "",
    @SerialName("corp_name") val corpName: String = "",
    val from: String = "",
    val to: String = "",
    val count: Int = 0,
    val partial: Boolean = false,
    val reports: List<AuditReportDto> = emptyList(),
)

@Serializable
data class AuditReportDto(
    val id: String,
    val title: String,
    /** AUDIT | REVIEW */
    val kind: String,
    val consolidated: Boolean = false,
    val period: String = "",
    /** 해당 기간 정기보고서의 감사(검토)인·의견. 없으면 빈 문자열. */
    val auditor: String = "",
    val opinion: String = "",
    @SerialName("period_label") val periodLabel: String = "",
    @SerialName("rcept_dt") val rceptDt: String = "",
    @SerialName("rcp_no") val rcpNo: String,
    @SerialName("dcm_no") val dcmNo: String,
    /** STANDALONE | ATTACHMENT */
    val origin: String = "",
    @SerialName("source_report_name") val sourceReportName: String = "",
    @SerialName("pdf_url") val pdfUrl: String,
    @SerialName("pdf_proxy_path") val pdfProxyPath: String,
    @SerialName("viewer_url") val viewerUrl: String,
    @SerialName("file_name") val fileName: String,
)

// ───────── 탭 3: 공시목록 ─────────
@Serializable
data class DisclosuresResponse(
    @SerialName("corp_code") val corpCode: String = "",
    val from: String = "",
    val to: String = "",
    val years: Int = 1,
    val source: String = "all",
    @SerialName("kind_supported") val kindSupported: Boolean = true,
    val counts: DisclosureCounts = DisclosureCounts(),
    val page: Int = 1,
    val size: Int = 50,
    @SerialName("has_next") val hasNext: Boolean = false,
    val items: List<DisclosureDto> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class DisclosureCounts(val dart: Int = 0, val kind: Int = 0, val total: Int = 0)

@Serializable
data class DisclosureDto(
    val id: String,
    /** DART | KIND */
    val source: String,
    val date: String,
    val time: String? = null,
    val title: String,
    val submitter: String? = null,
    val remark: String? = null,
    val url: String,
    @SerialName("mobile_url") val mobileUrl: String,
)

// ───────── 탭 4: 주가 ─────────
@Serializable
data class QuoteDto(
    @SerialName("stock_code") val stockCode: String,
    val name: String = "",
    val price: Long? = null,
    val change: Long = 0,
    @SerialName("change_rate") val changeRate: Double = 0.0,
    /** UP | DOWN | FLAT */
    val direction: String = "FLAT",
    val open: Long? = null,
    val high: Long? = null,
    val low: Long? = null,
    val volume: Long? = null,
    @SerialName("week52_high") val week52High: Long? = null,
    @SerialName("week52_low") val week52Low: Long? = null,
    /** 투자지표(키움 응답일 때만) */
    val per: Double? = null,
    val pbr: Double? = null,
    val eps: Double? = null,
    val bps: Double? = null,
    val roe: Double? = null,
    /** KIWOOM | NAVER | KRX */
    val source: String = "",
    val delayed: Boolean = false,
    @SerialName("market_status") val marketStatus: String? = null,
    @SerialName("fetched_at") val fetchedAt: String? = null,
)

@Serializable
data class ChartDto(
    @SerialName("stock_code") val stockCode: String,
    val name: String = "",
    val from: String = "",
    val to: String = "",
    val source: String = "",
    val points: List<ChartPointDto> = emptyList(),
    @SerialName("range_52w") val range52w: Range52wDto = Range52wDto(),
    @SerialName("last_date") val lastDate: String? = null,
)

@Serializable
data class ChartPointDto(
    val date: String,
    val close: Long? = null,
    val high: Long? = null,
    val low: Long? = null,
    val volume: Long? = null,
)

@Serializable
data class Range52wDto(
    val high: Long? = null,
    @SerialName("high_date") val highDate: String? = null,
    val low: Long? = null,
    @SerialName("low_date") val lowDate: String? = null,
)

// ───────── 탭 5: 채권·단기증권 ─────────
@Serializable
data class DebtSecuritiesDto(
    @SerialName("corp_code") val corpCode: String = "",
    val available: Boolean = false,
    val message: String? = null,
    @SerialName("unit_note") val unitNote: String = "",
    /** 데이터 출처 문구 */
    val source: String = "",
    val basis: BasisDto? = null,
    val sections: List<DebtSectionDto> = emptyList(),
    /** 이번 보고서에 잔액 표가 없는 종류(예: 단기사채) */
    val missing: List<String> = emptyList(),
    @SerialName("viewer_url") val viewerUrl: String? = null,
)

@Serializable
data class DebtSectionDto(
    /** CORP_BOND | COMMERCIAL_PAPER | SHORT_TERM_BOND | HYBRID_CAPITAL | CONTINGENT_CAPITAL */
    val key: String,
    val title: String,
    val total: Long? = null,
    @SerialName("total_display") val totalDisplay: String? = null,
    val rows: List<DebtRowDto> = emptyList(),
)

@Serializable
data class DebtRowDto(
    val label: String,
    /** 종목별 잔액 표(공공데이터포털)일 때만 채워진다. */
    val name: String? = null,
    val maturity: String? = null,
    val rate: String? = null,
    @SerialName("is_total") val isTotal: Boolean = false,
    val total: Long? = null,
    @SerialName("issue_limit") val issueLimit: Long? = null,
    @SerialName("remaining_limit") val remainingLimit: Long? = null,
    val buckets: List<DebtBucketDto> = emptyList(),
)

@Serializable
data class DebtBucketDto(val key: String, val label: String, val amount: Long? = null)

// ───────── 뉴스 ─────────
@Serializable
data class NewsDto(
    @SerialName("corp_code") val corpCode: String = "",
    val query: String = "",
    /** NAVER | GOOGLE */
    val source: String = "",
    val count: Int = 0,
    val items: List<NewsItemDto> = emptyList(),
)

@Serializable
data class NewsItemDto(
    val title: String,
    val summary: String = "",
    val publisher: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val url: String,
)

// ───────── 당일 분봉 ─────────
@Serializable
data class IntradayDto(
    @SerialName("stock_code") val stockCode: String = "",
    val name: String = "",
    val source: String = "",
    /** 분봉 기준일(장 시작 전·휴장일이면 직전 거래일) */
    val date: String? = null,
    val points: List<IntradayPointDto> = emptyList(),
)

@Serializable
data class IntradayPointDto(val time: String, val price: Long, val volume: Long = 0)


// ───────── 최대주주 현황·변동 ─────────
@Serializable
data class ShareholdersDto(
    val available: Boolean = false,
    val message: String? = null,
    /** 비상장 법인의 감사보고서 주석에서 뽑은 추정값이면 true. */
    val estimated: Boolean = false,
    val source: String = "",
    val basis: BasisDto? = null,
    val holders: List<ShareholderRowDto> = emptyList(),
    val total: ShareholderRowDto? = null,
    /** 주식 종류(보통주·우선주 등)별 합계. */
    val totals: List<ShareholderRowDto> = emptyList(),
    val largest: String? = null,
    val changes: List<HolderChangeDto> = emptyList(),
    @SerialName("viewer_url") val viewerUrl: String? = null,
)

@Serializable
data class ShareholderRowDto(
    val name: String = "",
    val relation: String = "",
    @SerialName("stock_kind") val stockKind: String = "",
    @SerialName("begin_shares") val beginShares: Long? = null,
    @SerialName("begin_ratio") val beginRatio: Double? = null,
    @SerialName("end_shares") val endShares: Long? = null,
    @SerialName("end_ratio") val endRatio: Double? = null,
    val note: String = "",
)

@Serializable
data class HolderChangeDto(
    val date: String = "",
    val name: String = "",
    val shares: Long? = null,
    val ratio: Double? = null,
    val cause: String = "",
    val note: String = "",
)


// ───────── ETF ─────────
@Serializable
data class EtfSearchResponse(val query: String = "", val count: Int = 0, val items: List<EtfItemDto> = emptyList())

@Serializable
data class EtfItemDto(
    val code: String,
    val name: String,
    @SerialName("index_name") val indexName: String = "",
    val price: Long? = null,
    @SerialName("change_rate") val changeRate: Double? = null,
    val nav: Double? = null,
    val volume: Long? = null,
)

@Serializable
data class EtfDetailDto(
    val code: String,
    val name: String = "",
    @SerialName("index_name") val indexName: String = "",
    @SerialName("tax_type") val taxType: String = "",
    @SerialName("par_value") val parValue: Long? = null,
    val quote: QuoteDto? = null,
    val daily: List<EtfDailyDto> = emptyList(),
    val source: String = "",
)

@Serializable
data class EtfDailyDto(
    val date: String,
    val close: Long? = null,
    @SerialName("change_rate") val changeRate: Double? = null,
    val volume: Long? = null,
    val nav: Double? = null,
    /** 종가가 NAV보다 높으면 +(프리미엄), 낮으면 -(할인). 단위 %. */
    val disparity: Double? = null,
    @SerialName("tracking_error") val trackingError: Double? = null,
)
