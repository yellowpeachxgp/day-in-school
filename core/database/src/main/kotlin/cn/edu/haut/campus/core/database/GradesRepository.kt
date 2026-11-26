package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.StudentProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal

/**
 * 成绩 attempts、官方 GPA 本地记录、成绩隐私偏好与临时 StudentProfile 存储。
 * 实现从 CampusRepository 兼容门面逐字迁入；事务、默认值与通知语义保持不变。
 */
class GradesRepository internal constructor(
    private val environment: CampusRepositoryEnvironment
) {
    private val database = environment.database
    private val dao = environment.dao
    private val preferences = environment.preferences

    val studentProfileFlow: Flow<StudentProfile?> =
        dao.observeStudentProfile().map { it?.toDomain() }

    val gradeAttemptsFlow: Flow<List<GradeAttempt>> =
        dao.observeGradeAttempts().map { list -> list.map { it.toDomain() } }

    suspend fun saveImportedGrades(
        attempts: List<GradeAttempt>,
        officialGpa: BigDecimal? = null,
        officialNote: String? = null,
        replaceExisting: Boolean = false
    ) {
        database.withTransaction {
            if (replaceExisting) {
                dao.clearGradeAttempts()
            }
            dao.upsertGradeAttempts(attempts.map { it.toEntity() })
        }
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.ONBOARDING_COMPLETED] = true
            prefs[CampusRepositoryPrefKeys.CLEAN_BASE_V2] = true
            if (officialGpa != null) {
                prefs[CampusRepositoryPrefKeys.OFFICIAL_GPA] = officialGpa.toPlainString()
                prefs[CampusRepositoryPrefKeys.OFFICIAL_GPA_NOTE] = officialNote ?: "正方教务系统返回平均学分绩点"
            } else {
                prefs.remove(CampusRepositoryPrefKeys.OFFICIAL_GPA)
                prefs.remove(CampusRepositoryPrefKeys.OFFICIAL_GPA_NOTE)
            }
        }
    }

    suspend fun saveStudentProfile(profile: StudentProfile) {
        dao.upsertStudentProfile(profile.toEntity())
    }

    suspend fun setGpaPrivacyShield(enabled: Boolean) {
        preferences.edit { it[CampusRepositoryPrefKeys.GPA_PRIVACY] = enabled }
    }
}
