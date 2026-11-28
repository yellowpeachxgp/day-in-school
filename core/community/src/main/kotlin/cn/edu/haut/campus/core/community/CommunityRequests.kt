package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.json.JsonValue

/** 写请求体的本地契约形状。服务端仍是权威校验；这里只做可证明的 fail-fast 校验。 */
interface CommunityRequestBody {
    fun toJson(): JsonValue.Obj
}

data class AvailabilityBlockInput(
    val startAt: String,
    val endAt: String,
) {
    init {
        requireAlignedInstantRange(startAt, endAt, "start_at", "end_at")
    }

    fun toJson(): JsonValue.Obj = jsonObject(
        linkedMapOf(
            "start_at" to JsonValue.Str(startAt),
            "end_at" to JsonValue.Str(endAt),
        ),
    )
}

data class GroupPollOptionInput(
    val startAt: String,
    val endAt: String,
) {
    init {
        requireAlignedInstantRange(startAt, endAt, "start_at", "end_at")
    }

    fun toJson(): JsonValue.Obj = jsonObject(
        linkedMapOf(
            "start_at" to JsonValue.Str(startAt),
            "end_at" to JsonValue.Str(endAt),
        ),
    )
}

data class CreateGroupRequest(
    val schoolId: String,
    val type: String,
    val name: String,
    val description: String? = null,
) : CommunityRequestBody {
    init {
        requireText(schoolId, "school_id")
        requireWireEnum(type, setOf("study", "dorm", "club", "project"), "type")
        requireText(name, "name", 80)
        requireOptionalText(description, "description", 500, allowBlank = true)
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["school_id"] = JsonValue.Str(schoolId)
        fields["type"] = JsonValue.Str(type)
        fields["name"] = JsonValue.Str(name)
        fields.putString("description", description)
        return jsonObject(fields)
    }
}

/**
 * description 使用三态 JSON 值：不传 null 表示省略；传 JsonValue.Null 表示按契约显式清空；
 * 传 JsonValue.Str 表示更新文本。
 */
data class UpdateGroupRequest(
    val name: String? = null,
    val description: JsonValue? = null,
) : CommunityRequestBody {
    init {
        requireOptionalText(name, "name", 80)
        require(name != null || description != null) { "UpdateGroupRequest 至少要更新一个字段" }
        if (description != null) {
            require(description is JsonValue.Str || description == JsonValue.Null) {
                "description 只能是字符串、JsonValue.Null 或省略"
            }
            (description as? JsonValue.Str)?.let { require(it.value.length <= 500) { "description 长度不能超过 500" } }
        }
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields.putString("name", name)
        fields.putJson("description", description)
        return jsonObject(fields)
    }
}

data class CreateGroupInviteRequest(
    val expiresAt: String,
    val maxUses: Int,
    val label: String? = null,
) : CommunityRequestBody {
    init {
        requireIsoDateTime(expiresAt, "expires_at")
        require(maxUses in 1..1000) { "max_uses 必须在 1..1000" }
        requireOptionalText(label, "label", 80, allowBlank = true)
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["expires_at"] = JsonValue.Str(expiresAt)
        fields["max_uses"] = JsonValue.Num(maxUses.toString())
        fields.putString("label", label)
        return jsonObject(fields)
    }
}

/**
 * AUTO-030 从严收窄后的成员管理请求：只允许管理角色，类型层面不存在 sharingLevel。
 * 客户端无法证明任意 memberId 就是调用者本人，因此不提供任何经该端点代改 sharing_level 的 API 路径；
 * 忙闲共享/撤销只走 ReplaceGroupAvailabilityRequest 与 /availability/me 自服务端点，由服务端按调用者身份派生 sharing_level。
 */
data class UpdateGroupMemberRequest(
    val role: String,
) : CommunityRequestBody {
    init {
        requireWireEnum(role, setOf("OWNER", "ADMIN", "MEMBER"), "role")
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields.putString("role", role)
        return jsonObject(fields)
    }
}

