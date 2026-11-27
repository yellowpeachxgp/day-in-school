package cn.edu.haut.campus.feature.settings.library.seat.state

import cn.edu.haut.campus.core.database.CampusRepository

/** 预约提交身份快照的状态层别名，避免 ui 引用 Repository 嵌套类型。 */
typealias BookingSubmissionIdentity = CampusRepository.BookingSubmissionIdentity
