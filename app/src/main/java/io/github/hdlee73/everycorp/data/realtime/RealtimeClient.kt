package io.github.hdlee73.everycorp.data.realtime

import io.github.hdlee73.everycorp.BuildConfig
import io.github.hdlee73.everycorp.data.api.NetworkModule
import io.github.hdlee73.everycorp.data.api.dto.RealtimeMessageDto
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 서버 실시간 중계(WebSocket /api/v1/realtime) 클라이언트. 키움 키·토큰은 서버에만 있고 앱은 시세·호가만 받는다.
 * - [stream]을 collect 하는 동안만 연결한다(화면이 보일 때만). 취소하면 연결을 닫는다.
 * - 끊기면 1→2→4…최대 30초 간격으로 다시 연결하고, 그 사이 [RealtimeMessageDto] type="disconnected"를 내보낸다.
 * - 앱 토큰은 NetworkModule 인터셉터가 같은 서버 호스트일 때만 붙인다(웹소켓 핸드셰이크도 인터셉터를 지난다).
 */
class RealtimeClient(base: OkHttpClient, private val baseUrl: String = BuildConfig.API_BASE_URL) {
    private val client: OkHttpClient = base.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val url: String get() = realtimeUrl(baseUrl)

    fun stream(code: String): Flow<RealtimeMessageDto> = flow {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            var gotData = false
            // 네트워크 단절 등 연결 쪽 오류만 삼킨다(catch 는 위쪽 흐름의 예외만 잡는다). 잠시 쉬고 다시 연결.
            connectOnce(code)
                .catch { }
                .collect { m ->
                    if (m.type != "connected") gotData = true
                    emit(m)
                }
            emit(RealtimeMessageDto(type = "disconnected"))
            if (gotData) backoff = 1_000L
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun connectOnce(code: String): Flow<RealtimeMessageDto> = callbackFlow {
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(subscribeMessage(code))
                trySend(RealtimeMessageDto(type = "connected"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parse(text)?.let { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                channel.close(t)
            }
        })
        awaitClose { ws.close(1000, "bye") }
    }

    companion object {
        /** http(s)://host → 같은 호스트의 /api/v1/realtime (OkHttp 는 http 주소로도 웹소켓을 연다). */
        fun realtimeUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/realtime"

        fun subscribeMessage(code: String): String = """{"action":"subscribe","codes":["${code.filter { it.isLetterOrDigit() }.take(6)}"]}"""

        fun parse(text: String): RealtimeMessageDto? =
            runCatching { NetworkModule.json.decodeFromString(RealtimeMessageDto.serializer(), text) }.getOrNull()
    }
}