/** 自服务忙闲共享的唯一客户端写入类型；不包含 memberId 或 sharingLevel，shared/NONE 状态由自服务端点语义派生。 */
data class ReplaceGroupAvailabilityRequest(
    val blocks: List<AvailabilityBlockInput>,
    val ttlSeconds: Int,
) : CommunityRequestBody {
    init {
        require(blocks.size <= 200) { "blocks 数量不能超过 200" }
        require(ttlSeconds in 1800..7_776_000) { "ttl_seconds 必须在 1800..7776000" }
        val ordered = blocks.sortedWith(compareBy({ epochMillisIsoDateTime(it.startAt) }, { epochMillisIsoDateTime(it.endAt) }))
        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1]
            val current = ordered[index]
            require(epochMillisIsoDateTime(current.startAt) >= epochMillisIsoDateTime(previous.endAt)) {
                "blocks 必须互不重叠且不得包含重复窗口"
            }
        }
    }

    override fun toJson(): JsonValue.Obj = jsonObject(
        linkedMapOf(
            "blocks" to jsonObjectArray(blocks.map { it.toJson() }),
            "ttl_seconds" to JsonValue.Num(ttlSeconds.toString()),
        ),
    )
}

data class GroupAvailabilityQueryRequest(
    val windowStartAt: String,
    val windowEndAt: String,
    val durationMinutes: Int,
    val minAttendees: Int,
    val memberIds: List<String> = emptyList(),
    val granularityMinutes: Int = 30,
) : CommunityRequestBody {
    init {
        require(granularityMinutes >= 30 && granularityMinutes % 30 == 0) { "granularity_minutes 必须是 30 的正整数倍" }
        requireAlignedInstantRange(windowStartAt, windowEndAt, "window_start_at", "window_end_at", granularityMinutes)
        require(durationMinutes >= 30 && durationMinutes % 30 == 0) { "duration_minutes 必须是 30 的正整数倍" }
        val windowMillis = epochMillisIsoDateTime(windowEndAt) - epochMillisIsoDateTime(windowStartAt)
        require(durationMinutes.toLong() * 60_000L <= windowMillis) { "duration_minutes 必须能放入查询窗口" }
        require(minAttendees >= 1) { "min_attendees 至少为 1" }
        require(memberIds.size <= 100 && memberIds.distinct().size == memberIds.size) {
            "member_ids 必须唯一且不超过 100 个"
        }
        memberIds.forEach { requireUuid(it, "member_ids[]") }
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["window_start_at"] = JsonValue.Str(windowStartAt)
        fields["window_end_at"] = JsonValue.Str(windowEndAt)
        fields["duration_minutes"] = JsonValue.Num(durationMinutes.toString())
        fields["min_attendees"] = JsonValue.Num(minAttendees.toString())
        if (memberIds.isNotEmpty()) fields["member_ids"] = jsonStringArray(memberIds)
        fields["granularity_minutes"] = JsonValue.Num(granularityMinutes.toString())
        return jsonObject(fields)
    }
}

data class CreateGroupPollRequest(
    val title: String,
    val options: List<GroupPollOptionInput>,
    val votingDeadlineAt: String,
) : CommunityRequestBody {
    init {
        requireText(title, "title", 120)
        require(options.size in 2..10 && options.distinct().size == options.size) { "options 必须有 2..10 个互不相同的候选时段" }
        requireIsoDateTime(votingDeadlineAt, "voting_deadline_at")
        val earliestStart = options.minOf { epochMillisIsoDateTime(it.startAt) }
        require(epochMillisIsoDateTime(votingDeadlineAt) <= earliestStart) {
            "voting_deadline_at 不得晚于最早候选开始时间"
        }
    }

    override fun toJson(): JsonValue.Obj = jsonObject(
        linkedMapOf(
            "title" to JsonValue.Str(title),
            "options" to jsonObjectArray(options.map { it.toJson() }),
            "voting_deadline_at" to JsonValue.Str(votingDeadlineAt),
        ),
    )
}

