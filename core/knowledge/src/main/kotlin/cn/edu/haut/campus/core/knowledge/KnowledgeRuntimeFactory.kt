package cn.edu.haut.campus.core.knowledge

import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.textextract.TextExtractPort
import cn.edu.haut.campus.core.vault.PersonalEventSnapshotStore
import cn.edu.haut.campus.core.vault.RoomEventOutbox
import cn.edu.haut.campus.core.vault.RoomPersonalEventStore
import cn.edu.haut.campus.core.vault.RoomSearchIndex
import cn.edu.haut.campus.core.vault.RoomVaultJournal
import cn.edu.haut.campus.core.vault.VaultBlobStore
import java.time.Instant

/** core:knowledge 的显式组合入口；Application/集成列车只注入账号库、scope 与 W2-04 抽取端口。 */
object KnowledgeRuntimeFactory {
    fun create(
        database: HautDatabase,
        scope: SchoolAccountScope,
        textExtractor: TextExtractPort,
        clock: () -> Instant = Instant::now
    ): KnowledgeRepository {
        val blobStore = VaultBlobStore(database = database, clock = clock)
        val searchIndex = RoomSearchIndex(database = database, clock = clock)
        val materialDocuments = VersionedBlobDocumentStore(
            database = database,
            blobStore = blobStore,
            scope = scope,
            ownerType = EntityType.COURSE_MATERIAL,
            mimeType = VersionedBlobDocumentStore.MATERIAL_MIME,
            clock = clock
        )
        val reviewDocuments = VersionedBlobDocumentStore(
            database = database,
            blobStore = blobStore,
            scope = scope,
            ownerType = EntityType.REVIEW_ITEM,
            mimeType = VersionedBlobDocumentStore.REVIEW_TASK_MIME,
            clock = clock
        )
        val searchProjector = KnowledgeSearchProjector(scope, searchIndex, blobStore)
        val materialSnapshots = CourseMaterialSnapshotStore(materialDocuments, scope, searchProjector, clock)
        val reviewSnapshots = ReviewTaskSnapshotStore(reviewDocuments, scope, clock)
        val journal = RoomVaultJournal(
            database = database,
            outbox = RoomEventOutbox(database),
            snapshotStores = listOf(
                materialSnapshots,
                reviewSnapshots,
                PersonalEventSnapshotStore(database, searchIndex)
            ),
            clock = clock,
            searchIndex = searchIndex
        )
        val personalEvents = RoomPersonalEventStore(
            database = database,
            scope = scope,
            writer = journal,
            searchIndex = searchIndex,
            clock = clock
        )
        return VaultKnowledgeRepository(
            database = database,
            scope = scope,
            blobStore = blobStore,
            searchIndex = searchIndex,
            searchProjector = searchProjector,
            writer = journal,
            materialDocuments = materialDocuments,
            reviewDocuments = reviewDocuments,
            personalEventStore = personalEvents,
            textExtractor = textExtractor,
            clock = clock
        )
    }
}
