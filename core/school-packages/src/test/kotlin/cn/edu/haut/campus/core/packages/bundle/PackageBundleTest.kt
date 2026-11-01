package cn.edu.haut.campus.core.packages.bundle

import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.assertPackageError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * 包结构、路径白名单与体积上限用例。
 *
 * UNVERIFIED：目录布局、上限与白名单都是本仓库自定策略，未在真实学校包上验证。
 */
class PackageBundleTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    @Test
    fun 路径穿越条目必须拒绝() {
        assertPackageError(PackageError.UNSAFE_PATH) {
            PackageBundle.ofContents(mapOf("../profile/escape.json" to ByteArray(4)))
        }
        assertPackageError(PackageError.UNSAFE_PATH) {
            PackageBundle.ofContents(mapOf("profile/../../escape.json" to ByteArray(4)))
        }
        assertPackageError(PackageError.UNSAFE_PATH) {
            PackageBundle.ofContents(mapOf("profile/%2e%2e/escape.json" to ByteArray(4)))
        }
    }

    @Test
    fun 绝对路径与反斜杠必须拒绝() {
        assertPackageError(PackageError.UNSAFE_PATH) {
            PackageBundle.ofContents(mapOf("/etc/passwd" to ByteArray(4)))
        }
        assertPackageError(PackageError.UNSAFE_PATH) {
            PackageBundle.ofContents(mapOf("profile\\..\\escape.json" to ByteArray(4)))
        }
        assertPackageError(PackageError.UNSAFE_PATH) {
            PackageBundle.ofContents(mapOf("C:/profile/escape.json" to ByteArray(4)))
        }
    }

    @Test
    fun 白名单之外的目录必须拒绝() {
        assertPackageError(PackageError.UNSAFE_PATH) {
            PackageBundle.ofContents(mapOf("secrets/token.json" to ByteArray(4)))
        }
        assertPackageError(PackageError.UNSAFE_PATH) {
            PackageBundle.ofContents(mapOf("manifest.json" to ByteArray(4)))
        }
    }

    @Test
    fun 大小写不同的重复条目必须拒绝() {
        val contents = linkedMapOf(
            "profile/School.json" to ByteArray(4),
            "profile/school.json" to ByteArray(4)
        )
        assertPackageError(PackageError.DUPLICATE_ENTRY) { PackageBundle.ofContents(contents) }
    }

    @Test
    fun 单文件与总量上限必须生效() {
        val limits = PackageLimits(maxFileBytes = 4, maxTotalBytes = 6)
        assertPackageError(PackageError.SIZE_LIMIT_EXCEEDED) {
            PackageBundle.ofContents(mapOf("profile/school.json" to ByteArray(5)), limits)
        }
        assertPackageError(PackageError.SIZE_LIMIT_EXCEEDED) {
            PackageBundle.ofContents(
                mapOf(
                    "profile/a.json" to ByteArray(4),
                    "profile/b.json" to ByteArray(4)
                ),
                limits
            )
        }
    }

    @Test
    fun 条目数上限必须生效() {
        val limits = PackageLimits(maxEntries = 1)
        assertPackageError(PackageError.TOO_MANY_ENTRIES) {
            PackageBundle.ofContents(
                mapOf(
                    "profile/a.json" to ByteArray(1),
                    "profile/b.json" to ByteArray(1)
                ),
                limits
            )
        }
    }

    @Test
    fun 空包必须拒绝() {
        assertPackageError(PackageError.EMPTY_PACKAGE) { PackageBundle.ofContents(emptyMap()) }
    }

    @Test
    fun 包摘要与清单文件映射一致() {
        val bundle = PackageBundle.ofContents(
            mapOf(
                "profile/school.json" to "{\"displayName\":\"合成学校\"}".toByteArray(),
                "assets/icon.txt" to ByteArray(3)
            )
        )
        val files = bundle.files.associate { it.path to it.sha256 }
        assertEquals("UNVERIFIED：包摘要必须与清单映射一致", PackageFileDigest.of(files), bundle.digest)
        assertEquals("UNVERIFIED：包摘要必须覆盖全部条目", 2, files.size)
        assertEquals("UNVERIFIED：内容摘要必须稳定", bundle.digest, PackageBundle.ofContents(
            mapOf(
                "assets/icon.txt" to ByteArray(3),
                "profile/school.json" to "{\"displayName\":\"合成学校\"}".toByteArray()
            )
        ).digest)
    }

    @Test
    fun 从目录读取正常包() {
        val directory = folder.newFolder("pkg").toPath()
        val profile = directory.resolve("profile")
        Files.createDirectories(profile)
        Files.write(profile.resolve("school.json"), "{}".toByteArray())
        val assets = directory.resolve("assets")
        Files.createDirectories(assets)
        Files.write(assets.resolve("icon.txt"), "icon".toByteArray())

        val bundle = PackageBundle.fromDirectory(directory)
        assertEquals("UNVERIFIED：目录读取必须包含全部条目", 2, bundle.files.size)
        assertTrue(
            "UNVERIFIED：目录读取的相对路径必须用 / 分隔",
            bundle.files.map { it.path }.containsAll(listOf("profile/school.json", "assets/icon.txt"))
        )
    }

    @Test
    fun 从目录读取时拒绝软链接() {
        val directory = folder.newFolder("symlink-pkg").toPath()
        val profile = directory.resolve("profile")
        Files.createDirectories(profile)
        Files.write(profile.resolve("school.json"), "{}".toByteArray())
        Files.createSymbolicLink(profile.resolve("alias.json"), profile.resolve("school.json"))
        assertPackageError(PackageError.UNSAFE_PATH) { PackageBundle.fromDirectory(directory) }
    }

    @Test
    fun 从目录读取时按大小预检拒绝() {
        val directory = folder.newFolder("oversize-pkg").toPath()
        val profile = directory.resolve("profile")
        Files.createDirectories(profile)
        Files.write(profile.resolve("big.json"), ByteArray(64))
        assertPackageError(PackageError.SIZE_LIMIT_EXCEEDED) {
            PackageBundle.fromDirectory(directory, PackageLimits(maxFileBytes = 8, maxTotalBytes = 1024))
        }
    }
}