data class UpsertGroupPollVoteRequest(val optionId: String) : CommunityRequestBody {
    init { requireUuid(optionId, "option_id") }
    override fun toJson(): JsonValue.Obj = jsonObject(linkedMapOf("option_id" to JsonValue.Str(optionId)))
}

data class ConfirmGroupPollRequest(val optionId: String) : CommunityRequestBody {
    init { requireUuid(optionId, "option_id") }
    override fun toJson(): JsonValue.Obj = jsonObject(linkedMapOf("option_id" to JsonValue.Str(optionId)))
}

data class LedgerRatioWeightInput(
    val memberId: String,
    val weight: Long,
) {
    init {
        requireUuid(memberId, "member_id")
        require(weight in 0..1_000_000L) { "weight 必须在 0..1000000" }
    }

    fun toJson(): JsonValue.Obj = jsonObject(
        linkedMapOf(
            "member_id" to JsonValue.Str(memberId),
            "weight" to JsonValue.Num(weight.toString()),
        ),
    )
}

data class LedgerStayDaysInput(
    val memberId: String,
    val days: Long,
) {
    init {
        requireUuid(memberId, "member_id")
        require(days in 0..366L) { "days 必须在 0..366" }
    }

    fun toJson(): JsonValue.Obj = jsonObject(
        linkedMapOf(
            "member_id" to JsonValue.Str(memberId),
            "days" to JsonValue.Num(days.toString()),
        ),
    )
}

data class LedgerSplitRuleInput(
    val type: String,
    val participantMemberIds: List<String> = emptyList(),
    val ratioWeights: List<LedgerRatioWeightInput> = emptyList(),
    val stayDays: List<LedgerStayDaysInput> = emptyList(),
) {
    init {
        requireWireEnum(type, setOf("EQUAL", "SELECTED", "RATIOS", "STAY_DAYS"), "split_rule.type")
        when (type) {
            "EQUAL" -> require(participantMemberIds.isEmpty() && ratioWeights.isEmpty() && stayDays.isEmpty()) {
                "EQUAL split_rule 不允许附带参与人、比例或入住天数"
            }
            "SELECTED" -> require(participantMemberIds.isNotEmpty() && ratioWeights.isEmpty() && stayDays.isEmpty()) {
                "SELECTED split_rule 必须且只能包含 participant_member_ids"
            }
            "RATIOS" -> require(ratioWeights.isNotEmpty() && participantMemberIds.isEmpty() && stayDays.isEmpty()) {
                "RATIOS split_rule 必须且只能包含 ratio_weights"
            }
            "STAY_DAYS" -> require(stayDays.isNotEmpty() && participantMemberIds.isEmpty() && ratioWeights.isEmpty()) {
                "STAY_DAYS split_rule 必须且只能包含 stay_days"
            }
        }
        require(participantMemberIds.size <= 100 && participantMemberIds.distinct().size == participantMemberIds.size) {
            "participant_member_ids 必须唯一且不超过 100 个"
        }
        participantMemberIds.forEach { requireUuid(it, "participant_member_ids[]") }
        require(ratioWeights.size <= 100) { "ratio_weights 不能超过 100 个" }
        require(ratioWeights.distinctBy { it.memberId }.size == ratioWeights.size) { "ratio_weights 不得重复 member_id" }
        if (type == "RATIOS") require(ratioWeights.any { it.weight > 0L }) { "ratio_weights 至少一个正权重" }
        require(stayDays.size <= 100) { "stay_days 不能超过 100 个" }
        require(stayDays.distinctBy { it.memberId }.size == stayDays.size) { "stay_days 不得重复 member_id" }
        if (type == "STAY_DAYS") require(stayDays.any { it.days > 0L }) { "stay_days 至少一个正天数" }
    }

    fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["type"] = JsonValue.Str(type)
        if (participantMemberIds.isNotEmpty()) fields["participant_member_ids"] = jsonStringArray(participantMemberIds)
        if (ratioWeights.isNotEmpty()) fields["ratio_weights"] = jsonObjectArray(ratioWeights.map { it.toJson() })
        if (stayDays.isNotEmpty()) fields["stay_days"] = jsonObjectArray(stayDays.map { it.toJson() })
        return jsonObject(fields)
    }
}

