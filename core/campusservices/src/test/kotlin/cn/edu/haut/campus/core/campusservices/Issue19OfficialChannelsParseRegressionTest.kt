package cn.edu.haut.campus.core.campusservices

import cn.edu.haut.campus.core.model.CampusOfficialChannelsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * #19 用户交流 QQ 群/频道配置入口的云端目录解析回归
 * （issue 原文：设立QQ群或QQ频道供用户讨论问题；修复承诺「待云端同步」群号/频道）。
 *
 * 界面读取链第二级是 catalog.officialChannels.userFeedbackQqGroup / userFeedbackChannelUrl，
 * 它们由 CampusCloudOidcHelper.parseCampusContentJsonString 从云端 campus-content 快照解析。
 * 本测试锁定解析契约中现有 CampusServicesUnitTest 未覆盖的缺口：
 * - 频道链接只接受 https，其余一律回退 GitHub Issue 兜底（防 javascript:/intent: 注入打开）；
 * - snake_case 与 camelCase 两种键名、snake 优先；
 * - official_channels 缺失或字段为 null/空白时的默认值与 PENDING 归一化。
 */
class Issue19OfficialChannelsParseRegressionTest {

    private val githubFallback = "https://github.com/yellowpeachxgp/day-in-school/issues"

    private fun payloadWithOfficialChannels(officialChannelsJson: String?): String {
        val section = if (officialChannelsJson == null) {
            ""
        } else {
            "\"official_channels\": " + officialChannelsJson + ","
        }
        return """
            {
              "school_id": "haut",
              "version": 3,
              "updated_at": "2026-11-10T00:00:00Z",
              $section
              "clubs": []
            }
        """.trimIndent()
    }

    private fun parseOfficialChannels(officialChannelsJson: String?): CampusOfficialChannelsConfig {
        val catalog = CampusCloudOidcHelper.parseCampusContentJsonString(payloadWithOfficialChannels(officialChannelsJson))
        assertNotNull("version>=3 的合法快照必须解析成功", catalog)
        return catalog!!.officialChannels
    }

    @Test
    fun nonHttpsChannelUrlFallsBackToGithubIssuesEntry() {
        // http 明文、无协议裸串、javascript:/intent: scheme、空串都不得进入 UI 的「打开QQ频道」
        for (unsafe in listOf(
            "http://pd.qq.com/s/insecure",
            "pd.qq.com/no-scheme",
            "javascript:alert(1)",
            "intent://pd.qq.com/x",
            ""
        )) {
            val parsed = parseOfficialChannels(
                """{"user_feedback_channel_url": "$unsafe", "user_feedback_qq_group": "987654321"}"""
            )
            assertEquals(
                "非 https 频道链接必须回退 GitHub Issue 兜底: <" + unsafe + ">",
                githubFallback,
                parsed.userFeedbackChannelUrl
            )
        }
    }

    @Test
    fun httpsChannelUrlIsPreservedExactlyAndSchemeMatchIsCaseInsensitive() {
        val exact = parseOfficialChannels(
            """{"user_feedback_channel_url": "https://pd.qq.com/s/dayinschool"}"""
        )
        assertEquals("https://pd.qq.com/s/dayinschool", exact.userFeedbackChannelUrl)

        val upper = parseOfficialChannels(
            """{"user_feedback_channel_url": "HTTPS://pd.qq.com/s/dayinschool"}"""
        )
        assertEquals("HTTPS://pd.qq.com/s/dayinschool", upper.userFeedbackChannelUrl)
    }

    @Test
    fun camelCaseKeysAreAcceptedAndSnakeCaseWinsWhenBothPresent() {
        val camel = parseOfficialChannels(
            """{"userFeedbackQqGroup": "111222333", "userFeedbackChannelUrl": "https://pd.qq.com/s/camel"}"""
        )
        assertEquals("111222333", camel.userFeedbackQqGroup)
        assertEquals("https://pd.qq.com/s/camel", camel.userFeedbackChannelUrl)

        val both = parseOfficialChannels(
            """{
                "user_feedback_qq_group": "987654321",
                "userFeedbackQqGroup": "111222333",
                "user_feedback_channel_url": "https://pd.qq.com/s/snake",
                "userFeedbackChannelUrl": "https://pd.qq.com/s/camel"
            }"""
        )
        assertEquals("snake_case 必须优先于 camelCase", "987654321", both.userFeedbackQqGroup)
        assertEquals("https://pd.qq.com/s/snake", both.userFeedbackChannelUrl)
    }

    @Test
    fun missingOfficialChannelsYieldsSafeDefaultsMatchingModel() {
        val parsed = parseOfficialChannels(null)
        // 与 core/model 的数据类默认值逐字段一致——UI 依赖该兜底显示 GitHub Issue 反馈入口
        assertEquals("", parsed.userFeedbackQqGroup)
        assertEquals(githubFallback, parsed.userFeedbackChannelUrl)
        assertEquals("", parsed.wechatOfficialAccountName)
        assertEquals("PENDING", parsed.wechatOfficialAccountStatus)

        val modelDefault = CampusOfficialChannelsConfig()
        assertEquals("模型默认频道链接必须是 GitHub Issue 兜底", githubFallback, modelDefault.userFeedbackChannelUrl)
        assertEquals("", modelDefault.userFeedbackQqGroup)
        assertEquals("PENDING", modelDefault.wechatOfficialAccountStatus)
    }

    @Test
    fun nullAndBlankFieldsNormalizeToFallbacks() {
        // JSON null 字面量 → 走 fallback 链；空白群号保持为空（UI 据此显示「待云端同步或绑定群号」而非伪造群号）
        val parsed = parseOfficialChannels(
            """{
                "user_feedback_qq_group": null,
                "user_feedback_channel_url": null,
                "wechat_official_account_status": "  "
            }"""
        )
        assertEquals("", parsed.userFeedbackQqGroup)
        assertEquals(githubFallback, parsed.userFeedbackChannelUrl)
        assertEquals("空白公众号状态必须归一化为 PENDING", "PENDING", parsed.wechatOfficialAccountStatus)

        val blankGroup = parseOfficialChannels("""{"user_feedback_qq_group": ""}""")
        assertEquals("空白群号必须保持空白，不得伪造", "", blankGroup.userFeedbackQqGroup)
    }

    @Test
    fun officialChannelsCamelCaseObjectKeyIsAlsoAccepted() {
        // 对象本身同时支持 officialChannels 驼峰键（云端快照兼容路径）
        val payload = """
            {
              "school_id": "haut",
              "version": 3,
              "updated_at": "2026-11-10T00:00:00Z",
              "officialChannels": {
                "userFeedbackQqGroup": "444555666",
                "userFeedbackChannelUrl": "https://pd.qq.com/s/camelobj"
              },
              "clubs": []
            }
        """.trimIndent()
        val catalog = CampusCloudOidcHelper.parseCampusContentJsonString(payload)
        assertNotNull(catalog)
        assertEquals("444555666", catalog!!.officialChannels.userFeedbackQqGroup)
        assertEquals("https://pd.qq.com/s/camelobj", catalog.officialChannels.userFeedbackChannelUrl)
        assertEquals("PENDING", catalog.officialChannels.wechatOfficialAccountStatus)
    }
}
