package io.github.hdlee73.everycorp.data.prefs

import android.content.Context
import io.github.hdlee73.everycorp.data.api.NetworkModule
import io.github.hdlee73.everycorp.data.api.dto.CorpItemDto
import kotlinx.serialization.builtins.ListSerializer

/** 목록(관심기업·최근 검색)을 JSON 문자열로 바꾸고 되돌리는 순수 로직. */
object CorpListCodec {
    private val serializer = ListSerializer(CorpItemDto.serializer())

    fun encode(items: List<CorpItemDto>): String = NetworkModule.json.encodeToString(serializer, items)

    fun decode(raw: String?): List<CorpItemDto> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { NetworkModule.json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())

    /** 맨 앞에 넣고 같은 기업은 하나만, 최대 [max]개. */
    fun pushFront(list: List<CorpItemDto>, item: CorpItemDto, max: Int): List<CorpItemDto> =
        (listOf(item) + list.filterNot { it.corpCode == item.corpCode }).take(max)
}

/** 관심기업·최근 검색·마지막으로 본 버전. 사용자 기기에만 저장된다. */
class AppPrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("everycorp", Context.MODE_PRIVATE)

    var favorites: List<CorpItemDto>
        get() = CorpListCodec.decode(p.getString("favorites", null))
        set(v) = p.edit().putString("favorites", CorpListCodec.encode(v)).apply()

    var recents: List<CorpItemDto>
        get() = CorpListCodec.decode(p.getString("recents", null))
        set(v) = p.edit().putString("recents", CorpListCodec.encode(v)).apply()

    var lastSeenVersion: String?
        get() = p.getString("last_seen_version", null)
        set(v) = p.edit().putString("last_seen_version", v).apply()

    /** 관심기업 새 공시 알림(기본 켬). */
    var notifyDisclosures: Boolean
        get() = p.getBoolean("notify_disclosures", true)
        set(v) = p.edit().putBoolean("notify_disclosures", v).apply()

    var dismissedUpdate: String?
        get() = p.getString("dismissed_update", null)
        set(v) = p.edit().putString("dismissed_update", v).apply()
}