data class CreateLedgerEntryRequest(
    val title: String,
    val amountFen: Long,
    val paidByMemberId: String,
    val occurredAt: String,
    val splitRule: LedgerSplitRuleInput,
    val entryKind: String = "EXPENSE",
    val note: String? = null,
) : CommunityRequestBody {
    init {
        requireWireEnum(entryKind, setOf("EXPENSE", "REFUND"), "entry_kind")
        requireText(title, "title", 120)
        requireOptionalText(note, "note", 500, allowBlank = true)
        require(amountFen != 0L) { "amount_fen 不能为 0" }
        requireUuid(paidByMemberId, "paid_by_member_id")
        requireIsoDateTime(occurredAt, "occurred_at")
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["entry_kind"] = JsonValue.Str(entryKind)
        fields["title"] = JsonValue.Str(title)
        fields.putString("note", note)
        fields["amount_fen"] = JsonValue.Num(amountFen.toString())
        fields["paid_by_member_id"] = JsonValue.Str(paidByMemberId)
        fields["occurred_at"] = JsonValue.Str(occurredAt)
        fields["split_rule"] = splitRule.toJson()
        return jsonObject(fields)
    }
}

data class AmendLedgerEntryRequest(
    val reason: String,
    val amountFen: Long,
    val paidByMemberId: String,
    val occurredAt: String,
    val splitRule: LedgerSplitRuleInput,
    val title: String? = null,
) : CommunityRequestBody {
    init {
        requireText(reason, "reason", 240)
        requireOptionalText(title, "title", 120)
        require(amountFen != 0L) { "amount_fen 不能为 0" }
        requireUuid(paidByMemberId, "paid_by_member_id")
        requireIsoDateTime(occurredAt, "occurred_at")
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["reason"] = JsonValue.Str(reason)
        fields.putString("title", title)
        fields["amount_fen"] = JsonValue.Num(amountFen.toString())
        fields["paid_by_member_id"] = JsonValue.Str(paidByMemberId)
        fields["occurred_at"] = JsonValue.Str(occurredAt)
        fields["split_rule"] = splitRule.toJson()
        return jsonObject(fields)
    }
}

data class CreateSettlementRequest(
    val debtorMemberId: String,
    val creditorMemberId: String,
    val amountFen: Long,
    val basisBalanceRevision: Long,
    val note: String? = null,
) : CommunityRequestBody {
    init {
        requireUuid(debtorMemberId, "debtor_member_id")
        requireUuid(creditorMemberId, "creditor_member_id")
        require(debtorMemberId != creditorMemberId) { "结算双方不能是同一成员" }
        require(amountFen >= 1L) { "amount_fen 至少为 1" }
        require(basisBalanceRevision >= 0L) { "basis_balance_revision 不能为负" }
        requireOptionalText(note, "note", 240, allowBlank = true)
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["debtor_member_id"] = JsonValue.Str(debtorMemberId)
        fields["creditor_member_id"] = JsonValue.Str(creditorMemberId)
        fields["amount_fen"] = JsonValue.Num(amountFen.toString())
        fields["basis_balance_revision"] = JsonValue.Num(basisBalanceRevision.toString())
        fields.putString("note", note)
        return jsonObject(fields)
    }
}

data class ConfirmSettlementRequest(val basisBalanceRevision: Long) : CommunityRequestBody {
    init { require(basisBalanceRevision >= 0L) { "basis_balance_revision 不能为负" } }
    override fun toJson(): JsonValue.Obj = jsonObject(
        linkedMapOf("basis_balance_revision" to JsonValue.Num(basisBalanceRevision.toString())),
    )
}

data class CancelSettlementRequest(val reason: String? = null) : CommunityRequestBody {
    init { requireOptionalText(reason, "reason", 240, allowBlank = true) }
    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields.putString("reason", reason)
        return jsonObject(fields)
    }
}


