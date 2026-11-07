package cn.edu.haut.campus.core.academic

import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import cn.edu.haut.campus.core.model.PresetMirrorNode
import cn.edu.haut.campus.core.model.ProxyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

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
                  "digest": "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
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
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            info.expectedSha256Hex
        )
        assertEquals(
            "https://github.com/yellowpeachxgp/day-in-school/releases/download/v0.9.0/day-in-school-v0.9.0-release.apk",
            info.apkDownloadUrl
        )
        assertEquals("", NetworkProxyConfig.normalizePrefixUrl("http://insecure-mirror.example.com"))
    }

    @Test
    fun `preset campus clubs and qq channels catalog is populated`() {
        assertTrue(PresetCampusCommunityCatalog.defaultQqChannels.isEmpty())
        assertEquals(3, PresetCampusCommunityCatalog.defaultCampusClubs.size)
        assertEquals(
            setOf("314623763", "789860526", "1043885481"),
            PresetCampusCommunityCatalog.defaultCampusClubs.map { it.qqGroupNumber }.toSet()
        )
        assertTrue(PresetCampusCommunityCatalog.defaultCompetitions.size >= 14)
        assertTrue(
            PresetCampusCommunityCatalog.defaultCompetitions.all {
                it.officialWebsiteUrl.startsWith("https://")
            }
        )
        assertEquals(
            cn.edu.haut.campus.core.model.CampusMajorTrack.CS_AI_SOFTWARE,
            cn.edu.haut.campus.core.model.CampusMajorTrack.inferFromMajorText("软件工程")
        )
        assertEquals(
            cn.edu.haut.campus.core.model.CampusMajorTrack.EE_AUTOMATION_IOT,
            cn.edu.haut.campus.core.model.CampusMajorTrack.inferFromMajorText("电气工程及其自动化")
        )
    }

    @Test
    fun `network proxy config rewrites github urls and builds fallback chain`() {
        val rawApkUrl = "https://github.com/yellowpeachxgp/day-in-school/releases/download/v0.11.2/day-in-school-v0.11.2-release.apk"
        val presetCfg = NetworkProxyConfig(
            mode = ProxyMode.PRESET_MIRROR,
            presetMirrorKey = PresetMirrorNode.GHFAST_TOP.key,
            autoFallbackMirror = true
        )
        val candidates = presetCfg.buildGitHubDownloadCandidateUrls(rawApkUrl)
        assertEquals("https://ghfast.top/$rawApkUrl", candidates.first())
        assertTrue(candidates.contains("https://gh-proxy.com/$rawApkUrl"))
        assertTrue(candidates.contains(rawApkUrl))

        val customCfg = NetworkProxyConfig(
            mode = ProxyMode.CUSTOM_REVERSE_PROXY,
            customReverseProxyPrefix = "gh.mycampus.cn",
            autoFallbackMirror = false
        )
        val customCandidates = customCfg.buildGitHubDownloadCandidateUrls(rawApkUrl)
        assertEquals("https://gh.mycampus.cn/$rawApkUrl", customCandidates.first())
        assertEquals(2, customCandidates.size)
    }

    @Test
    fun `network proxy config resolves http and socks java net proxies and cloud urls`() {
        val httpCfg = NetworkProxyConfig(
            mode = ProxyMode.HTTP_PROXY,
            proxyHost = "127.0.0.1",
            proxyPort = 7890
        )
        val httpProxy = httpCfg.resolveJavaNetProxy()
        assertNotNull(httpProxy)
        assertEquals(Proxy.Type.HTTP, httpProxy?.type())

        val socksCfg = NetworkProxyConfig(
            mode = ProxyMode.SOCKS_PROXY,
            proxyHost = "127.0.0.1",
            proxyPort = 10808
        )
        val socksProxy = socksCfg.resolveJavaNetProxy()
        assertNotNull(socksProxy)
        assertEquals(Proxy.Type.SOCKS, socksProxy?.type())

        val directCfg = NetworkProxyConfig(mode = ProxyMode.DIRECT)
        assertNull(directCfg.resolveJavaNetProxy())

        val cloudCfg = NetworkProxyConfig(customCloudApiBaseUrl = "https://api.haut-cloud.cn/")
        assertEquals(
            "https://api.haut-cloud.cn/v1/timetable/sync",
            cloudCfg.resolveCloudServiceUrl("/v1/timetable/sync")
        )
    }

    @Test
    fun `extractTagFromReleaseUrl parses github latest 302 redirect tag`() {
        val location = "https://github.com/yellowpeachxgp/day-in-school/releases/tag/v0.11.2"
        assertEquals("v0.11.2", GitHubReleaseUpdater.extractTagFromReleaseUrl(location))
    }

    @Test
    fun `formatCleanChangelog strips markdown headers and full changelog footer`() {
        val raw = """
            ## 核心更新
            - **新增失物招领与二手教材广场**：支持寻物招领、同校区疑似线索匹配、课表一键挂单出书与随身防丢码
            - **优化版本更新日志**：在设置检查更新与启动更新弹窗中完整展示当前版本更新内容
            **Full Changelog**: https://github.com/yellowpeachxgp/day-in-school/compare/v0.19.12...v0.20.0
        """.trimIndent()
        val formatted = GitHubReleaseUpdater.formatCleanChangelog(raw)
        assertTrue(formatted.contains("【核心更新】"))
        assertTrue(formatted.contains("新增失物招领与二手教材广场"))
        assertFalse(formatted.contains("Full Changelog"))
        assertFalse(formatted.contains("**"))
    }

    @Test
    fun `campus moments starts empty and QR includes a quiet zone`() {
        val snapshot = cn.edu.haut.campus.core.model.CampusMomentsSnapshot()
        assertTrue(snapshot.posts.isEmpty())
        assertTrue(snapshot.bookListings.isEmpty())
        assertTrue(snapshot.antiLostTags.isEmpty())
        val tag = cn.edu.haut.campus.core.model.PresetCampusMomentsCatalog.defaultAntiLostTags.first()
        assertEquals("https://campus.yellowpeachxgp.com/claim/HAUT-TAG-7E2A9B", tag.claimWebUrl())
        val matrix = tag.generateVisualMatrix()
        assertTrue(matrix.size > 21)
        assertEquals(matrix.size, matrix[0].size)
        assertFalse(matrix[0][0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `release notes rejects oversized body before full allocation`() {
        GitHubReleaseUpdater.readBoundedReleaseNotes(java.io.ByteArrayInputStream(ByteArray(65537)))
    }
}
