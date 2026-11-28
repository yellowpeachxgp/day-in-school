package cn.edu.haut.campus.feature.knowledge.state

/** 课程空间一次性导航/平台事件；不作为渲染状态保存。 */
sealed interface CourseSpaceUiEffect {
    data class OpenMaterial(val materialId: String) : CourseSpaceUiEffect
    data class ExportCoursePackage(val courseId: String) : CourseSpaceUiEffect
    data object RequestFilePermission : CourseSpaceUiEffect
    data object NavigateToLogin : CourseSpaceUiEffect
}
