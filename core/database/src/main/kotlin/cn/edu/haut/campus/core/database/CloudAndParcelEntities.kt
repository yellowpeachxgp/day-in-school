package cn.edu.haut.campus.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cloud_identity_binding")
data class CloudIdentityBindingEntity(
    @PrimaryKey val profileId: String,
    val cloudUserId: String,
    val ssoIssuer: String,
    val ssoSubject: String,
    val displayName: String,
    val peachBalanceMicropeach: Long,
    val holdSupported: Boolean = false,
    val linkedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "library_booking_plans",
    indices = [
        Index(value = ["targetLocalDate", "executionMode"]),
        Index(value = ["status"])
    ]
)
data class LibraryBookingPlanEntity(
    @PrimaryKey val id: String,
    val schoolId: String,
    val executionMode: String, // LOCAL_DEVICE | CLOUD_HOSTED | MANUAL_REMINDER
    val targetLocalDate: String, // YYYY-MM-DD
    val targetLocalTime: String, // HH:mm:ss
    val timezoneId: String,
    val precheckAtEpochMillis: Long, // T - 30m
    val finalCheckAtEpochMillis: Long, // T - 5m
    val fireAtEpochMillis: Long, // T
    val precheckStatus: String,
    val finalCheckStatus: String,
    val fireStatus: String,
    val preferredAreaName: String,
    val preferredSeatLabel: String,
    val allowAlternativeSeat: Boolean,
    val cloudJobId: String?,
    val externalBookingId: String?,
    val evidenceSha256: String?,
    val priceMicropeach: Long,
    val paymentState: String, // NOT_REQUIRED | DEBIT_PENDING | PAID | PAYMENT_DUE | REFUNDED
    val status: String, // ARMED | PRECHECK_OK | FINAL_CHECK_OK | EXECUTING | CONFIRMED | RESULT_UNKNOWN | FAILED | CANCELLED
    val stateVersion: Int,
    val generation: Int,
    val lastDiagnosticMessage: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val repeatDays: Int = 1,
    val remainingDays: Int = 1
)

@Entity(
    tableName = "parcel_items",
    indices = [
        Index(value = ["trackingNumber"], unique = true),
        Index(value = ["status"])
    ]
)
data class ParcelItemEntity(
    @PrimaryKey val id: String,
    val trackingNumber: String,
    val carrierCode: String,
    val carrierName: String,
    val status: String, // IN_TRANSIT | OUT_FOR_DELIVERY | READY_FOR_PICKUP | PICKED_UP | EXCEPTION
    val pickupCode: String?,
    val stationName: String?,
    val itemTitle: String?,
    val estimatedDeliveryText: String,
    val sourcesJson: String,
    val fieldProvenanceJson: String,
    val checkpointsJson: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "parcel_source_connections",
    indices = [Index(value = ["providerId"])]
)
data class ParcelSourceConnectionEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val accountAlias: String,
    val authorityStatus: String,
    val status: String, // ACTIVE | REVOKED
    val updatedAtEpochMillis: Long
)

@Entity(tableName = "installed_extension_plugins")
data class InstalledExtensionPluginEntity(
    @PrimaryKey val pluginId: String,
    val name: String,
    val activeVersion: String,
    val previousVersion: String?,
    val schemaFormat: String, // dis-extension/v2 | dis-plugin/v2 | dis-campus-plugin/v1
    val status: String, // ENABLED | DISABLED
    val grantedPermissionsJson: String,
    val uiSlotsJson: String,
    val updatedAtEpochMillis: Long
)

@Dao
interface CloudAndParcelDao {
    @Query("SELECT * FROM cloud_identity_binding LIMIT 1")
    fun observeCloudIdentityBinding(): Flow<CloudIdentityBindingEntity?>

    @Query("SELECT * FROM cloud_identity_binding LIMIT 1")
    suspend fun getCloudIdentityBindingSnapshot(): CloudIdentityBindingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCloudIdentityBinding(entity: CloudIdentityBindingEntity)

    @Query("DELETE FROM cloud_identity_binding")
    suspend fun clearCloudIdentityBinding()

    @Query("SELECT * FROM library_booking_plans ORDER BY targetLocalDate DESC, targetLocalTime DESC")
    fun observeLibraryBookingPlans(): Flow<List<LibraryBookingPlanEntity>>

    @Query("SELECT * FROM library_booking_plans ORDER BY targetLocalDate DESC, targetLocalTime DESC")
    suspend fun getLibraryBookingPlansSnapshot(): List<LibraryBookingPlanEntity>

    @Query("SELECT * FROM library_booking_plans WHERE id = :id LIMIT 1")
    suspend fun getLibraryBookingPlanById(id: String): LibraryBookingPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLibraryBookingPlan(plan: LibraryBookingPlanEntity)

    @Query("DELETE FROM library_booking_plans WHERE id = :id")
    suspend fun deleteLibraryBookingPlan(id: String)

    @Query("DELETE FROM library_booking_plans")
    suspend fun clearLibraryBookingPlans()

