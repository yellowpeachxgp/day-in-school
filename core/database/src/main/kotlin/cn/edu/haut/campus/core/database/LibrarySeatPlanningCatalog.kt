package cn.edu.haut.campus.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.edu.haut.campus.core.campusservices.LibraryRegionSeatsSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatItem
import cn.edu.haut.campus.core.campusservices.LibraryStudyRegionItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** 按本地档案保存座位目录；不保存账号、令牌或空闲状态，不供即时预约使用。 */
class LibrarySeatPlanningCatalog(
    private val preferences: DataStore<Preferences>,
    private val queryRegions: suspend (String) -> Result<List<LibraryStudyRegionItem>>,
    private val querySeats: suspend (Long, String) -> Result<LibraryRegionSeatsSnapshot>,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    private val key = stringPreferencesKey("library_seat_planning_catalog_v1")
    private suspend fun read(): JSONObject = JSONObject(preferences.data.first()[key] ?: "{}")

    suspend fun loadRegions(date: String, refresh: Boolean = false): Result<List<LibraryStudyRegionItem>> {
        val cached = cacheRead { readRegions(it.optJSONArray("regions")) }.orEmpty()
        if (cached.isNotEmpty() && !refresh) return Result.success(cached)
        val started = nowEpochMillis()
        val fresh = queryRegions(date)
        val list = fresh.getOrNull().orEmpty()
        if (list.isNotEmpty()) {
            cacheWrite { root ->
                if (started >= root.optLong("regionsUpdatedAt")) {
                    root.put("regionsUpdatedAt", started).put("regions", JSONArray().apply {
                        list.forEach { r -> put(JSONObject().put("id", r.id).put("name", r.regionName)
                            .put("building", r.buildingName).put("floor", r.layerName)
                            .put("open", r.openTime).put("close", r.closeTime).put("total", r.totalSeats)) }
                    })
                }
            }
            return fresh
        }
        return if (cached.isNotEmpty()) Result.success(cached) else fresh
    }

    suspend fun loadSeats(region: LibraryStudyRegionItem, date: String, refresh: Boolean = false): Result<LibraryRegionSeatsSnapshot> {
        val cached = cacheRead { root ->
            root.optJSONObject("seats")?.optJSONObject(region.id.toString())?.takeIf {
                it.optString("name") == region.regionName
            }?.let { item ->
                LibraryRegionSeatsSnapshot(region.id, region.regionName, item.getString("date"),
                    "", "", false, buildList {
                        val seats = item.getJSONArray("items")
                        for (i in 0 until seats.length()) {
                            val seat = seats.getJSONObject(i)
                            add(LibrarySeatItem(seat.getLong("id"), seat.getString("name"), 0))
                        }
                    }, isCachedLayout = true)
            }
        }
        if (cached != null && !refresh) return Result.success(cached)
        val started = nowEpochMillis()
        val fresh = querySeats(region.id, date)
        val snap = fresh.getOrNull()
        if (snap != null && snap.regionId == region.id &&
            snap.bookingDate == date && snap.seats.isNotEmpty()) {
            cacheWrite { root ->
                val regions = root.optJSONObject("seats") ?: JSONObject()
                val previous = regions.optJSONObject(region.id.toString())
                if (started >= (previous?.optLong("updatedAt") ?: 0)) {
                    regions.put(region.id.toString(), JSONObject().put("updatedAt", started)
                        .put("name", region.regionName).put("date", snap.bookingDate)
                        .put("items", JSONArray().apply {
                            snap.seats.forEach { put(JSONObject().put("id", it.id).put("name", it.seatName)) }
                        }))
                    root.put("seats", regions)
                }
            }
            return fresh
        }
        return cached?.let { Result.success(it) } ?: fresh
    }

    private fun readRegions(items: JSONArray?): List<LibraryStudyRegionItem> = buildList {
        if (items != null) for (i in 0 until items.length()) {
            val r = items.getJSONObject(i)
            add(LibraryStudyRegionItem(r.getLong("id"), r.getString("name"),
                buildingName = r.optString("building"), layerName = r.optString("floor"),
                openTime = r.optString("open"), closeTime = r.optString("close"), totalSeats = r.optInt("total")))
        }
    }

    private suspend fun <T> cacheRead(block: (JSONObject) -> T): T? = try { block(read()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    private suspend fun cacheWrite(update: (JSONObject) -> Unit) {
        try {
            preferences.edit { prefs ->
                val root = runCatching { JSONObject(prefs[key] ?: "{}") }.getOrElse { JSONObject() }
                update(root)
                prefs[key] = root.toString()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 可选目录缓存写入失败，不影响刚取得的目录或已选计划。 */ }
    }
}