data class CreateDutyRotaRequest(
    val title: String,
    val rotationMemberIds: List<String>,
    val frequency: String,
    val startDate: String,
    val timeZone: String,
    val description: String? = null,
    val daysOfWeek: List<Int> = emptyList(),
    val endDate: String? = null,
    val generateWeeksAhead: Int = 2,
) : CommunityRequestBody {
    init {
        requireText(title, "title", 80)
        requireOptionalText(description, "description", 500, allowBlank = true)
        require(rotationMemberIds.size in 2..100 && rotationMemberIds.distinct().size == rotationMemberIds.size) {
            "rotation_member_ids 必须唯一且包含 2..100 个成员"
        }
        rotationMemberIds.forEach { requireUuid(it, "rotation_member_ids[]") }
        requireWireEnum(frequency, setOf("DAILY", "WEEKLY"), "frequency")
        require(daysOfWeek.size <= 7 && daysOfWeek.distinct().size == daysOfWeek.size && daysOfWeek.all { it in 1..7 }) {
            "days_of_week 必须是唯一的 ISO 1..7 星期值"
        }
        if (frequency == "WEEKLY") require(daysOfWeek.isNotEmpty()) { "WEEKLY 值日必须提供 days_of_week" }
        requireIsoDate(startDate, "start_date")
        endDate?.let {
            requireIsoDate(it, "end_date")
            require(java.time.LocalDate.parse(it) >= java.time.LocalDate.parse(startDate)) { "end_date 不得早于 start_date" }
        }
        runCatching { java.time.ZoneId.of(timeZone) }.getOrElse {
            throw IllegalArgumentException("time_zone 必须是有效 IANA 时区", it)
        }
        require(generateWeeksAhead in 1..12) { "generate_weeks_ahead 必须在 1..12" }
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["title"] = JsonValue.Str(title)
        fields.putString("description", description)
        fields["rotation_member_ids"] = jsonStringArray(rotationMemberIds)
        fields["frequency"] = JsonValue.Str(frequency)
        if (daysOfWeek.isNotEmpty()) fields["days_of_week"] = JsonValue.Arr(daysOfWeek.map { JsonValue.Num(it.toString()) })
        fields["start_date"] = JsonValue.Str(startDate)
        fields.putString("end_date", endDate)
        fields["time_zone"] = JsonValue.Str(timeZone)
        fields["generate_weeks_ahead"] = JsonValue.Num(generateWeeksAhead.toString())
        return jsonObject(fields)
    }
}

/** description/endDate 的 JsonValue.Null 表示契约允许的显式清空；构造参数为 null 表示省略。 */
data class UpdateDutyRotaRequest(
    val title: String? = null,
    val description: JsonValue? = null,
    val rotationMemberIds: List<String>? = null,
    val frequency: String? = null,
    val daysOfWeek: List<Int>? = null,
    val endDate: JsonValue? = null,
    val status: String? = null,
    val generateWeeksAhead: Int? = null,
) : CommunityRequestBody {
    init {
        requireOptionalText(title, "title", 80)
        if (description != null) {
            require(description is JsonValue.Str || description == JsonValue.Null) { "description 只能是字符串、Null 或省略" }
            (description as? JsonValue.Str)?.let { require(it.value.length <= 500) { "description 长度不能超过 500" } }
        }
        rotationMemberIds?.let {
            require(it.size in 2..100 && it.distinct().size == it.size) {
                "rotation_member_ids 必须唯一且包含 2..100 个成员"
            }
            it.forEach { member -> requireUuid(member, "rotation_member_ids[]") }
        }
        frequency?.let { requireWireEnum(it, setOf("DAILY", "WEEKLY"), "frequency") }
        daysOfWeek?.let {
            require(it.size <= 7 && it.distinct().size == it.size && it.all { day -> day in 1..7 }) {
                "days_of_week 必须是唯一的 ISO 1..7 星期值"
            }
        }
        if (endDate != null) {
            require(endDate is JsonValue.Str || endDate == JsonValue.Null) { "end_date 只能是日期字符串、Null 或省略" }
            (endDate as? JsonValue.Str)?.let { requireIsoDate(it.value, "end_date") }
        }
        status?.let { requireWireEnum(it, setOf("ACTIVE", "PAUSED", "ARCHIVED"), "status") }
        generateWeeksAhead?.let { require(it in 1..12) { "generate_weeks_ahead 必须在 1..12" } }
        require(
            title != null || description != null || rotationMemberIds != null || frequency != null ||
                daysOfWeek != null || endDate != null || status != null || generateWeeksAhead != null,
        ) { "UpdateDutyRotaRequest 至少要更新一个字段" }
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields.putString("title", title)
        fields.putJson("description", description)
        rotationMemberIds?.let { fields["rotation_member_ids"] = jsonStringArray(it) }
        fields.putString("frequency", frequency)
        daysOfWeek?.let { days -> fields["days_of_week"] = JsonValue.Arr(days.map { JsonValue.Num(it.toString()) }) }
        fields.putJson("end_date", endDate)
        fields.putString("status", status)
        fields.putNumber("generate_weeks_ahead", generateWeeksAhead)
        return jsonObject(fields)
    }
}

