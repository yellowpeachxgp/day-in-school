package cn.edu.haut.campus.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalCampusModelsTest {

    @Test
    fun `能力可用必须同时满足实现核验授权和前置条件`() {
        val base = CapabilityStatus(
            kind = CapabilityKind.TIMETABLE_READ,
            providerSupports = true,
            verifiedForAccount = true,
            userConsent = true,
            prerequisitesReady = true,
            evidence = CapabilityEvidence(VerificationLevel.SOURCE_CONFIRMED)
        )
        assertTrue(base.isAvailable)
        assertFalse(base.copy(verifiedForAccount = false).isAvailable)
        assertFalse(base.copy(userConsent = false).isAvailable)
        assertFalse(base.copy(prerequisitesReady = false).isAvailable)
        assertFalse(base.copy(explicitState = CapabilityState.NOT_OPEN).isAvailable)
        assertFalse(base.copy(explicitState = CapabilityState.UNSUPPORTED).isAvailable)
        assertFalse(base.copy(explicitState = CapabilityState.REVOKED).isAvailable)
        assertFalse(base.copy(providerSupports = false, explicitState = CapabilityState.ENABLED).isAvailable)
    }

    @Test
    fun `学校账号和配置标识保持独立`() {
        val scope = SchoolAccountScope(
            schoolId = SchoolId("haut"),
            accountId = AccountId("account-opaque"),
            profileId = ProfileId("profile-opaque"),
            campusId = CampusId("haut-lianhuajie")
        )
        assertTrue(scope.schoolId.value != scope.accountId.value)
        assertTrue(scope.profileId.value != scope.accountId.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `标识符拒绝把教务网址作为学校主键`() {
        SchoolId("https://jwglxt.haut.edu.cn")
    }
}
