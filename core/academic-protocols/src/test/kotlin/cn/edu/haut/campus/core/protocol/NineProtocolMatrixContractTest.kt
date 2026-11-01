package cn.edu.haut.campus.core.protocol

import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.protocol.chaoxing.ChaoxingContractSpec
import cn.edu.haut.campus.core.protocol.chengfang.ChengfangContractSpec
import cn.edu.haut.campus.core.protocol.eams.EamsContractSpec
import cn.edu.haut.campus.core.protocol.jinzhi.JinzhiContractSpec
import cn.edu.haut.campus.core.protocol.kingosoft.KingosoftContractSpec
import cn.edu.haut.campus.core.protocol.qz.QzLegacyContractSpec
import cn.edu.haut.campus.core.protocol.qz.QzModernContractSpec
import cn.edu.haut.campus.core.protocol.zf.ZfLegacyContractSpec
import cn.edu.haut.campus.core.protocol.zf.ZfModernContractSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NineProtocolMatrixContractTest {

    private val specs: List<GatewayContractSpec> = listOf(
        ZfModernContractSpec(),
        ZfLegacyContractSpec(),
        QzModernContractSpec(),
        QzLegacyContractSpec(),
        JinzhiContractSpec(),
        ChengfangContractSpec(),
        KingosoftContractSpec(),
        EamsContractSpec(),
        ChaoxingContractSpec()
    )

    @Test
    fun 九类教务协议必须完整注册且全部通过九项合同检查() {
        assertEquals(
            AcademicProtocolFamily.entries.toSet(),
            specs.map { it.protocol }.toSet()
        )
        val descriptors = ProtocolGatewayRegistry.descriptors()
        assertEquals(AcademicProtocolFamily.entries.size, descriptors.size)
        descriptors.values.forEach { descriptor ->
            assertTrue(
                "未在真实学校验证的通用协议描述符必须标注 UNVERIFIED: " + descriptor.id,
                descriptor.notes.orEmpty().contains("UNVERIFIED") || descriptor.displayName.contains("UNVERIFIED")
            )
        }
        specs.forEach { spec ->
            GatewayContractSuite(spec).runAll()
        }
    }
}
