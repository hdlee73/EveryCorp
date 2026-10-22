package io.github.hdlee73.everycorp.data.kind

import io.github.hdlee73.everycorp.data.api.dto.DisclosureDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate

/**
 * KIND(kind.krx.co.kr) 공시 목록을 폰에서 직접 불러온다.
 * KIND는 해외·클라우드 데이터센터 IP(우리 서버)를 403으로 막지만 국내 통신사 IP는 허용하므로 서버를 거치지 않는다.
 */
class KindClient(private val http: OkHttpClient) {

    suspend fun disclosures(corpName: String, stockCode: String, years: Int, maxPages: Int = 10): List<DisclosureDto> =
        withContext(Dispatchers.IO) {
            val to = LocalDate.now()
            val from = to.minusDays(365L * years)
            val out = mutableListOf<DisclosureDto>()
            for (page in 1..maxPages) {
                val body = FormBody.Builder()
                    .add("method", "searchDetailsSub").add("currentPageSize", PAGE_SIZE.toString()).add("pageIndex", page.toString())
                    .add("orderMode", "1").add("orderStat", "D").add("forward", "details_sub").add("searchCodeType", "")
                    .add("repIsuSrtCd", "A$stockCode").add("searchCorpName", corpName)
                    .add("fromDate", from.toString()).add("toDate", to.toString())
                    .add("reportNm", "").add("bfrDsclsType", "on")
                    .build()
                val req = Request.Builder().url(URL).post(body)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://kind.krx.co.kr/disclosure/details.do?method=searchDetailsMain")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                val html = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("KIND 응답 오류 (HTTP ${r.code})")
                    r.body?.string().orEmpty()
                }
                val rows = KindParser.parse(html)
                out += KindParser.onlyCompany(rows, stockCode, corpName)
                if (rows.size < PAGE_SIZE) break
            }
            out
        }

    companion object {
        const val URL = "https://kind.krx.co.kr/disclosure/details.do"
        const val PAGE_SIZE = 100
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}

/** KIND 상세검색 결과 HTML → 공시 행. 서버 쪽 backend/app/parsers/kind.py 와 같은 규칙. */
object KindParser {
    data class Row(val dto: DisclosureDto, val companyCode: String?, val companyName: String)

    private val ROW = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val VIEWER = Regex("""openDisclsViewer\(\s*'(\d+)'\s*(?:,\s*'([^']*)')?""")
    private val COMPANY = Regex("""companysummary_open\(\s*'(\w+)'""")
    private val DATETIME = Regex("""(\d{4}-\d{2}-\d{2})(?:\s+(\d{2}:\d{2}))?""")
    private val VIEWER_ANCHOR = Regex("""<a\b[^>]*openDisclsViewer[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val COMPANY_ANCHOR = Regex("""<a\b[^>]*companysummary_open[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TITLE_ATTR = Regex("""title\s*=\s*"([^"]*)"""")
    private val TD = Regex("<td[^>]*>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAGS = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")

    fun viewerUrl(acptNo: String, docNo: String = "") =
        "https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptno=$acptNo&docno=$docNo&viewerhost=&viewerport="

    fun parse(html: String): List<Row> = ROW.findAll(html).mapNotNull { m -> parseRow(m.groupValues[1]) }.toList()

    private fun parseRow(tr: String): Row? {
        val viewer = VIEWER.find(tr) ?: return null
        val tds = TD.findAll(tr).map { text(it.groupValues[1]) }.toList()
        val dt = tds.firstNotNullOfOrNull { DATETIME.find(it) } ?: return null
        val anchor = VIEWER_ANCHOR.find(tr)
        val anchorOpen = anchor?.value?.substringBefore(">").orEmpty()
        val title = (TITLE_ATTR.find(anchorOpen)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let(::decode)
            ?: anchor?.groupValues?.get(1)?.let(::text).orEmpty()).replace(SPACES, " ").trim()
        val companyAnchor = COMPANY_ANCHOR.find(tr)
        val companyName = companyAnchor?.groupValues?.get(1)?.let(::text).orEmpty()
        val acpt = viewer.groupValues[1]
        val url = viewerUrl(acpt, viewer.groupValues[2])
        val dto = DisclosureDto(
            id = "KIND-$acpt", source = "KIND", date = dt.groupValues[1], time = dt.groupValues[2].ifBlank { null },
            title = title, submitter = tds.lastOrNull()?.ifBlank { null } ?: companyName.ifBlank { null }, remark = null, url = url, mobileUrl = url,
        )
        return Row(dto, COMPANY.find(tr)?.groupValues?.get(1), companyName)
    }

    /** 회사명 검색은 부분일치라 다른 회사가 섞인다 → 회사코드(종목코드 앞 5자리)로 다시 거른다. */
    fun onlyCompany(rows: List<Row>, stockCode: String, corpName: String): List<DisclosureDto> =
        rows.filter { if (it.companyCode != null) it.companyCode == stockCode.take(5) else it.companyName == corpName }.map { it.dto }

    private fun text(html: String) = decode(TAGS.replace(html, " ")).replace(SPACES, " ").trim()

    private fun decode(s: String) = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}

/** DART(서버) + KIND(앱) 공시를 합치고 거르는 순수 로직. */
object DisclosureMerge {
    private val PERIODIC = Regex("""^(\[[^\]]+]\s*)*(사업|반기|분기)보고서""")

    fun isPeriodic(title: String) = PERIODIC.containsMatchIn(title.trim())

    fun filterKind(items: List<DisclosureDto>, query: String, periodicOnly: Boolean): List<DisclosureDto> =
        items.filter { (!periodicOnly || isPeriodic(it.title)) && (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }

    /** 공시일자·시각·접수번호 내림차순. 같은 id 는 하나만. */
    fun merge(dart: List<DisclosureDto>, kind: List<DisclosureDto>): List<DisclosureDto> =
        (dart + kind).distinctBy { it.id }.sortedWith(
            compareByDescending<DisclosureDto> { it.date }.thenByDescending { it.time.orEmpty() }.thenByDescending { it.id.substringAfter('-') },
        )
}
