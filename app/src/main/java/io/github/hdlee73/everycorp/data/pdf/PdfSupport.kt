package io.github.hdlee73.everycorp.data.pdf

/** DownloadManager 와 분리해 JVM 단위 테스트가 가능한 순수 함수들. */
object PdfSupport {
    private val MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

    /** 파일 앞부분이 PDF 인지. DART 가 PDF 대신 HTML 안내 페이지를 줄 때 걸러낸다. */
    fun isPdf(head: ByteArray): Boolean =
        head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    /** 파일 이름에 쓸 수 없는 문자를 `_` 로 바꾸고 .pdf 를 보장한다. */
    fun safeFileName(raw: String, fallback: String = "report"): String {
        val base = raw.trim().removeSuffix(".pdf").replace(Regex("""[\\/:*?"<>|\s]+"""), "_").trim('_', '.')
        return (base.ifEmpty { fallback }.take(120)) + ".pdf"
    }

    /** 서버 중계 주소. `pdf_proxy_path` 는 `/api/v1/reports/pdf?...` 처럼 슬래시로 시작한다. */
    fun proxyUrl(baseUrl: String, proxyPath: String): String =
        baseUrl.trimEnd('/') + "/" + proxyPath.trimStart('/')
}
