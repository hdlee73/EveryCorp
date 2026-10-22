package io.github.hdlee73.everycorp.data.update

import io.github.hdlee73.everycorp.data.api.dto.LatestReleaseDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 네트워크·안드로이드 API 를 쓰지 않는 업데이트 판단 로직(JVM 단위 테스트 대상). */
object UpdateLogic {
    /** "v1.2.3" / "1.2.3-debug" → [1,2,3]. 해석할 수 없으면 null. */
    fun parseVersion(raw: String): List<Int>? {
        val core = raw.trim().removePrefix("v").removePrefix("V").takeWhile { it.isDigit() || it == '.' }
        if (core.isEmpty()) return null
        return core.split('.').map { it.toIntOrNull() ?: return null }
    }

    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest) ?: return false
        val b = parseVersion(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 릴리스 본문에서 "## ... 변경 내용" 구역만. 없으면 본문 전체. */
    fun extractChanges(body: String): String {
        val lines = body.lines()
        val start = lines.indexOfFirst { it.startsWith("#") && it.contains("변경 내용") }
        if (start < 0) return body.trim()
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("#") } ?: lines.size
        return lines.subList(start + 1, end).joinToString("\n").trim()
    }

    /** GitHub `releases/latest` 응답 → 앱 모델. APK 가 없는 릴리스는 apkUrl=null. */
    fun parseGithubRelease(text: String, json: Json = Json { ignoreUnknownKeys = true }): LatestReleaseDto? {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val assets: JsonArray = o["assets"]?.jsonArray ?: JsonArray(emptyList())
        fun asset(suffix: String): JsonObject? =
            assets.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(suffix, true) == true }
        val apk = asset(".apk")
        val sha = asset(".apk.sha256")
        return LatestReleaseDto(
            version = tag.removePrefix("v"),
            publishedAt = o["published_at"]?.jsonPrimitive?.contentOrNull,
            notes = extractChanges(o["body"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            releaseUrl = o["html_url"]?.jsonPrimitive?.contentOrNull,
            apkUrl = apk?.get("browser_download_url")?.jsonPrimitive?.contentOrNull,
            apkSize = apk?.get("size")?.jsonPrimitive?.longOrNull,
            sha256Url = sha?.get("browser_download_url")?.jsonPrimitive?.contentOrNull,
        )
    }

    /** `sha256sum` 출력("<해시>  <파일명>")에서 해시만. */
    fun parseSha256(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { Regex("[0-9a-f]{64}").matches(it) }

    /** 강제 업데이트: 현재 버전이 최소 지원 버전보다 낮을 때. */
    fun isForced(current: String, minSupported: String): Boolean = isNewer(minSupported, current)
}
