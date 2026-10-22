package io.github.hdlee73.everycorp.data.api

import io.github.hdlee73.everycorp.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Interceptor
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

    /** X-App-Token 은 서버가 켜 둔 경우에만 필요하다. 비밀이 아니라 남용 억제용(ARCHITECTURE.md 6장). */
    fun appTokenInterceptor(token: String): Interceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .header("User-Agent", "EveryCorp/${BuildConfig.VERSION_NAME}")
            .apply { if (token.isNotBlank()) header("X-App-Token", token) }
            .build()
        chain.proceed(req)
    }

    fun okHttp(token: String = BuildConfig.APP_TOKEN): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(appTokenInterceptor(token))
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
        is IOException -> ApiException("NETWORK", "서버에 연결하지 못했습니다. 네트워크를 확인해 주세요.")
        else -> ApiException("UNKNOWN", t.message ?: "알 수 없는 오류가 발생했습니다.")
    }
}
