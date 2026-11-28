package cn.edu.haut.campus.core.degree

import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.vault.RoomEventOutbox
import cn.edu.haut.campus.core.vault.RoomSearchIndex
import cn.edu.haut.campus.core.vault.RoomVaultJournal
import cn.edu.haut.campus.core.vault.VaultBlobStore
import java.time.Instant

/** core:degree 的显式组合入口；Application/集成列车只注入账号库与 profile scope。 */
object DegreeRuntimeFactory {
    fun create(
        database: HautDatabase,
        scope: SchoolAccountScope,
        clock: () -> Instant = Instant::now
    ): DegreeRepository {
        val blobStore = VaultBlobStore(database = database, clock = clock)
        val planDocuments = VersionedDegreeDocumentStore(
            database = database,
            blobStore = blobStore,
            scope = scope,
            ownerType = EntityType.TRAINING_PLAN_IMPORT,
            mimeType = VersionedDegreeDocumentStore.PLAN_IMPORT_MIME,
            clock = clock
        )
        val overrideDocuments = VersionedDegreeDocumentStore(
            database = database,
            blobStore = blobStore,
            scope = scope,
            ownerType = EntityType.AUDIT_OVERRIDE,
            mimeType = VersionedDegreeDocumentStore.AUDIT_OVERRIDE_MIME,
            clock = clock
        )
        val outbox = RoomEventOutbox(database)
        val searchIndex = RoomSearchIndex(database = database, clock = clock)
        val journal = RoomVaultJournal(
            database = database,
            outbox = outbox,
            snapshotStores = listOf(
                TrainingPlanImportSnapshotStore(planDocuments, scope, clock),
                AuditOverrideSnapshotStore(overrideDocuments, scope, clock)
            ),
            clock = clock,
            searchIndex = searchIndex
        )
        return VaultDegreeRepository(
            scope = scope,
            writer = journal,
            planDocuments = planDocuments,
            overrideDocuments = overrideDocuments,
            clock = clock,
            ownedCloseables = listOf(outbox)
        )
    }
}
