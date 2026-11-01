package cn.edu.haut.campus.core.packages.catalog

import cn.edu.haut.campus.core.model.CapabilityEvidence
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.packages.manifest.PackageVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线目录与本地覆盖合并用例。
 *
 * UNVERIFIED：学校条目为合成档案，不代表任何真实学校的目录收录或验证结论。
 */
class SchoolCatalogTest {

    private fun profile(id: String, name: String): SchoolProfile = SchoolProfile(
        id = SchoolId(id),
        displayName = name,
        aliases = listOf("alias-" + id),
        province = "河南",
        verification = CapabilityEvidence(level = VerificationLevel.MOCK_TESTED),
        academicSystems = emptyList(),
        campuses = emptyList()
    )

    private fun entry(id: String, name: String): CatalogEntry = CatalogEntry(
        school = profile(id, name),
        packageId = "cn.example." + id,
        packageVersion = PackageVersion("1.0.0")
    )

    @Test
    fun 本地覆盖按稳定学校ID生效() {
        val catalog = OverlaySchoolCatalog(
            catalog = listOf(entry("school-a", "合成甲大学"), entry("school-b", "合成乙大学")),
            overrides = listOf(
                SchoolOverride(
                    schoolId = SchoolId("school-a"),
                    displayName = "教师自定义名称",
                    additionalAliases = listOf("本地别名")
                )
            )
        )
        val resolved = catalog.find(SchoolId("school-a"))
        assertNotNull("UNVERIFIED：目录条目必须可按 ID 找到", resolved)
        assertEquals("UNVERIFIED：覆盖校名必须生效", "教师自定义名称", resolved!!.school.displayName)
        assertTrue(
            "UNVERIFIED：覆盖别名必须追加而不是替换",
            resolved.school.aliases.containsAll(listOf("alias-school-a", "本地别名"))
        )
        assertEquals(
            "UNVERIFIED：未覆盖的学校保持目录原值",
            "合成乙大学",
            catalog.find(SchoolId("school-b"))?.school?.displayName
        )
    }

    @Test
    fun 目录升级不丢失用户覆盖() {
        val overrides = listOf(SchoolOverride(schoolId = SchoolId("school-a"), displayName = "升级后仍保留"))
        val original = OverlaySchoolCatalog(
            catalog = listOf(entry("school-a", "合成甲大学")),
            overrides = overrides
        )
        val upgraded = original.upgrade(
            listOf(
                entry("school-a", "合成甲大学（新版目录名）"),
                entry("school-c", "合成丙大学")
            )
        )
        assertEquals(
            "UNVERIFIED：升级后用户自定义名称必须保留",
            "升级后仍保留",
            upgraded.find(SchoolId("school-a"))?.school?.displayName
        )
        assertEquals("UNVERIFIED：新目录条目必须出现", 2, upgraded.entries().size)
        assertEquals("UNVERIFIED：覆盖数量不因升级变化", 1, upgraded.overrides().size)
        assertTrue("UNVERIFIED：升级不应产生孤儿覆盖", upgraded.orphanOverrides().isEmpty())
    }

    @Test
    fun 目录里暂时消失的学校保留孤儿覆盖并在回归时再生效() {
        val original = OverlaySchoolCatalog(
            catalog = listOf(entry("school-a", "合成甲大学"), entry("school-b", "合成乙大学")),
            overrides = listOf(SchoolOverride(schoolId = SchoolId("school-b"), displayName = "本地乙大"))
        )
        val shrunk = original.upgrade(listOf(entry("school-a", "合成甲大学")))
        assertEquals("UNVERIFIED：孤儿覆盖必须被保留", 1, shrunk.orphanOverrides().size)
        assertEquals(
            "UNVERIFIED：孤儿覆盖必须记录学校 ID",
            "school-b",
            shrunk.orphanOverrides().single().schoolId.value
        )
        assertNull("UNVERIFIED：学校不在目录时不能编造条目", shrunk.find(SchoolId("school-b")))

        val restored = shrunk.upgrade(listOf(entry("school-a", "合成甲大学"), entry("school-b", "合成乙大学")))
        assertEquals(
            "UNVERIFIED：学校回归目录后覆盖必须自动再生效",
            "本地乙大",
            restored.find(SchoolId("school-b"))?.school?.displayName
        )
        assertTrue("UNVERIFIED：覆盖再生效后不再有孤儿", restored.orphanOverrides().isEmpty())
    }