    @Query("SELECT * FROM parcel_items ORDER BY updatedAtEpochMillis DESC")
    fun observeParcelItems(): Flow<List<ParcelItemEntity>>

    @Query("SELECT * FROM parcel_items ORDER BY updatedAtEpochMillis DESC")
    suspend fun getParcelItemsSnapshot(): List<ParcelItemEntity>

    @Query("SELECT * FROM parcel_items WHERE trackingNumber = :trackingNumber LIMIT 1")
    suspend fun getParcelByTrackingNumber(trackingNumber: String): ParcelItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParcelItem(item: ParcelItemEntity)

    @Query("DELETE FROM parcel_items WHERE id = :id")
    suspend fun deleteParcelItemById(id: String)

    @Query("DELETE FROM parcel_items")
    suspend fun clearParcelItems()

    @Query("SELECT * FROM parcel_source_connections ORDER BY updatedAtEpochMillis DESC")
    fun observeParcelSourceConnections(): Flow<List<ParcelSourceConnectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParcelSourceConnection(conn: ParcelSourceConnectionEntity)

    @Query("DELETE FROM parcel_source_connections WHERE id = :id")
    suspend fun deleteParcelSourceConnection(id: String)

    @Query("SELECT * FROM installed_extension_plugins ORDER BY pluginId ASC")
    fun observeInstalledExtensionPlugins(): Flow<List<InstalledExtensionPluginEntity>>

    @Query("SELECT * FROM installed_extension_plugins ORDER BY pluginId ASC")
    suspend fun getInstalledExtensionPluginsSnapshot(): List<InstalledExtensionPluginEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInstalledExtensionPlugin(plugin: InstalledExtensionPluginEntity)

    @Query("DELETE FROM installed_extension_plugins WHERE pluginId = :pluginId")
    suspend fun deleteInstalledExtensionPlugin(pluginId: String)
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cloud_identity_binding` (
                `profileId` TEXT NOT NULL,
                `cloudUserId` TEXT NOT NULL,
                `ssoIssuer` TEXT NOT NULL,
                `ssoSubject` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `peachBalanceMicropeach` INTEGER NOT NULL,
                `holdSupported` INTEGER NOT NULL,
                `linkedAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`profileId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_booking_plans` (
                `id` TEXT NOT NULL,
                `schoolId` TEXT NOT NULL,
                `executionMode` TEXT NOT NULL,
                `targetLocalDate` TEXT NOT NULL,
                `targetLocalTime` TEXT NOT NULL,
                `timezoneId` TEXT NOT NULL,
                `precheckAtEpochMillis` INTEGER NOT NULL,
                `finalCheckAtEpochMillis` INTEGER NOT NULL,
                `fireAtEpochMillis` INTEGER NOT NULL,
                `precheckStatus` TEXT NOT NULL,
                `finalCheckStatus` TEXT NOT NULL,
                `fireStatus` TEXT NOT NULL,
                `preferredAreaName` TEXT NOT NULL,
                `preferredSeatLabel` TEXT NOT NULL,
                `allowAlternativeSeat` INTEGER NOT NULL,
                `cloudJobId` TEXT,
                `externalBookingId` TEXT,
                `evidenceSha256` TEXT,
                `priceMicropeach` INTEGER NOT NULL,
                `paymentState` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `stateVersion` INTEGER NOT NULL,
                `generation` INTEGER NOT NULL,
                `lastDiagnosticMessage` TEXT,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                `repeatDays` INTEGER NOT NULL DEFAULT 1,
                `remainingDays` INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_library_booking_plans_targetLocalDate_executionMode` ON `library_booking_plans` (`targetLocalDate`, `executionMode`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_library_booking_plans_status` ON `library_booking_plans` (`status`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `parcel_items` (
                `id` TEXT NOT NULL,
                `trackingNumber` TEXT NOT NULL,
                `carrierCode` TEXT NOT NULL,
                `carrierName` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `pickupCode` TEXT,
                `stationName` TEXT,
                `itemTitle` TEXT,
                `estimatedDeliveryText` TEXT NOT NULL,
                `sourcesJson` TEXT NOT NULL,
                `fieldProvenanceJson` TEXT NOT NULL,
                `checkpointsJson` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_parcel_items_trackingNumber` ON `parcel_items` (`trackingNumber`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_parcel_items_status` ON `parcel_items` (`status`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `parcel_source_connections` (
                `id` TEXT NOT NULL,
                `providerId` TEXT NOT NULL,
                `accountAlias` TEXT NOT NULL,
                `authorityStatus` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_parcel_source_connections_providerId` ON `parcel_source_connections` (`providerId`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `installed_extension_plugins` (
                `pluginId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `activeVersion` TEXT NOT NULL,
                `previousVersion` TEXT,
                `schemaFormat` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `grantedPermissionsJson` TEXT NOT NULL,
                `uiSlotsJson` TEXT NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`pluginId`)
            )
            """.trimIndent()
        )
    }
}
