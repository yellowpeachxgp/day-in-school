package cn.edu.haut.campus.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelsAndRedactorTest {
    @Test
    fun httpsUrlRejectsPlainHttpCredentialsQueryAndFragment() {
        listOf(
            "http://provider.example/v1",
            "https://user:pass@provider.example/v1",
            "https://provider.example/v1?format=json",
            "https://provider.example/v1#frag",
            "https://provider.example/v1 with-space"
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { HttpsUrl(raw) }
        }
        val valid = HttpsUrl("https://provider.example:8443/v1/")
        assertEquals("provider.example", valid.host)
    }

    @Test
    fun redactorMasksStudentPhoneNationalIdAndDormWithoutRetainingOriginals() {
        val student = "202012345678"
        val phone = "13812345678"
        val nationalId = "110101199003071234"
        val dorm = "3-502"
        val input = "学号：$student，手机 $phone，身份证 $nationalId，宿舍 $dorm，请提炼通知"

        val redacted = AiLocalRedactor.redact(listOf(AiMessage(AiMessageRole.USER, input)))
        val outbound = redacted.messages.single().text

        listOf(student, phone, nationalId, dorm).forEach { original ->
            assertFalse("出站文本不得保留原文片段 $original", outbound.contains(original))
        }
        assertTrue(outbound.contains("[已脱敏:学号]"))
        assertTrue(outbound.contains("[已脱敏:手机号]"))
        assertTrue(outbound.contains("[已脱敏:身份证号]"))
        assertTrue(outbound.contains("[已脱敏:宿舍号]"))
        val kinds = redacted.redactions.map { it.kind }.toSet()
        assertEquals(
            setOf(RedactionKind.STUDENT_ID, RedactionKind.PHONE_NUMBER, RedactionKind.NATIONAL_ID, RedactionKind.DORM_ROOM),
            kinds
        )
    }

    @Test
    fun redactionPreviewStoresOnlyKindsAndCounts() {
        val redacted = AiLocalRedactor.redact(
            listOf(AiMessage(AiMessageRole.USER, "电话 13812345678，再写一次 13812345678"))
        )

        val phone = redacted.redactions.single { it.kind == RedactionKind.PHONE_NUMBER }
        assertEquals(2, phone.count)
        assertFalse(redacted.redactions.toString().contains("13812345678"))
    }
}
