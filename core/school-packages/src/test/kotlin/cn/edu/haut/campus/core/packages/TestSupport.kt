package cn.edu.haut.campus.core.packages

import cn.edu.haut.campus.core.packages.install.InstallOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * 断言辅助。消息统一带 UNVERIFIED 前缀：这些用例只证明合成夹具上的行为，
 * 不能作为任何真实学校或真实发布包的验证结论。
 */
internal fun assertPackageError(expected: PackageError, block: () -> Unit): PackageException {
    val thrown = try {
        block()
        null
    } catch (error: PackageException) {
        error
    }
    assertNotNull("UNVERIFIED：期望抛出 PackageException(" + expected.name + ")", thrown)
    assertEquals("UNVERIFIED：错误码不符", expected, thrown!!.error)
    return thrown
}

internal fun assertInstallRejected(expected: PackageError, outcome: InstallOutcome) {
    val rejected = outcome as? InstallOutcome.Rejected
    assertNotNull("UNVERIFIED：期望安装被拒绝为 " + expected.name + "，实际为 " + outcome, rejected)
    assertEquals("UNVERIFIED：安装拒绝原因不符", expected, rejected!!.error)
}
