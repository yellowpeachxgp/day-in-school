package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.edit
import cn.edu.haut.campus.core.model.OccurrenceOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** 旧版调课调入核对项的持久身份引用；Room 行仍是唯一候选内容来源。 */
internal data class LegacyRestoreReviewTargetRef(
    val id: String,
    val termId: String,
    val mutationToken: String
)

/**
 * 旧版调课「恢复完成」核对 journal 的 DataStore 编码。
 *
 * 只持久化候选行身份（id/termId/mutationToken），不复制教室、原因等课程详情；
 * UI 恢复时用身份引用回读 Room 当前行。行被删除或任一写路径改盖令牌后自动失效，
 * 避免把旧候选身份用于删除已经变化的新行。
 */
internal object LegacyRestoreReviewJournalCodec {
    private const val VERSION = 1

    fun decode(raw: String?, expectedProfileId: String): List<LegacyRestoreReviewTargetRef> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        if (root.optInt("version") != VERSION) return emptyList()
        if (root.optString("profileId") != expectedProfileId) return emptyList()
        val items = root.optJSONArray("targets") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val termId = item.optString("termId")
                val mutationToken = item.optString("mutationToken")
                if (id.isNotBlank() && termId.isNotBlank() && mutationToken.isNotBlank()) {
                    add(
                        LegacyRestoreReviewTargetRef(
                            id = id,
                            termId = termId,
                            mutationToken = mutationToken
                        )
                    )
                }
            }
        }
    }

    fun encode(
        profileId: String,
        targets: List<LegacyRestoreReviewTargetRef>
    ): String {
        val items = JSONArray()
        for (target in targets) {
            items.put(
                JSONObject()
                    .put("id", target.id)
                    .put("termId", target.termId)
                    .put("mutationToken", target.mutationToken)
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("profileId", profileId)
            .put("targets", items)
            .toString()
    }
}

/**
 * 按 Profile 绑定 DataStore 的旧版调入核对 journal。
 *
 * DataStore edit 是单文件原子提交；并发达成完成时按 id 合并，避免后完成恢复覆盖
 * 前一次尚未核对的候选。该 store 不写 Room schema，因此不需要 Room 版本迁移。
 */
internal class LegacyRestoreReviewJournalStore(
    private val environment: CampusRepositoryEnvironment
) {
    private val preferences = environment.preferences
    private val dao = environment.dao
    private val profileId: String get() = environment.accountProfile.id

    /** 当前仍存在的核对候选；行消失或 mutationToken 变化即自动从 UI 语义中失效。 */
    val targetsFlow: Flow<List<OccurrenceOverride>> =
        combine(
            preferences.data.map { prefs ->
                LegacyRestoreReviewJournalCodec.decode(
                    raw = prefs[CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON],
                    expectedProfileId = profileId
                )
            },
            dao.observeOccurrenceOverrides()
        ) { refs, rows ->
            val rowsById = rows.associateBy { it.id }
            refs.mapNotNull { ref ->
                rowsById[ref.id]
                    ?.takeIf { it.termId == ref.termId && it.mutationToken == ref.mutationToken }
                    ?.toDomain()
            }
        }

    suspend fun append(targets: List<OccurrenceOverride>) {
        val incoming = targets.mapNotNull { target ->
            if (target.id.isBlank() || target.termId.isBlank() || target.mutationToken.isBlank()) {
                null
            } else {
                LegacyRestoreReviewTargetRef(
                    id = target.id,
                    termId = target.termId,
                    mutationToken = target.mutationToken
                )
            }
        }.distinctBy { it.id }
        if (incoming.isEmpty()) return
        preferences.edit { prefs ->
            val existing = LegacyRestoreReviewJournalCodec.decode(
                raw = prefs[CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON],
                expectedProfileId = profileId
            )
            val merged = linkedMapOf<String, LegacyRestoreReviewTargetRef>()
            for (target in existing) merged[target.id] = target
            for (target in incoming) merged[target.id] = target
            prefs[CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON] =
                LegacyRestoreReviewJournalCodec.encode(
                    profileId = profileId,
                    targets = merged.values.toList()
                )
        }
    }

    suspend fun remove(overrideId: String) {
        if (overrideId.isBlank()) return
        preferences.edit { prefs ->
            val existing = LegacyRestoreReviewJournalCodec.decode(
                raw = prefs[CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON],
                expectedProfileId = profileId
            )
            val remaining = existing.filterNot { it.id == overrideId }
            if (remaining.size == existing.size) return@edit
            if (remaining.isEmpty()) {
                prefs.remove(CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON)
            } else {
                prefs[CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON] =
                    LegacyRestoreReviewJournalCodec.encode(
                        profileId = profileId,
                        targets = remaining
                    )
            }
        }
    }

    suspend fun clear() {
        preferences.edit { prefs ->
            prefs.remove(CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON)
        }
    }
}
