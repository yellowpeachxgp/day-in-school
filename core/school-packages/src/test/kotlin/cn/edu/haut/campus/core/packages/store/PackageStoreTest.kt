package cn.edu.haut.campus.core.packages.store

import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.SyntheticPackages
import cn.edu.haut.campus.core.packages.assertPackageError
import cn.edu.haut.campus.core.packages.manifest.PackageManifestCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * 不可变包存储用例：暂存 → 校验 → 激活 → 回滚，以及篡改与状态机攻击。
 *
 * UNVERIFIED：全部包内容与密钥均为合成数据；文件存储行为只在 JVM 临时目录上验证，
 * 未覆盖 Android scoped storage、断电恢复或低存储空间。
 */
class PackageStoreTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val keyPair = SyntheticPackages.keyPair()
    private val keys = SyntheticPackages.registry(keyPair)

    private fun stores(): List<PackageStore> = listOf(
        InMemoryPackageStore(),
        FilePackageStore(folder.newFolder().toPath())
    )

    private fun contents(version: String): Map<String, String> =
        mapOf("profile/school.json" to "{\"version\":\"" + version + "\"}")

    private fun verifiedFor(store: PackageStore, version: String): StagedVerification.Verified {
        val signed = SyntheticPackages.signed(keyPair, contents = contents(version), version = version)
        val staged = store.stage(SyntheticPackages.PACKAGE_ID, signed.bundle)
        val verification = store.verify(staged, signed.manifestBytes, signed.signature, keys)
        assertTrue("UNVERIFIED：合法包必须通过校验，实际为 " + verification, verification is StagedVerification.Verified)
        return verification as StagedVerification.Verified
    }

    @Test
    fun 暂存校验激活后活动版本正确() {
        stores().forEach { store ->
            val verified = verifiedFor(store, "1.0.0")
            val active = store.activate(verified)
            assertEquals("UNVERIFIED：活动摘要必须等于真实内容摘要", verified.digest, active.digest)
            assertEquals("UNVERIFIED：活动版本必须为刚安装的版本", "1.0.0", active.version.value)
            assertNull("UNVERIFIED：首次激活不应有回滚目标", active.previousDigest)
            assertEquals(
                "UNVERIFIED：活动版本必须可通过 store 读回",
                active.digest,
                store.active(SyntheticPackages.PACKAGE_ID)?.digest
            )
            assertTrue(
                "UNVERIFIED：已验证摘要必须出现在历史中",
                store.verifiedDigests(SyntheticPackages.PACKAGE_ID).contains(active.digest)
            )
        }
    }

    @Test
    fun 未通过校验的包不能激活() {
        stores().forEach { store ->
            val signed = SyntheticPackages.signed(keyPair, contents = contents("1.0.0"))
            val staged = store.stage(SyntheticPackages.PACKAGE_ID, signed.bundle)
            val manifest = PackageManifestCodec.parse(signed.manifestBytes)
            val forged = StagedVerification.Verified(
                stage = staged,
                manifest = manifest,
                digest = signed.bundle.digest,
                manifestDigest = "0".repeat(64)
            )
            assertPackageError(PackageError.NOT_VERIFIED) { store.activate(forged) }
            assertNull(
                "UNVERIFIED：校验未通过时不得产生活动版本",
                store.active(SyntheticPackages.PACKAGE_ID)
            )
        }
    }

    @Test
    fun 丢弃暂存后不能继续校验() {
        stores().forEach { store ->
            val signed = SyntheticPackages.signed(keyPair, contents = contents("1.0.0"))
            val staged = store.stage(SyntheticPackages.PACKAGE_ID, signed.bundle)
            store.discard(staged)
            val result = store.verify(staged, signed.manifestBytes, signed.signature, keys)
            assertTrue("UNVERIFIED：丢弃后校验必须失败", result is StagedVerification.Rejected)
            assertEquals(
                "UNVERIFIED：丢弃后必须映射为 NOT_STAGED",
                PackageError.NOT_STAGED,
                (result as StagedVerification.Rejected).error
            )
        }
    }

    @Test
    fun 内容与清单不一致时拒绝且不激活() {
        stores().forEach { store ->
            val honest = SyntheticPackages.signed(keyPair, contents = contents("1.0.0"))
            val tamperedBundle = SyntheticPackages.bundle(contents("1.0.0-tampered"))
            val staged = store.stage(SyntheticPackages.PACKAGE_ID, tamperedBundle)
            val result = store.verify(staged, honest.manifestBytes, honest.signature, keys)
            assertTrue("UNVERIFIED：内容不一致必须拒绝", result is StagedVerification.Rejected)
            assertEquals(
                "UNVERIFIED：内容不一致必须映射为 FILE_HASH_MISMATCH",
                PackageError.FILE_HASH_MISMATCH,
                (result as StagedVerification.Rejected).error
            )
            assertNull(
                "UNVERIFIED：拒绝后不得留下活动版本",
                store.active(SyntheticPackages.PACKAGE_ID)
            )
            assertTrue(
                "UNVERIFIED：被拒绝的摘要不得进入已验证历史",
                store.verifiedDigests(SyntheticPackages.PACKAGE_ID).isEmpty()
            )
        }
    }

    @Test
    fun 回滚到上一个已验证版本() {
        stores().forEach { store ->
            val first = verifiedFor(store, "1.0.0")
            store.activate(first)
            val second = verifiedFor(store, "1.1.0")
            val activeSecond = store.activate(second)
            assertEquals("UNVERIFIED：当前必须是较新版本", "1.1.0", activeSecond.version.value)
            assertEquals("UNVERIFIED：回滚目标必须是上一版本摘要", first.digest, activeSecond.previousDigest)

            val rolledBack = store.rollback(SyntheticPackages.PACKAGE_ID)
            assertEquals("UNVERIFIED：回滚后必须是上一版本", "1.0.0", rolledBack.version.value)
            assertEquals("UNVERIFIED：回滚后摘要必须匹配上一版本", first.digest, rolledBack.digest)
            assertPackageError(PackageError.ROLLBACK_UNAVAILABLE) {
                store.rollback(SyntheticPackages.PACKAGE_ID)
            }
        }
    }

    @Test
    fun 没有更早版本时回滚失败且不改变活动版本() {
        stores().forEach { store ->
            val first = verifiedFor(store, "1.0.0")
            store.activate(first)
            assertPackageError(PackageError.ROLLBACK_UNAVAILABLE) {
                store.rollback(SyntheticPackages.PACKAGE_ID)
            }
            assertNotNull(
                "UNVERIFIED：回滚失败不得清掉活动版本",
                store.active(SyntheticPackages.PACKAGE_ID)
            )
        }
    }

    @Test
    fun 未安装的包回滚失败() {
        stores().forEach { store ->
            assertPackageError(PackageError.ROLLBACK_UNAVAILABLE) {
                store.rollback("cn.example.never-installed")
            }
        }
    }

    @Test
    fun 文件存储的暂存目录隔离在包的根目录内() {
        val root: Path = folder.newFolder().toPath()
        val store = FilePackageStore(root)
        val signed = SyntheticPackages.signed(keyPair, contents = contents("1.0.0"))
        val staged = store.stage(SyntheticPackages.PACKAGE_ID, signed.bundle)
        val stagedFile = root.resolve("staging").resolve(staged.stagingId).resolve("profile/school.json")
        assertTrue("UNVERIFIED：暂存内容必须落在暂存目录", Files.isRegularFile(stagedFile))
        assertTrue(
            "UNVERIFIED：激活前不得出现版本目录",
            !Files.exists(root.resolve("versions").resolve(SyntheticPackages.PACKAGE_ID))
        )
    }

    @Test
    fun 文件存储校验的是磁盘真实字节而不是内存副本() {
        val root: Path = folder.newFolder().toPath()
        val store = FilePackageStore(root)
        val signed = SyntheticPackages.signed(keyPair, contents = contents("1.0.0"))
        val staged = store.stage(SyntheticPackages.PACKAGE_ID, signed.bundle)
        val stagedFile = root.resolve("staging").resolve(staged.stagingId).resolve("profile/school.json")
        Files.write(stagedFile, "{\"version\":\"tampered-on-disk\"}".toByteArray())

        val result = store.verify(staged, signed.manifestBytes, signed.signature, keys)
        assertTrue("UNVERIFIED：磁盘内容被改写后必须拒绝", result is StagedVerification.Rejected)
        assertEquals(
            "UNVERIFIED：磁盘篡改必须映射为 FILE_HASH_MISMATCH",
            PackageError.FILE_HASH_MISMATCH,
            (result as StagedVerification.Rejected).error
        )
        assertNull(
            "UNVERIFIED：磁盘篡改后不得留下活动版本",
            store.active(SyntheticPackages.PACKAGE_ID)
        )
    }

    @Test
    fun 文件存储的索引在新实例中仍可读回活动版本() {
        val root: Path = folder.newFolder().toPath()
        val store = FilePackageStore(root)
        val verified = verifiedFor(store, "2.0.0")
        val activated = store.activate(verified)

        val reopened = FilePackageStore(root)
        val active = reopened.active(SyntheticPackages.PACKAGE_ID)
        assertNotNull("UNVERIFIED：重启后必须能读回活动版本", active)
        assertEquals("UNVERIFIED：重启后摘要必须一致", activated.digest, active!!.digest)
        assertEquals("UNVERIFIED：重启后版本必须一致", "2.0.0", active.version.value)
        assertTrue(
            "UNVERIFIED：重启后已验证摘要仍可见",
            reopened.verifiedDigests(SyntheticPackages.PACKAGE_ID).contains(activated.digest)
        )
        assertTrue(
            "UNVERIFIED：重启后索引文件必须存在",
            Files.isRegularFile(root.resolve("index.json"))
        )
    }

    @Test
    fun 同一版本重复激活不会产生虚假回滚目标() {
        stores().forEach { store ->
            val verified = verifiedFor(store, "1.0.0")
            store.activate(verified)
            val again = verifiedFor(store, "1.0.0")
            val active = store.activate(again)
            assertNull("UNVERIFIED：重复激活同一摘要不应产生回滚目标", active.previousDigest)
            assertPackageError(PackageError.ROLLBACK_UNAVAILABLE) {
                store.rollback(SyntheticPackages.PACKAGE_ID)
            }
        }
    }

    @Test
    fun 存储不接受空包ID与伪造的暂存句柄() {
        val store = InMemoryPackageStore()
        val error = try {
            store.stage("", SyntheticPackages.bundle())
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertNotNull("UNVERIFIED：空包 ID 必须被拒绝", error)

        val signed = SyntheticPackages.signed(keyPair, contents = contents("1.0.0"))
        val forgedStage = StagedPackage("staged-forged", SyntheticPackages.PACKAGE_ID, signed.bundle)
        val result = store.verify(forgedStage, signed.manifestBytes, signed.signature, keys)
        assertEquals(
            "UNVERIFIED：伪造的暂存句柄必须映射为 NOT_STAGED",
            PackageError.NOT_STAGED,
            (result as StagedVerification.Rejected).error
        )
        assertNull(
            "UNVERIFIED：伪造句柄不得产生活动版本",
            store.active(SyntheticPackages.PACKAGE_ID)
        )
    }
}
