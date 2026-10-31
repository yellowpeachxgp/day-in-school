package cn.edu.haut.campus.core.academic

import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseUpdaterTest {

    @Test
    fun `semantic version comparison handles v prefix and debug suffix`() {
        assertTrue(GitHubReleaseUpdater.isRemoteVersionNewer("0.8.0", "v0.9.0"))
        assertTrue(GitHubReleaseUpdater.isRemoteVersionNewer("0.8.0-debug", "v0.8.1"))
        assertTrue(GitHubReleaseUpdater.isRemoteVersionNewer("v0.9.0", "v1.0.0"))
        assertFalse(GitHubReleaseUpdater.isRemoteVersionNewer("0.9.0", "v0.9.0"))
        assertFalse(GitHubReleaseUpdater.isRemoteVersionNewer("0.9.0", "v0.8.0"))
    }

    @Test
    fun `parseReleaseJson selects release apk asset and detects newer tag`() {
        val sampleJson = """
            {
              "tag_name": "v0.9.0",
              "name": "日在校园 v0.9.0",
              "body": "新增社团模块、预置QQ频道与在线更新",
              "published_at": "2026-11-01T04:00:00Z",
              "html_url": "https://github.com/yellowpeachxgp/day-in-school/releases/tag/v0.9.0",
              "assets": [
                {
                  "name": "day-in-school-v0.9.0-debug.apk",
                  "size": 16200000,
                  "browser_download_url": "https://github.com/yellowpeachxgp/day-in-school/releases/download/v0.9.0/day-in-school-v0.9.0-debug.apk"
                },
                {
                  "name": "day-in-school-v0.9.0-release.apk",
                  "size": 12400000,
                  "browser_download_url": "https://github.com/yellowpeachxgp/day-in-school/releases/download/v0.9.0/day-in-school-v0.9.0-release.apk"
                }
              ]
            }
        """.trimIndent()

        val info = GitHubReleaseUpdater.parseReleaseJson(sampleJson, "0.8.0")
        assertTrue(info.hasNewerVersion)
        assertEquals("v0.9.0", info.tagName)
        assertEquals("day-in-school-v0.9.0-release.apk", info.apkFileName)
        assertEquals(
            "https://github.com/yellowpeachxgp/day-in-school/releases/download/v0.9.0/day-in-school-v0.9.0-release.apk",
            info.apkDownloadUrl
        )
    }

    @Test
    fun `preset campus clubs and qq channels catalog is populated`() {
        assertTrue(PresetCampusCommunityCatalog.defaultQqChannels.size >= 4)
        assertTrue(PresetCampusCommunityCatalog.defaultCampusClubs.size >= 15)
        assertTrue(PresetCampusCommunityCatalog.defaultCampusClubs.all { it.qqGroupNumber.isNotBlank() })
    }
}
