package cn.edu.haut.campus.core.knowledge

import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.SchoolAccountScope
import java.io.OutputStream

/** 课程知识空间端口。实现必须本地优先、按 scope 隔离，不得联网或代理 AI。 */
interface KnowledgeRepository : AutoCloseable {
    /** 关闭工厂创建的 outbox 门面；调用后本 repository 不得再写入。 */
    suspend fun archiveMaterial(request: ArchiveMaterialRequest, actor: Actor = Actor.USER): CourseMaterial

    suspend fun getMaterial(scope: SchoolAccountScope, materialId: String): CourseMaterial?

    suspend fun listMaterials(
        scope: SchoolAccountScope,
        courseId: String? = null,
        teachingWeek: Int? = null
    ): List<CourseMaterial>

    suspend fun searchMaterials(
        scope: SchoolAccountScope,
        text: String,
        courseId: String? = null,
        teachingWeek: Int? = null,
        limit: Int = 20
    ): List<KnowledgeSearchHit>

    suspend fun setExtractionConfirmed(
        scope: SchoolAccountScope,
        materialId: String,
        confirmed: Boolean,
        actor: Actor = Actor.USER
    ): CourseMaterial

    suspend fun upsertReviewTask(task: ReviewTask, actor: Actor = Actor.USER): ReviewTask

    suspend fun listReviewTasks(
        scope: SchoolAccountScope,
        courseId: String? = null,
        state: ReviewTaskState? = null
    ): List<ReviewTask>

    /** 导出为 zip；实现不得把未请求的其它课程或其它 scope 混入。 */
    suspend fun exportCoursePackage(
        scope: SchoolAccountScope,
        courseId: String,
        output: OutputStream,
        teachingWeeks: Set<Int> = emptySet()
    ): KnowledgeExportSummary
}
