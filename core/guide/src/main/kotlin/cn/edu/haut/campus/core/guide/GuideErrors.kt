package cn.edu.haut.campus.core.guide

/** 数据包结构或签名信任边界不满足 guide 执行前置条件。 */
class GuideDefinitionException(message: String) : IllegalArgumentException(message)

/** 调用方伪造 VerifiedDataPack 类型但内部摘要不一致等安全失败。 */
class GuideSecurityException(message: String) : IllegalStateException(message)

/** 持久化快照损坏、版本不支持或字段越界。 */
class GuidePersistenceException(message: String) : IllegalArgumentException(message)

/** 用户操作与当前状态机不匹配，或输入答案/事实不属于声明式选项集。 */
class GuideOperationException(message: String) : IllegalStateException(message)

/** Timeline 投影发现进度与当前数据包不一致；宁可中断也不伪造时间语义。 */
class GuideTimelineProjectionException(message: String) : IllegalStateException(message)