data class CompleteDutyTaskRequest(val completionNote: String? = null) : CommunityRequestBody {
    init { requireOptionalText(completionNote, "completion_note", 240, allowBlank = true) }
    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields.putString("completion_note", completionNote)
        return jsonObject(fields)
    }
}

data class ConfirmDutyTaskRequest(val confirmationNote: String? = null) : CommunityRequestBody {
    init { requireOptionalText(confirmationNote, "confirmation_note", 240, allowBlank = true) }
    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields.putString("confirmation_note", confirmationNote)
        return jsonObject(fields)
    }
}

data class SkipDutyTaskRequest(val skipReason: String) : CommunityRequestBody {
    init { requireText(skipReason, "skip_reason", 240) }
    override fun toJson(): JsonValue.Obj = jsonObject(linkedMapOf("skip_reason" to JsonValue.Str(skipReason)))
}

data class RequestDutySwapRequest(
    val dutyTaskId: String,
    val type: String,
    val targetMemberId: String,
    val reason: String? = null,
) : CommunityRequestBody {
    init {
        requireUuid(dutyTaskId, "duty_task_id")
        requireWireEnum(type, setOf("SWAP", "COVER"), "type")
        requireUuid(targetMemberId, "target_member_id")
        requireOptionalText(reason, "reason", 240, allowBlank = true)
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["duty_task_id"] = JsonValue.Str(dutyTaskId)
        fields["type"] = JsonValue.Str(type)
        fields["target_member_id"] = JsonValue.Str(targetMemberId)
        fields.putString("reason", reason)
        return jsonObject(fields)
    }
}

data class ResolveDutySwapRequest(
    val action: String,
    val resolutionNote: String? = null,
) : CommunityRequestBody {
    init {
        requireWireEnum(action, setOf("ACCEPT", "REJECT", "CANCEL"), "action")
        requireOptionalText(resolutionNote, "resolution_note", 240, allowBlank = true)
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["action"] = JsonValue.Str(action)
        fields.putString("resolution_note", resolutionNote)
        return jsonObject(fields)
    }
}

data class CreateRepairTicketRequest(
    val title: String,
    val category: String,
    val locationText: String,
    val description: String,
) : CommunityRequestBody {
    init {
        requireText(title, "title", 120)
        requireWireEnum(category, setOf("PLUMBING", "ELECTRICAL", "FURNITURE", "NETWORK", "CLEANING", "SAFETY", "OTHER"), "category")
        requireText(locationText, "location_text", 120)
        requireText(description, "description", 2000)
    }

    override fun toJson(): JsonValue.Obj = jsonObject(
        linkedMapOf(
            "title" to JsonValue.Str(title),
            "category" to JsonValue.Str(category),
            "location_text" to JsonValue.Str(locationText),
            "description" to JsonValue.Str(description),
        ),
    )
}

