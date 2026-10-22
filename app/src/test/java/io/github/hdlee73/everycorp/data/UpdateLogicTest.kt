package io.github.hdlee73.everycorp.data

import io.github.hdlee73.everycorp.data.update.UpdateLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    @Test fun versions() {
        assertTrue(UpdateLogic.isNewer("1.0.1", "1.0.0"))
        assertTrue(UpdateLogic.isNewer("v1.10.0", "1.9.9"))
        assertTrue(UpdateLogic.isNewer("1.0.0.1", "1.0.0"))
        assertFalse(UpdateLogic.isNewer("1.0.0", "1.0.0-debug"))
        assertFalse(UpdateLogic.isNewer("abc", "1.0.0"))
        assertEquals(listOf(1, 2, 3), UpdateLogic.parseVersion("v1.2.3-debug"))
    }

    @Test fun forced() {
        assertTrue(UpdateLogic.isForced("1.0.0", "1.1.0"))
        assertFalse(UpdateLogic.isForced("1.1.0", "1.1.0"))
    }

    @Test fun releaseNotes() {
        val body = "# EveryCorp v1.1.0 변경 내용\n- 공시 검색\n- 차트 개선\n\n## 설치 방법\n- APK"
        assertEquals("- 공시 검색\n- 차트 개선", UpdateLogic.extractChanges(body))
        assertEquals("그냥 본문", UpdateLogic.extractChanges("그냥 본문"))
    }

    @Test fun githubRelease() {
        val r = UpdateLogic.parseGithubRelease(
            """{"tag_name":"v1.1.0","html_url":"https://github.com/hdlee73/EveryCorp/releases/tag/v1.1.0","published_at":"2026-11-02T03:00:00Z",
              "body":"# EveryCorp v1.1.0 변경 내용\n- a","assets":[
              {"name":"everycorp-v1.1.0.apk","browser_download_url":"https://x/apk","size":10},
              {"name":"everycorp-v1.1.0.apk.sha256","browser_download_url":"https://x/sha","size":1}]}""",
        )!!
        assertEquals("1.1.0", r.version)
        assertEquals("https://x/apk", r.apkUrl)
        assertEquals("https://x/sha", r.sha256Url)
        assertEquals("- a", r.notes)
        assertNull(UpdateLogic.parseGithubRelease("{}"))
        assertNull(UpdateLogic.parseGithubRelease("not json"))
    }

    @Test fun sha256() {
        val h = "a".repeat(64)
        assertEquals(h, UpdateLogic.parseSha256("${h.uppercase()}  everycorp.apk\n"))
        assertNull(UpdateLogic.parseSha256("short  file"))
    }
}
