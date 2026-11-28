package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.database.AcademicEntityWritePort
import cn.edu.haut.campus.core.database.AcademicEntityWritePortFactory
import cn.edu.haut.campus.core.database.AcademicEntityWritePortRegistry
import cn.edu.haut.campus.core.database.AccountProfileEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId

/**
 * core:vault 的进程级组合入口。Application 在打开账号库前调用 [install]，
 * database 模块随后通过依赖反转端口拿到 RoomVaultJournal；单元测试仍可直接用本模块构造器。
 */
object VaultRuntimeInstaller {
    @Volatile
    private var appVersionName: String = "unknown"

    fun install(appVersionName: String = "unknown") {
        require(appVersionName.isNotBlank() && appVersionName.length <= 128) { "appVersionName 非法" }
        this.appVersionName = appVersionName
        AcademicEntityWritePortRegistry.installFactory(VaultAcademicWritePortFactory)
    }

    internal fun accountScope(profile: AccountProfileEntity): SchoolAccountScope = SchoolAccountScope(
        schoolId = SchoolId(profile.schoolId),
        accountId = AccountId(profile.accountId),
        profileId = ProfileId(profile.id),
        campusId = profile.campusId?.let(::CampusId)
    )

    internal fun createJournal(database: HautDatabase): RoomVaultJournal {
        val searchIndex = RoomSearchIndex(database)
        return RoomVaultJournal(
            database = database,
            outbox = RoomEventOutbox(database),
            snapshotStores = listOf(
                CourseEntitySnapshotStore(database),
                GradeAttemptSnapshotStore(database),
                ExamScheduleSnapshotStore(database),
                PersonalEventSnapshotStore(database, searchIndex)
            ),
            searchIndex = searchIndex
        )
    }

    private object VaultAcademicWritePortFactory : AcademicEntityWritePortFactory {
        override fun create(database: HautDatabase, accountProfile: AccountProfileEntity): AcademicEntityWritePort {
            val journal = createJournal(database)
            VaultBackupServiceRegistry.register(
                profileId = accountProfile.id,
                service = VaultBackupService(
                    database = database,
                    scope = accountScope(accountProfile),
                    journal = journal,
                    appVersion = appVersionName
                )
            )
            return journal
        }
    }
}
