package cn.edu.haut.campus.core.protocol

import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.protocol.chaoxing.ChaoxingGateway
import cn.edu.haut.campus.core.protocol.chengfang.ChengfangGateway
import cn.edu.haut.campus.core.protocol.eams.EamsGateway
import cn.edu.haut.campus.core.protocol.jinzhi.JinzhiEndpoints
import cn.edu.haut.campus.core.protocol.jinzhi.JinzhiGateway
import cn.edu.haut.campus.core.protocol.kingosoft.KingosoftGateway
import cn.edu.haut.campus.core.protocol.qz.QzLegacyGateway
import cn.edu.haut.campus.core.protocol.qz.QzModernGateway
import cn.edu.haut.campus.core.protocol.zf.ZfLegacyGateway
import cn.edu.haut.campus.core.protocol.zf.ZfModernGateway

/**
 * 九类教务协议统一注册表。
 *
 * 所有通过通用协议层创建的 Gateway 都显式标注 UNVERIFIED（合成夹具合同级验证，
 * 未在对应真实学校完成线上验证）；真实学校是否可用由 [cn.edu.haut.campus.core.model.SchoolProfile]
 * 中的 [cn.edu.haut.campus.core.model.VerificationLevel] 单独记录。
 */
object ProtocolGatewayRegistry {

    fun create(
        family: AcademicProtocolFamily,
        baseUrl: String? = null
    ): AcademicGateway = when (family) {
        AcademicProtocolFamily.ZF_MODERN -> ZfModernGateway()
        AcademicProtocolFamily.ZF_LEGACY -> ZfLegacyGateway()
        AcademicProtocolFamily.QZ_MODERN -> QzModernGateway()
        AcademicProtocolFamily.QZ_LEGACY -> QzLegacyGateway()
        AcademicProtocolFamily.JINZHI -> {
            val endpoints = baseUrl?.takeIf { it.isNotBlank() }
                ?.let { runCatching { JinzhiEndpoints.fromBaseUrl(it) }.getOrNull() }
                ?: JinzhiEndpoints("")
            JinzhiGateway(endpoints)
        }
        AcademicProtocolFamily.CHENGFANG -> ChengfangGateway()
        AcademicProtocolFamily.KINGOSOFT -> KingosoftGateway()
        AcademicProtocolFamily.EAMS -> EamsGateway()
        AcademicProtocolFamily.CHAOXING_ACADEMIC -> ChaoxingGateway()
    }

    fun descriptors(): Map<AcademicProtocolFamily, GatewayDescriptor> =
        AcademicProtocolFamily.entries.associateWith { create(it).descriptor }
}
