package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId

/**
 * W2-05 收件箱运行时接缝。与 automation 访问器一样只读取同一模块内部已绑定的
 * repositoryEnvironment；不提供任意数据库替换能力，也不扩大 CampusRepository 兼容外观。
 */
val CampusRepository.inboxDatabase: HautDatabase
    get() = repositoryEnvironment.database

val CampusRepository.inboxEntityWritePort: AcademicEntityWritePort
    get() = repositoryEnvironment.academicEntityWritePort

val CampusRepository.inboxScope: SchoolAccountScope
    get() {
        val profile = repositoryEnvironment.accountProfile
        return SchoolAccountScope(
            schoolId = SchoolId(profile.schoolId),
            accountId = AccountId(profile.accountId),
            profileId = ProfileId(profile.id),
            campusId = profile.campusId?.let(::CampusId)
        )
    }