data class UpdateRepairTicketRequest(
    val title: String? = null,
    val category: String? = null,
    val locationText: String? = null,
    val description: String? = null,
) : CommunityRequestBody {
    init {
        requireOptionalText(title, "title", 120)
        category?.let { requireWireEnum(it, setOf("PLUMBING", "ELECTRICAL", "FURNITURE", "NETWORK", "CLEANING", "SAFETY", "OTHER"), "category") }
        requireOptionalText(locationText, "location_text", 120)
        requireOptionalText(description, "description", 2000)
        require(title != null || category != null || locationText != null || description != null) {
            "UpdateRepairTicketRequest 至少要更新一个字段"
        }
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields.putString("title", title)
        fields.putString("category", category)
        fields.putString("location_text", locationText)
        fields.putString("description", description)
        return jsonObject(fields)
    }
}

data class TransitionRepairStatusRequest(
    val toStatus: String,
    val note: String? = null,
    val assignedMemberId: String? = null,
) : CommunityRequestBody {
    init {
        requireWireEnum(toStatus, setOf("OPEN", "TRIAGED", "IN_PROGRESS", "RESOLVED", "REJECTED", "CLOSED"), "to_status")
        requireOptionalText(note, "note", 500, allowBlank = true)
        assignedMemberId?.let { requireUuid(it, "assigned_member_id") }
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["to_status"] = JsonValue.Str(toStatus)
        fields.putString("note", note)
        fields.putString("assigned_member_id", assignedMemberId)
        return jsonObject(fields)
    }
}

data class SubmitRepairReviewRequest(
    val rating: Int,
    val comment: String? = null,
) : CommunityRequestBody {
    init {
        require(rating in 1..5) { "rating 必须在 1..5" }
        requireOptionalText(comment, "comment", 500, allowBlank = true)
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["rating"] = JsonValue.Num(rating.toString())
        fields.putString("comment", comment)
        return jsonObject(fields)
    }
}

data class CreatePlaceStatusReportRequest(
    val metric: String,
    val state: String,
    val observedAt: String,
    val reportKind: String,
    val correctsReportId: String? = null,
    val note: String? = null,
) : CommunityRequestBody {
    init {
        requireWireEnum(metric, setOf("ACCESS", "OPERATION", "OCCUPANCY", "SAFETY"), "metric")
        val allowedStates = when (metric) {
            "ACCESS" -> setOf("OPEN", "CLOSED", "RESTRICTED")
            "OPERATION" -> setOf("WORKING", "DEGRADED", "OUT_OF_SERVICE")
            "OCCUPANCY" -> setOf("QUIET", "MODERATE", "CROWDED", "FULL")
            else -> setOf("SAFE", "HAZARD")
        }
        requireWireEnum(state, allowedStates, "state")
        requireIsoDateTime(observedAt, "observed_at")
        requireWireEnum(reportKind, setOf("OBSERVATION", "CORRECTION"), "report_kind")
        if (reportKind == "CORRECTION") {
            require(!correctsReportId.isNullOrBlank()) { "CORRECTION 必须提供 corrects_report_id" }
            correctsReportId?.let { requireUuid(it, "corrects_report_id") }
        } else {
            require(correctsReportId == null) { "OBSERVATION 不允许 corrects_report_id" }
        }
        requireOptionalText(note, "note", 280, allowBlank = true)
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["metric"] = JsonValue.Str(metric)
        fields["state"] = JsonValue.Str(state)
        fields["observed_at"] = JsonValue.Str(observedAt)
        fields["report_kind"] = JsonValue.Str(reportKind)
        fields.putString("corrects_report_id", correctsReportId)
        fields.putString("note", note)
        return jsonObject(fields)
    }
}

