package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ImportChangeType
import cn.edu.haut.campus.core.model.ImportCourseCandidate
import cn.edu.haut.campus.core.model.ImportDiffEntry
import cn.edu.haut.campus.core.model.ImportDiffPreview

object ImportDiffEngine {

    /**
     * 教务/文件导入差异预览引擎：
     * - 对比现有课表与待导入排课
     * - 显式输出 ADDED / MODIFIED / CONFLICT_USER_MODIFIED / UNCHANGED
     * - 用户手动编辑过的课程/规则默认标记为保留用户修改，绝不静默覆盖
     */
    fun computeDiff(
        termId: String,
        source: CourseSource,
        existingCourses: List<Course>,
        existingRules: List<CourseScheduleRule>,
        incomingCandidates: List<ImportCourseCandidate>
    ): ImportDiffPreview {
        val courseById = existingCourses.associateBy { it.id }
        val entries = mutableListOf<ImportDiffEntry>()
        val matchedRuleIds = mutableSetOf<String>()

        for (candidate in incomingCandidates) {
            // 优先匹配相同星期 + 重叠节次的规则或同名课程规则
            val matchingRule = existingRules.firstOrNull { rule ->
                rule.termId == termId &&
                    rule.weekday == candidate.weekday &&
                    rule.startPeriod == candidate.startPeriod &&
                    rule.endPeriod == candidate.endPeriod &&
                    courseById[rule.courseId]?.name == candidate.courseName
            } ?: existingRules.firstOrNull { rule ->
                rule.termId == termId &&
                    rule.weekday == candidate.weekday &&
                    periodsOverlap(rule.startPeriod, rule.endPeriod, candidate.startPeriod, candidate.endPeriod)
            }

            if (matchingRule == null) {
                entries.add(
                    ImportDiffEntry(
                        changeType = ImportChangeType.ADDED,
                        incoming = candidate,
                        existingCourse = null,
                        existingRule = null,
                        summary = "新增《${candidate.courseName}》(${candidate.weekday.ChineseLabel} ${candidate.startPeriod}-${candidate.endPeriod}节 · ${candidate.rawRoom})"
                    )
                )
            } else {
                matchedRuleIds.add(matchingRule.id)
                val existingCourse = courseById[matchingRule.courseId]
                val isIdentical = existingCourse?.name == candidate.courseName &&
                    existingCourse.teacher == candidate.teacher &&
                    matchingRule.rawRoom == candidate.rawRoom &&
                    matchingRule.weeks == candidate.weeks &&
                    matchingRule.startPeriod == candidate.startPeriod &&
                    matchingRule.endPeriod == candidate.endPeriod

                val hasUserModification = (existingCourse?.userModified == true) || matchingRule.userModified

                when {
                    isIdentical -> {
                        entries.add(
                            ImportDiffEntry(
                                changeType = ImportChangeType.UNCHANGED,
                                incoming = candidate,
                                existingCourse = existingCourse,
                                existingRule = matchingRule,
                                summary = "《${candidate.courseName}》无变化"
                            )
                        )
                    }
                    hasUserModification -> {
                        entries.add(
                            ImportDiffEntry(
                                changeType = ImportChangeType.CONFLICT_USER_MODIFIED,
                                incoming = candidate,
                                existingCourse = existingCourse,
                                existingRule = matchingRule,
                                summary = "检测到本地手动修改：本地为《${existingCourse?.name ?: ""}》@${matchingRule.rawRoom}，导入源为《${candidate.courseName}》@${candidate.rawRoom}（默认保留本地修改）",
                                keepUserEditByDefault = true
                            )
                        )
                    }
                    else -> {
                        entries.add(
                            ImportDiffEntry(
                                changeType = ImportChangeType.MODIFIED,
                                incoming = candidate,
                                existingCourse = existingCourse,
                                existingRule = matchingRule,
                                summary = "更新《${candidate.courseName}》：教室 ${matchingRule.rawRoom} → ${candidate.rawRoom}，周次 ${WeeksSetParser.format(matchingRule.weeks)} → ${WeeksSetParser.format(candidate.weeks)}"
                            )
                        )
                    }
                }
            }
        }

        for (unmatchedRule in existingRules.filter { it.termId == termId && it.id !in matchedRuleIds }) {
            val course = courseById[unmatchedRule.courseId]
            if (course?.source == CourseSource.MANUAL || unmatchedRule.userModified || course?.userModified == true) {
                entries.add(
                    ImportDiffEntry(
                        changeType = ImportChangeType.CONFLICT_USER_MODIFIED,
                        incoming = null,
                        existingCourse = course,
                        existingRule = unmatchedRule,
                        summary = "保留用户自建/已改课程《${course?.name ?: "自定义课程"}》(${unmatchedRule.rawRoom})",
                        keepUserEditByDefault = true
                    )
                )
            } else {
                entries.add(
                    ImportDiffEntry(
                        changeType = ImportChangeType.REMOVED_FROM_REMOTE,
                        incoming = null,
                        existingCourse = course,
                        existingRule = unmatchedRule,
                        summary = "导入源未包含《${course?.name ?: ""}》(${unmatchedRule.rawRoom})"
                    )
                )
            }
        }

        return ImportDiffPreview(
            termId = termId,
            source = source,
            entries = entries,
            addedCount = entries.count { it.changeType == ImportChangeType.ADDED },
            modifiedCount = entries.count { it.changeType == ImportChangeType.MODIFIED },
            conflictUserEditCount = entries.count { it.changeType == ImportChangeType.CONFLICT_USER_MODIFIED },
            unchangedCount = entries.count { it.changeType == ImportChangeType.UNCHANGED }
        )
    }

    private fun periodsOverlap(s1: Int, e1: Int, s2: Int, e2: Int): Boolean {
        return maxOf(s1, s2) <= minOf(e1, e2)
    }
}

val java.time.DayOfWeek.ChineseLabel: String
    get() = when (this) {
        java.time.DayOfWeek.MONDAY -> "周一"
        java.time.DayOfWeek.TUESDAY -> "周二"
        java.time.DayOfWeek.WEDNESDAY -> "周三"
        java.time.DayOfWeek.THURSDAY -> "周四"
        java.time.DayOfWeek.FRIDAY -> "周五"
        java.time.DayOfWeek.SATURDAY -> "周六"
        java.time.DayOfWeek.SUNDAY -> "周日"
    }
