package cn.edu.haut.campus.core.packages.bundle

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException

/**
 * 包文件摘要。清单里的 files 映射与真实包内容的摘要必须逐字节一致。
 *
 * 摘要覆盖"排序后的 path=sha256 逐条长度前缀拼接"，因此条目顺序、条数、
 * 路径大小写任何变化都会改变结果；同一路径不能出现两条记录。
 */
object PackageFileDigest {

    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun isSha256(value: String): Boolean = SHA256.matches(value)

    fun of(files: Map<String, String>): String {
        if (files.isEmpty()) {
            throw PackageException(PackageError.EMPTY_PACKAGE, "包文件清单不能为空")
        }
        val parts = files.entries.sortedBy { it.key }.map { entry ->
            if (!SHA256.matches(entry.value)) {
                throw PackageException(
                    PackageError.MANIFEST_INVALID,
                    "文件摘要必须是 64 位小写十六进制：" + entry.key
                )
            }
            entry.key + "=" + entry.value
        }
        return Digests.sha256Hex(*parts.toTypedArray())
    }
}
