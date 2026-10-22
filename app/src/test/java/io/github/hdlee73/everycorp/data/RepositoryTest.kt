package io.github.hdlee73.everycorp.data

import io.github.hdlee73.everycorp.data.api.ApiException
import io.github.hdlee73.everycorp.data.api.NetworkModule
import io.github.hdlee73.everycorp.data.repo.CorpRepository
import io.github.hdlee73.everycorp.data.repo.DisclosurePeriod
import io.github.hdlee73.everycorp.data.repo.DisclosureSource
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: CorpRepository

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val client = NetworkModule.okHttp(token = "secret-token", tokenHost = server.hostName)
        repo = CorpRepository(NetworkModule.api(NetworkModule.retrofit(server.url("/").toString(), client)))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun searchSendsTokenAndQuery() = runTest {
        server.enqueue(MockResponse().setBody("""{"query":"삼성","count":1,"items":[{"corp_code":"00126380","corp_name":"삼성전자","listing":"LISTED","badge":"상장"}]}"""))
        val items = repo.search(" 삼성 ").getOrThrow()
        assertEquals("삼성전자", items.single().corpName)
        val req = server.takeRequest()
        assertEquals("secret-token", req.getHeader("X-App-Token"))
        assertTrue(req.path!!.startsWith("/api/v1/corps/search?q="))
    }

    @Test fun blankSearchSkipsNetwork() = runTest {
        assertTrue(repo.search("  ").getOrThrow().isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun disclosureQuery() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        repo.disclosures("00126380", DisclosurePeriod.Y10, DisclosureSource.Kind, query = "배당").getOrThrow()
        val path = server.takeRequest().path!!
        assertTrue(path.contains("years=10"))
        assertTrue(path.contains("source=kind"))
        assertTrue(path.contains("page=1"))
    }

    @Test fun serverErrorMapsToApiException() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_LISTED","message":"비상장 법인은 주가 정보를 제공하지 않습니다."}}"""))
        val e = repo.quote("00258801").exceptionOrNull() as ApiException
        assertEquals("NOT_LISTED", e.code)
        assertEquals(404, e.httpStatus)
        assertEquals("비상장 법인은 주가 정보를 제공하지 않습니다.", e.message)
    }

    @Test fun networkFailure() = runTest {
        server.shutdown()
        val e = repo.overview("00126380").exceptionOrNull() as ApiException
        assertEquals("NETWORK", e.code)
    }
}
