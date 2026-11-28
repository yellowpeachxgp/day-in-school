package cn.edu.haut.campus.core.campusgraph

import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.packages.bundle.PackageBundle
import cn.edu.haut.campus.core.packages.manifest.PackageManifest

/**
 * dis-datapack/v1 的实体解码 Port。
 *
 * W1-07 不定义外部数据包文件 schema；W5-01 将提供真正的 decoder。
 * 本 Port 只把「已签名校验的包内容 → Campus Graph 实体」作为显式接缝固定下来。
 */
fun interface CampusGraphDataPackageDecoder {
    fun decode(manifest: PackageManifest, bundle: PackageBundle): List<CampusGraphEntity>
}

/**
 * 校园图数据包加载器。它假设包签名、发布者信任与文件摘要已经由
 * core:school-packages 的 PackageVerifier 验过；这里仍复查目标学校、
 * 清单/内容摘要一致性与实体来源层级，保证解码器错误不会静默进入图快照。
 */
class CampusGraphDataPackageLoader(
    private val decoder: CampusGraphDataPackageDecoder
) {

    fun load(
        manifest: PackageManifest,
        bundle: PackageBundle,
        schoolId: SchoolId,
        campusId: CampusId? = null,
        providerId: String? = null
    ): List<CampusGraphEntity> {
        require(manifest.matchesTarget(schoolId, providerId)) {
            "Campus Graph 数据包目标学校/教务实例不匹配"
        }
        require(manifest.filesDigest == bundle.digest) {
            "Campus Graph 数据包清单摘要与真实内容摘要不一致"
        }
        val decoded = decoder.decode(manifest, bundle)
        val duplicated = decoded.groupingBy { it.ref }.eachCount().filterValues { it > 1 }.keys
        require(duplicated.isEmpty()) {
            "Campus Graph 数据包包含重复稳定 ID：" + duplicated.joinToString { it.id }
        }
        decoded.forEach { entity ->
            require(entity.ref.schoolId == schoolId) {
                "Campus Graph 数据包实体学校与目标不一致：" + entity.ref.id
            }
            require(entity.provenance.sourceLevel == CampusGraphSourceLevel.DATA_PACKAGE) {
                "Campus Graph 数据包实体必须声明 DATA_PACKAGE 来源：" + entity.ref.id
            }
        }
        return if (campusId == null) {
            decoded
        } else {
            decoded.filter { it.ref.campusId == campusId }
        }
    }
}
