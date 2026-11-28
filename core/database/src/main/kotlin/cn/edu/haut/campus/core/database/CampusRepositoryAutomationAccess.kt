package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId

/**
 * W2-02 自动化运行时接缝。放在独立文件，避免扩大 W0-05 的 CampusRepository 外观；
 * 只读取同一模块内部已绑定的 repositoryEnvironment，不提供任意数据库替换能力。
 */
val CampusRepository.automationDatabase: HautDatabase
    get() = repositoryEnvironment.database

val CampusRepository.automationEntityWritePort: AcademicEntityWritePort
    get() = repositoryEnvironment.academicEntityWritePort

val CampusRepository.automationScope: SchoolAccountScope
    get() {
        val profile = repositoryEnvironment.accountProfile
        return SchoolAccountScope(
            schoolId = SchoolId(profile.schoolId),
            accountId = AccountId(profile.accountId),
            profileId = ProfileId(profile.id),
            campusId = profile.campusId?.let(::CampusId)
        )
    }
