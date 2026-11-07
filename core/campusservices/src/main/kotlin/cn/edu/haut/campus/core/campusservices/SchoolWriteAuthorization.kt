package cn.edu.haut.campus.core.campusservices

/**
 * Production writes stay closed until school-issued authorization is independently verified.
 * A saved password, user consent, SSO login or a successful mock is not that authorization.
 * Reopening requires reviewed evidence, provider-scoped limits and replayable tests; no UI bypass.
 * Offline tools, official manual WebViews and synthetic mock adapters remain available.
 */
object SchoolWriteAuthorization {
    const val automaticWritesEnabled: Boolean = false
    const val blockedMessage: String = "尚未验证学校自动写入授权，自动预约与原生写入已暂停；请使用学校官方网页手动操作，离线功能与到点提醒仍可用"

    fun requireVerified() {
        check(automaticWritesEnabled) { blockedMessage }
    }
}