data class PlaceGeoBoundingBoxInput(
    val minLatitude: Double,
    val minLongitude: Double,
    val maxLatitude: Double,
    val maxLongitude: Double,
) {
    init {
        require(minLatitude in -90.0..90.0 && maxLatitude in -90.0..90.0) { "纬度必须在 -90..90" }
        require(minLongitude in -180.0..180.0 && maxLongitude in -180.0..180.0) { "经度必须在 -180..180" }
        require(minLatitude < maxLatitude && minLongitude < maxLongitude) { "bbox 下限必须小于上限" }
    }

    fun toJson(): JsonValue.Obj = jsonObject(
        linkedMapOf(
            "min_latitude" to JsonValue.Num(minLatitude.toString()),
            "min_longitude" to JsonValue.Num(minLongitude.toString()),
            "max_latitude" to JsonValue.Num(maxLatitude.toString()),
            "max_longitude" to JsonValue.Num(maxLongitude.toString()),
        ),
    )
}

data class PlaceStatusSubscriptionTargetInput(
    val type: String,
    val placeRef: String? = null,
    val bbox: PlaceGeoBoundingBoxInput? = null,
    val placeTypes: List<String> = emptyList(),
) {
    init {
        requireWireEnum(type, setOf("PLACE", "AREA"), "target.type")
        if (type == "PLACE") {
            require(!placeRef.isNullOrBlank()) { "PLACE target 必须提供 place_ref" }
            placeRef?.let { requirePlaceRef(it, "target.place_ref") }
            require(bbox == null) { "PLACE target 不允许 bbox" }
        } else {
            requireNotNull(bbox) { "AREA target 必须提供 bbox" }
            require(placeRef == null) { "AREA target 不允许 place_ref" }
        }
        val allowedTypes = setOf("campus", "building", "floor", "room", "facility", "entrance", "indoor_node")
        require(placeTypes.distinct().size == placeTypes.size && placeTypes.all { it in allowedTypes }) {
            "place_types 必须是唯一契约地点类型"
        }
    }

    fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["type"] = JsonValue.Str(type)
        fields.putString("place_ref", placeRef)
        bbox?.let { fields["bbox"] = it.toJson() }
        if (placeTypes.isNotEmpty()) fields["place_types"] = jsonStringArray(placeTypes)
        return jsonObject(fields)
    }
}

data class CreatePlaceStatusSubscriptionRequest(
    val schoolId: String,
    val target: PlaceStatusSubscriptionTargetInput,
    val metrics: List<String>,
) : CommunityRequestBody {
    init {
        requireText(schoolId, "school_id")
        val allowed = setOf("ACCESS", "OPERATION", "OCCUPANCY", "SAFETY")
        require(metrics.isNotEmpty() && metrics.distinct().size == metrics.size && metrics.all { it in allowed }) {
            "metrics 必须是唯一契约 metric，且至少一项"
        }
    }

    override fun toJson(): JsonValue.Obj = jsonObject(
        linkedMapOf(
            "school_id" to JsonValue.Str(schoolId),
            "target" to target.toJson(),
            "metrics" to jsonStringArray(metrics),
        ),
    )
}

data class CreatePlaceReportFlagRequest(
    val reason: String,
    val detail: String? = null,
) : CommunityRequestBody {
    init {
        requireWireEnum(reason, setOf("INCORRECT", "STALE", "ABUSE", "PII", "SPAM", "OTHER"), "reason")
        requireOptionalText(detail, "detail", 500, allowBlank = true)
    }

    override fun toJson(): JsonValue.Obj {
        val fields = jsonFields()
        fields["reason"] = JsonValue.Str(reason)
        fields.putString("detail", detail)
        return jsonObject(fields)
    }
}

data class ReviewPlaceReportFlagRequest(
    val action: String,
    val reviewNote: String,
) : CommunityRequestBody {
    init {
        requireWireEnum(action, setOf("KEEP_VISIBLE", "EXCLUDE_INCORRECT", "EXCLUDE_ABUSE", "REJECT_FLAG"), "action")
        requireText(reviewNote, "review_note", 500)
    }

    override fun toJson(): JsonValue.Obj = jsonObject(
        linkedMapOf(
            "action" to JsonValue.Str(action),
            "review_note" to JsonValue.Str(reviewNote),
        ),
    )
}
