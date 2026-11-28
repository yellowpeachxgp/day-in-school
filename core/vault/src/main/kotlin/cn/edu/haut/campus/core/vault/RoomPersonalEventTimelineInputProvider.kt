package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.timeline.PersonalEventRecord
import cn.edu.haut.campus.core.timeline.PersonalEventTimelineInputProvider
import cn.edu.haut.campus.core.timeline.TimeWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * personal_events 的只读 Timeline 输入门面。投影裁剪仍完全交给
 * core:timeline 的 PersonalTimelineSource；这里只把当前 scope 的权威 Room 行
 * 还原为纯 JVM 记录，不复制窗口过滤或事件映射语义。
 */
class RoomPersonalEventTimelineInputProvider(
    database: HautDatabase,
    private val scope: SchoolAccountScope
) : PersonalEventTimelineInputProvider {
    private val dao = database.vaultDao()

    override fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<List<PersonalEventRecord>> {
        require(scope == this.scope) { "PersonalEvent Timeline 输入只能观察本 profile scope" }
        return dao.observePersonalEvents().map { rows ->
            rows.filter { it.inScope(scope) }
                .map { it.toPersonalEventRecord() }
        }
    }
}
