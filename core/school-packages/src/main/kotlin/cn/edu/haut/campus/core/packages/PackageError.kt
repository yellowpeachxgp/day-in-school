package cn.edu.haut.campus.core.packages

/**
 * 学校配置包被拒绝的结构化原因。
 *
 * 这里的取值是"宿主可以据以给出可操作提示"的最小集合；不使用字符串消息做分支判断。
 * UNVERIFIED：所有取值来自本仓库自定的清单与安装流程设计，尚未与任何真实发行渠道核对。
 */
enum class PackageError {
    /** 清单结构、字段类型或取值不合法。 */
    MANIFEST_INVALID,

    /** 清单声明了非 dis-campus-plugin/v1 的格式，或声明了本宿主不支持的包类型。 */
    MANIFEST_UNSUPPORTED_FORMAT,

    /** 清单体积超过宿主上限。 */
    MANIFEST_TOO_LARGE,

    /** 包内容为空。 */
    EMPTY_PACKAGE,

    /** 清单声明了宿主路径白名单之外的条目。 */
    UNSAFE_PATH,

    /** 包内存在规范化后重复的条目（含大小写）。 */
    DUPLICATE_ENTRY,

    /** 单文件或总大小超出上限。 */
    SIZE_LIMIT_EXCEEDED,

    /** 条目数量超出上限。 */
    TOO_MANY_ENTRIES,

    /** 缺少签名。 */
    SIGNATURE_MISSING,

    /** 签名与清单摘要不匹配，或签名本身不可解析。 */
    SIGNATURE_INVALID,

    /** 发布者不在宿主信任清单中，或信任清单里的公钥格式非法。 */
    SIGNATURE_UNTRUSTED_PUBLISHER,

    /** 清单声明了包内不存在的文件。 */
    FILE_MISSING,

    /** 包内存在清单未声明的文件。 */
    FILE_UNLISTED,

    /** 实际文件内容摘要与清单登记不一致。 */
    FILE_HASH_MISMATCH,

    /** 暂存区里没有该 staged 记录（已丢弃或从未创建）。 */
    NOT_STAGED,

    /** 该摘要从未通过校验，不能激活或回滚到它。 */
    NOT_VERIFIED,

    /** 暂存写入失败（磁盘错误、权限不足等），未产生任何可激活内容。 */
    STAGING_FAILED,

    /** 激活过程失败，活动版本保持不变。 */
    ACTIVATION_FAILED,

    /** 没有可回滚的上一个已验证版本。 */
    ROLLBACK_UNAVAILABLE,

    /** 宿主 API 版本低于清单要求。 */
    HOST_API_TOO_OLD,

    /** App 版本码低于清单要求。 */
    APP_VERSION_TOO_OLD,

    /** 清单要求宿主还不支持的能力。 */
    CAPABILITY_UNSUPPORTED,

    /** 清单目标学校/教务实例与当前安装范围不一致。 */
    TARGET_MISMATCH,

    /** 清单声明了暂存时登记的包 ID 之外的 ID。 */
    PACKAGE_ID_MISMATCH,

    /** 新版本扩大了权限范围，必须先取得用户重新授权。 */
    PERMISSION_REAUTHORIZATION_REQUIRED
}

/**
 * 安装/校验失败时抛出的唯一异常类型。
 *
 * 约束：消息只允许包含包 ID、条目路径、摘要前缀与枚举名，绝不写入文件正文、
 * 凭据、Cookie 或完整响应内容。
 */
class PackageException(
    val error: PackageError,
    message: String
) : IllegalArgumentException(message)
