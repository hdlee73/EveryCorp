package io.github.hdlee73.everycorp.data.api

import io.github.hdlee73.everycorp.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import io.github.hdlee73.everycorp.data.api.dto.ApiErrorBody

/** 서버가 준 {"error":{"code","message"}} 또는 네트워크 오류를 앱에서 쓰기 쉬운 형태로. */
class ApiException(val code: String, override val message: String, val httpStatus: Int? = null) : IOException(message)

object NetworkModule {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 앱 서버 주소의 호스트. 이 호스트로 가는 요청에만 앱 토큰을 붙인다(GitHub 등 다른 곳에 새지 않게). */
    val apiHost: String = BuildConfig.API_BASE_URL.toHttpUrlOrNull()?.host.orEmpty()

    /** X-App-Token 은 서버가 켜 둔 경우에만 필요하다. 비밀이 아니라 남용 억제용(ARCHITECTURE.md 6장). */
    fun appTokenInterceptor(token: String, tokenHost: String = apiHost): Interceptor = Interceptor { chain ->
        val builder = chain.request().newBuilder().header("User-Agent", "EveryCorp/${BuildConfig.VERSION_NAME}")
        if (token.isNotBlank() && chain.request().url.host == tokenHost) builder.header("X-App-Token", token)
        chain.proceed(builder.build())
    }

    fun okHttp(token: String = BuildConfig.APP_TOKEN, tokenHost: String = apiHost): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(appTokenInterceptor(token, tokenHost))
        .connectTimeout(10, TimeUnit.SECONDS)
        // 서버 첫 요청이 느릴 수 있다(콜드 스타트, DART 크롤링). 감사보고서 목록이 가장 오래 걸린다.
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun retrofit(baseUrl: String = BuildConfig.API_BASE_URL, client: OkHttpClient = okHttp()): Retrofit =
        Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    fun api(retrofit: Retrofit = retrofit()): EveryCorpApi = retrofit.create(EveryCorpApi::class.java)

    /** 연결 실패 안내. 개발용 기본 주소(10.0.2.2)면 "서버 주소 미설정"임을 알려 원인을 바로 알 수 있게 한다. */
    fun networkMessage(baseUrl: String): String {
        val host = baseUrl.toHttpUrlOrNull()?.host.orEmpty()
        return if (host == "10.0.2.2" || host == "localhost" || host.isEmpty()) {
            "서버 주소가 설정되지 않은 앱입니다. 서버(API_BASE_URL)를 배포하고 앱을 다시 빌드해야 검색·조회가 됩니다."
        } else {
            "서버($host)에 연결하지 못했습니다. 인터넷 연결을 확인하거나, 서버가 켜져 있는지 확인해 주세요."
        }
    }

    /** 예외를 [ApiException] 으로 통일(한글 안내 메시지 포함). */
    fun toApiException(t: Throwable): ApiException = when (t) {
        is ApiException -> t
        is HttpException -> {
            val body = runCatching {
                t.response()?.errorBody()?.byteStream()?.use { json.decodeFromStream<ApiErrorBody>(it) }
            }.getOrNull()?.error
            ApiException(
                code = body?.code ?: "HTTP_${t.code()}",
                message = body?.message?.takeIf { it.isNotBlank() } ?: "서버 오류가 발생했습니다 (${t.code()}).",
                httpStatus = t.code(),
            )
        }
        is IOException -> ApiException("NETWORK", networkMessage(BuildConfig.API_BASE_URL))
        else -> ApiException("UNKNOWN", t.message ?: "알 수 없는 오류가 발생했습니다.")
    }
}