    @Test
    fun 隐藏的学校不出现在列表但可按ID查找() {
        val catalog = OverlaySchoolCatalog(
            catalog = listOf(entry("school-a", "合成甲大学"), entry("school-b", "合成乙大学")),
            overrides = listOf(SchoolOverride(schoolId = SchoolId("school-b"), hidden = true))
        )
        assertEquals(
            "UNVERIFIED：隐藏项不得出现在列表",
            listOf("school-a"),
            catalog.entries().map { it.id.value }
        )
        assertNotNull(
            "UNVERIFIED：隐藏项仍可按 ID 查找（用于恢复显示）",
            catalog.find(SchoolId("school-b"))
        )
    }

    @Test
    fun 覆盖只改展示字段不改能力与验证等级() {
        val withCapability = CatalogEntry(
            school = profile("school-a", "合成甲大学").copy(
                academicSystems = emptyList(),
                verification = CapabilityEvidence(level = VerificationLevel.SOURCE_CONFIRMED)
            ),
            packageId = "cn.example.school-a",
            packageVersion = PackageVersion("1.0.0")
        )
        val catalog = OverlaySchoolCatalog(
            catalog = listOf(withCapability),
            overrides = listOf(SchoolOverride(schoolId = SchoolId("school-a"), displayName = "改名"))
        )
        val resolved = catalog.find(SchoolId("school-a"))!!
        assertEquals(
            "UNVERIFIED：覆盖不得提升验证等级",
            VerificationLevel.SOURCE_CONFIRMED,
            resolved.school.verification?.level
        )
        assertTrue(
            "UNVERIFIED：覆盖不得凭空增加教务实例",
            resolved.school.academicSystems.isEmpty()
        )
        assertEquals(
            "UNVERIFIED：覆盖不得改变包版本",
            PackageVersion("1.0.0"),
            resolved.packageVersion
        )
    }

    @Test
    fun 编辑覆盖替换旧值而不是叠加() {
        val catalog = OverlaySchoolCatalog(
            catalog = listOf(entry("school-a", "合成甲大学")),
            overrides = listOf(SchoolOverride(schoolId = SchoolId("school-a"), additionalAliases = listOf("旧别名")))
        )
        val edited = catalog.withOverride(
            SchoolOverride(schoolId = SchoolId("school-a"), additionalAliases = listOf("新别名"))
        )
        val aliases = edited.find(SchoolId("school-a"))!!.school.aliases
        assertTrue("UNVERIFIED：新别名必须生效", aliases.contains("新别名"))
        assertTrue("UNVERIFIED：旧覆盖必须被替换", !aliases.contains("旧别名"))
    }

    @Test
    fun 目录与覆盖中的重复学校ID必须拒绝() {
        val duplicatedCatalog = try {
            OverlaySchoolCatalog(listOf(entry("school-a", "合成甲大学"), entry("school-a", "另一个甲大学")))
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertNotNull("UNVERIFIED：重复目录 ID 必须拒绝", duplicatedCatalog)

        val duplicatedOverride = try {
            OverlaySchoolCatalog(
                catalog = listOf(entry("school-a", "合成甲大学")),
                overrides = listOf(
                    SchoolOverride(schoolId = SchoolId("school-a"), displayName = "甲"),
                    SchoolOverride(schoolId = SchoolId("school-a"), displayName = "乙")
                )
            )
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertNotNull("UNVERIFIED：重复覆盖 ID 必须拒绝", duplicatedOverride)
    }

    @Test
    fun 目录条目声明包版本必须同时声明包ID() {
        val error = try {
            CatalogEntry(school = profile("school-a", "合成甲大学"), packageVersion = PackageVersion("1.0.0"))
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertNotNull("UNVERIFIED：缺包 ID 的版本声明必须拒绝", error)
    }
}
