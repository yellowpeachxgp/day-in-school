package cn.edu.haut.campus.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CampusProfile
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SchoolProfile
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId

data class AccountProfile(
    val id: String,
    val school: SchoolProfile,
    val label: String,
    val isLegacy: Boolean
)

@Entity(tableName = "account_profiles")
data class AccountProfileEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val schoolId: String,
    val schoolName: String,
    val province: String?,
    val city: String?,
    val campusId: String?,
    val campusName: String?,
    val timeZoneId: String,
    val label: String,
    val isLegacy: Boolean,
    val databaseName: String,
    val preferencesName: String,
    val createdAtEpochMs: Long
) {
    fun school(): SchoolProfile = if (schoolId == SchoolDirectory.HAUT_SCHOOL_ID.value) {
        SchoolDirectory.hautProfile()
    } else SchoolProfile(
        id = SchoolId(schoolId), displayName = schoolName, province = province, city = city,
        campuses = if (campusId != null && campusName != null) listOf(
            CampusProfile(CampusId(campusId), campusName, timeZone = ZoneId.of(timeZoneId), isPrimary = true)
        ) else emptyList()
    )

    fun toDomain() = AccountProfile(id, school(), label, isLegacy)
}

@Entity(tableName = "active_profile")
data class ActiveProfileEntity(@PrimaryKey val id: Int = 1, val profileId: String, val generation: Long)

/** 旧库采用原文件，journal 记录采用事实与一致性证据，不宣称已经复制数据库。 */
@Entity(tableName = "profile_adoption_journal")
data class ProfileAdoptionJournalEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val sourceDatabase: String,
    val sourceSchemaVersion: Int,
    val adoptedSchemaVersion: Int,
    val originalTableCounts: String,
    val status: String,
    val recordedAtEpochMs: Long
)

internal const val LEGACY_ADOPTION_JOURNAL_ID = "legacy-haut-adoption-v1"
internal const val ADOPTION_REGISTERED = "REGISTERED"
internal const val ADOPTION_SOURCE_CAPTURED = "SOURCE_CAPTURED"
internal const val ADOPTION_COMPLETE = "ADOPTED_IN_PLACE"

@Dao
interface GlobalProfileDao {
    @Query("SELECT * FROM account_profiles ORDER BY createdAtEpochMs ASC, id ASC")
    fun observeProfiles(): Flow<List<AccountProfileEntity>>
    @Query("SELECT * FROM account_profiles ORDER BY createdAtEpochMs ASC, id ASC")
    suspend fun allProfiles(): List<AccountProfileEntity>
    @Query("SELECT * FROM account_profiles WHERE isLegacy = 1 ORDER BY createdAtEpochMs ASC, id ASC")
    suspend fun legacyProfiles(): List<AccountProfileEntity>
    @Query("SELECT * FROM account_profiles WHERE id = :id LIMIT 1")
    suspend fun profile(id: String): AccountProfileEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProfile(profile: AccountProfileEntity)
    @Query("SELECT * FROM active_profile WHERE id = 1")
    suspend fun active(): ActiveProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveActive(active: ActiveProfileEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveJournal(journal: ProfileAdoptionJournalEntity)
    @Query("SELECT * FROM profile_adoption_journal WHERE id = :id LIMIT 1")
    suspend fun journal(id: String): ProfileAdoptionJournalEntity?
}

@Database(
    entities = [AccountProfileEntity::class, ActiveProfileEntity::class, ProfileAdoptionJournalEntity::class],
    version = 1,
    exportSchema = true
)
abstract class GlobalProfileRegistry : RoomDatabase() {
    abstract fun profiles(): GlobalProfileDao
}
