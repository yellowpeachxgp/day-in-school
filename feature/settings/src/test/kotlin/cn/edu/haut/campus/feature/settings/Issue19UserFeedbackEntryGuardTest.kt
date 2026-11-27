package cn.edu.haut.campus.feature.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #19 用户交流 QQ 群/频道配置入口的源码级守卫（issue 原文：设立QQ群或QQ频道供用户讨论问题）。
 *
 * 修复点位于 Compose 界面内联逻辑，W0-10 约束不改生产代码、不新增生产接缝，
 * 因此采用与 MainActivityLaunchIntentGuardTest 相同的源码扫描方式锁定：
 * - 哨兵键 "__app_user_feedback_qq_group__" / "__app_user_feedback_channel__" 的读写路径；
 * - 用户覆盖 → 云端 officialChannels 回退的两级 Elvis 链与空白穿透；
 * - 频道链接拒绝把 github.com 当作 QQ 频道展示；
 * - 配置入口对话框、复制/打开动作与 GitHub Issue 兜底文案。
 */
class Issue19UserFeedbackEntryGuardTest {

    private fun repoRoot(): File {
        var dir = File(".").canonicalFile
        repeat(8) {
            if (File(dir, "feature/settings/src/main").isDirectory && File(dir, "core").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("无法定位仓库根目录（feature/settings 单元测试工作目录应在模块内）")
    }

    private fun normalizedSource(): String {
        val file = File(
            repoRoot(),
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/CampusHubScreens.kt"
        )
        assertTrue("被守卫的界面源文件必须存在: " + file.absolutePath, file.isFile)
        // 空白归一化后比对，免疫换行/缩进重排；语义符号序列仍被严格锁定
        return file.readText().replace(Regex("\\s+"), " ")
    }

    @Test
    fun sentinelKeysHaveBothReadAndWritePaths() {
        val text = normalizedSource()
        val qqKey = "\"__app_user_feedback_qq_group__\""
        val channelKey = "\"__app_user_feedback_channel__\""

        val qqOccurrences = Regex(Regex.escape(qqKey)).findAll(text).count()
        val channelOccurrences = Regex(Regex.escape(channelKey)).findAll(text).count()
        assertTrue(
            "QQ 群哨兵键必须同时出现在读取与保存两条路径（至少 2 处），实际 " + qqOccurrences,
            qqOccurrences >= 2
        )
        assertTrue(
            "频道哨兵键必须同时出现在读取与保存两条路径（至少 2 处），实际 " + channelOccurrences,
            channelOccurrences >= 2
        )
    }

    @Test
    fun qqGroupResolutionIsUserOverrideThenCloudFallbackWithBlankPassthrough() {
        val text = normalizedSource()
        // 用户覆盖优先、空白穿透到云端目录、云端空白再穿透为「未配置」——整条 Elvis 链逐符号锁定
        val chain = "prefs.customClubGroupNumbers[\"__app_user_feedback_qq_group__\"]" +
            "?.takeIf { it.isNotBlank() }" +
            " ?: catalog.officialChannels.userFeedbackQqGroup.takeIf { it.isNotBlank() }"
        assertTrue(
            "用户交流 QQ 群解析链必须是：用户覆盖(非空) → 云端 officialChannels(非空)；缺失说明 #19 已回退",
            text.contains(chain)
        )
    }

    @Test
    fun channelResolutionExcludesGithubFallbackFromQqChannelDisplay() {
        val text = normalizedSource()
        val chain = "prefs.customChannelUrls[\"__app_user_feedback_channel__\"]" +
            "?.takeIf { it.isNotBlank() }" +
            " ?: catalog.officialChannels.userFeedbackChannelUrl.takeIf {" +
            " it.isNotBlank() && !it.contains(\"github.com\", ignoreCase = true) }"
        assertTrue(
            "QQ 频道解析链必须是：用户覆盖(非空) → 云端链接(非空且非 github.com)；" +
                "把 GitHub 兜底链接当 QQ 频道展示属于 #19 回归",
            text.contains(chain)
        )
    }

    @Test
    fun configDialogWritesTrimmedValuesThroughRepositoryWithSentinelKeys() {
        val text = normalizedSource()
        assertTrue(
            "配置对话框必须通过 repository 保存 QQ 群号（trim 后）",
            text.contains("repository.saveCustomClubGroupNumber(\"__app_user_feedback_qq_group__\", qqInput.trim())")
        )
        assertTrue(
            "配置对话框必须通过 repository 保存频道链接（trim 后）",
            text.contains("repository.saveCustomChannelUrl(\"__app_user_feedback_channel__\", chInput.trim())")
        )
        assertTrue(
            "配置入口对话框标题必须存在",
            text.contains("Text(\"配置用户交流 QQ 群与频道\")")
        )
    }

    @Test
    fun feedbackCardCopyKeepsIssue19PromiseAndHonestFallback() {
        val text = normalizedSource()
        val requiredCopy = listOf(
            "Day in School 用户交流与问题反馈",            // 专卡标题：用户交流入口存在
            "独立于校内社团招新群",                          // 与校内社团招新群严格区分的诚实声明
            "用户交流 QQ 群：待云端同步或点击下方绑定群号",        // 未配置时的诚实提示而非伪造群号
            "GitHub Issue 反馈：https://github.com/yellowpeachxgp/day-in-school/issues", // 无频道时的兜底反馈通道
            "复制交流群号",
            "打开QQ加群",
            "打开QQ频道",
            "配置交流群/频道"                               // 首次配置入口文案
        )
        for (copy in requiredCopy) {
            assertTrue("缺失 #19 用户交流入口文案: " + copy, text.contains(copy))
        }
    }

    @Test
    fun feedbackEntryStaysInsideCommunityScreenElseBranch() {
        // 结构守卫：反馈专卡位于「交流与反馈」页签分支，且在微信/防冒充声明卡之前渲染
        val text = normalizedSource()
        val feedbackIdx = text.indexOf("Day in School 用户交流与问题反馈")
        val wechatIdx = text.indexOf("微信公众号、小程序与防冒充声明")
        assertTrue("反馈专卡文案必须存在", feedbackIdx >= 0)
        assertTrue("公众号声明卡文案必须存在（结构参照物）", wechatIdx >= 0)
        assertTrue(
            "反馈专卡必须先于公众号/防冒充声明卡渲染（issue #19 入口顺位）",
            feedbackIdx < wechatIdx
        )
        // 交流与反馈页签仍存在（修复把原「校园频道」页签改造为交流入口）
        assertTrue(text.contains("\"交流与反馈\""))
    }
}
