package cn.edu.haut.campus.feature.dorm.data

import cn.edu.haut.campus.core.community.AmendLedgerEntryRequest
import cn.edu.haut.campus.core.community.CancelSettlementRequest
import cn.edu.haut.campus.core.community.CommunityClient
import cn.edu.haut.campus.core.community.CommunityEnvelope
import cn.edu.haut.campus.core.community.CommunityError
import cn.edu.haut.campus.core.community.CommunityErrorKind
import cn.edu.haut.campus.core.community.CommunityResult
import cn.edu.haut.campus.core.community.CompleteDutyTaskRequest
import cn.edu.haut.campus.core.community.ConfirmDutyTaskRequest
import cn.edu.haut.campus.core.community.ConfirmSettlementRequest
import cn.edu.haut.campus.core.community.CreateDutyRotaRequest
import cn.edu.haut.campus.core.community.CreateGroupRequest
import cn.edu.haut.campus.core.community.CreateLedgerEntryRequest
import cn.edu.haut.campus.core.community.CreateRepairTicketRequest
import cn.edu.haut.campus.core.community.CreateSettlementRequest
import cn.edu.haut.campus.core.community.RequestDutySwapRequest
import cn.edu.haut.campus.core.community.ResolveDutySwapRequest
import cn.edu.haut.campus.core.community.SkipDutyTaskRequest
import cn.edu.haut.campus.core.community.SubmitRepairReviewRequest
import cn.edu.haut.campus.core.community.TransitionRepairStatusRequest
import cn.edu.haut.campus.core.community.amendLedgerEntry
import cn.edu.haut.campus.core.community.cancelSettlement
import cn.edu.haut.campus.core.community.completeDutyTask
import cn.edu.haut.campus.core.community.confirmDutyTaskCompletion
import cn.edu.haut.campus.core.community.confirmSettlement
import cn.edu.haut.campus.core.community.createDutyRota
import cn.edu.haut.campus.core.community.createGroup
import cn.edu.haut.campus.core.community.createLedgerEntry
import cn.edu.haut.campus.core.community.createRepairTicket
import cn.edu.haut.campus.core.community.createSettlement
import cn.edu.haut.campus.core.community.getDutyRota
import cn.edu.haut.campus.core.community.getLedgerBalances
import cn.edu.haut.campus.core.community.getRepairTicket
import cn.edu.haut.campus.core.community.getSettlement
import cn.edu.haut.campus.core.community.listDutyRotas
import cn.edu.haut.campus.core.community.listDutySwaps
import cn.edu.haut.campus.core.community.listDutyTasks
import cn.edu.haut.campus.core.community.listGroupMembers
import cn.edu.haut.campus.core.community.listGroups
import cn.edu.haut.campus.core.community.listLedgerEntries
import cn.edu.haut.campus.core.community.listRepairTickets
import cn.edu.haut.campus.core.community.listSettlements
import cn.edu.haut.campus.core.community.requestDutySwap
import cn.edu.haut.campus.core.community.resolveDutySwap
import cn.edu.haut.campus.core.community.skipDutyTask
import cn.edu.haut.campus.core.community.submitRepairReview
import cn.edu.haut.campus.core.community.transitionRepairStatus
import cn.edu.haut.campus.core.model.Staleness
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.feature.dorm.DormCollaborationException
import cn.edu.haut.campus.feature.dorm.DormCollaborationPort
import cn.edu.haut.campus.feature.dorm.DormDutyRota
import cn.edu.haut.campus.feature.dorm.DormDutyRotaInput
import cn.edu.haut.campus.feature.dorm.DormDutySwap
import cn.edu.haut.campus.feature.dorm.DormDutyTask
import cn.edu.haut.campus.feature.dorm.DormErrorKind
import cn.edu.haut.campus.feature.dorm.DormGroupSummary
import cn.edu.haut.campus.feature.dorm.DormLedgerBalances
import cn.edu.haut.campus.feature.dorm.DormLedgerEntry
import cn.edu.haut.campus.feature.dorm.DormLedgerEntryInput
import cn.edu.haut.campus.feature.dorm.DormLedgerEntryKind
import cn.edu.haut.campus.feature.dorm.DormMember
import cn.edu.haut.campus.feature.dorm.DormMemberBalance
import cn.edu.haut.campus.feature.dorm.DormRepairInput
import cn.edu.haut.campus.feature.dorm.DormRepairTicket
import cn.edu.haut.campus.feature.dorm.DormSettlement
import cn.edu.haut.campus.feature.dorm.DormSettlementInput
import cn.edu.haut.campus.feature.dorm.DormSnapshot
import cn.edu.haut.campus.feature.dorm.DormSplitRule
import cn.edu.haut.campus.feature.dorm.DormSplitRuleType
import cn.edu.haut.campus.feature.dorm.DormSplitShare
import cn.edu.haut.campus.feature.dorm.calculateDormShares
import cn.edu.haut.campus.feature.dorm.dormDutyTaskStatusFromWire
import cn.edu.haut.campus.feature.dorm.dormSettlementStatusFromWire
import cn.edu.haut.campus.feature.dorm.toCommunityInput
import cn.edu.haut.campus.feature.dorm.validateDormLedgerEntryInvariants
import cn.edu.haut.campus.feature.dorm.validateDormSettlementInvariants
import cn.edu.haut.campus.feature.dorm.validateSharesMatchCoreLedger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** W4-05 envelope 客户端到 W4-08 宿舍领域模型的契约映射层；不新增传输、缓存或支付语义。 */
class CommunityDormCollaborationAdapter(
    private val client: CommunityClient,
) : DormCollaborationPort {

    override suspend fun loadSnapshot(schoolId: String, preferredGroupId: String?): DormSnapshot = coroutineScope {
        val groupsResult = client.listGroups(schoolId = schoolId, type = "dorm", limit = 100).unwrap("listGroups")
        val groups = requireArray(groupsResult.value.data, "data").map { parseDormGroup(it.requireObj("group")) }
        if (groups.isEmpty()) {
            return@coroutineScope DormSnapshot(
                groups = emptyList(),
                selectedGroupId = null,
                staleness = groupsResult.staleness,
                cachedAtEpochMillis = groupsResult.cachedAtEpochMillis,
            )
        }
        val selected = groups.firstOrNull { it.groupId == preferredGroupId } ?: groups.first()
        val membersDeferred = async { client.listGroupMembers(selected.groupId, limit = 100).unwrap("listGroupMembers") }
        val entriesDeferred = async { client.listLedgerEntries(selected.groupId, limit = 100).unwrap("listLedgerEntries") }
        val balancesDeferred = async { client.getLedgerBalances(selected.groupId).unwrap("getLedgerBalances") }
        val settlementsDeferred = async { client.listSettlements(selected.groupId, limit = 100).unwrap("listSettlements") }
        val rotasDeferred = async { client.listDutyRotas(selected.groupId, limit = 100).unwrap("listDutyRotas") }
        val tasksDeferred = async { client.listDutyTasks(selected.groupId, limit = 100).unwrap("listDutyTasks") }
        val repairsDeferred = async { client.listRepairTickets(selected.groupId, limit = 100).unwrap("listRepairTickets") }

        val membersResult = membersDeferred.await()
        val entriesResult = entriesDeferred.await()
        val balancesResult = balancesDeferred.await()
        val settlementsResult = settlementsDeferred.await()
        val rotasResult = rotasDeferred.await()
        val tasksResult = tasksDeferred.await()
        val repairsResult = repairsDeferred.await()
        val partialWarnings = mutableListOf<String>()
        fun warnIfTruncated(result: Unwrapped, label: String) {
            if (result.hasNextCursor()) partialWarnings += label + "超过首 100 条，当前只展示第一页"
        }
        warnIfTruncated(groupsResult, "宿舍群组")
        warnIfTruncated(membersResult, "成员")
        warnIfTruncated(entriesResult, "账本历史")
        warnIfTruncated(settlementsResult, "结算记录")
        warnIfTruncated(rotasResult, "值日轮班")
        warnIfTruncated(tasksResult, "值日任务")
        warnIfTruncated(repairsResult, "报修记录")

        val members = requireArray(membersResult.value.data, "data").map { parseDormMember(it.requireObj("member")) }
        val entries = requireArray(entriesResult.value.data, "data").map {
            parseDormLedgerEntry(it.requireObj("ledger entry")).also { entry -> validateCloudLedgerEntryInvariants(entry) }
        }
        val balances = parseDormLedgerBalances(balancesResult.value.data.requireObj("ledger balances"))
        val settlements = requireArray(settlementsResult.value.data, "data").map { parseDormSettlement(it.requireObj("settlement")) }
        val rotas = requireArray(rotasResult.value.data, "data").map { parseDormDutyRota(it.requireObj("rota")) }
        val dutyTasks = requireArray(tasksResult.value.data, "data").map { parseDormDutyTask(it.requireObj("duty task")) }
        val repairs = requireArray(repairsResult.value.data, "data").map { parseDormRepairTicket(it.requireObj("repair")) }
        val swaps = rotas.flatMap { rota ->
            val result = client.listDutySwaps(rota.rotaId, limit = 100).unwrap("listDutySwaps")
            warnIfTruncated(result, "换班请求")
            requireArray(result.value.data, "data").map { parseDormDutySwap(it.requireObj("duty swap")) }
        }
        validateBalanceInvariant(balances)
        DormSnapshot(
            groups = groups,
            selectedGroupId = selected.groupId,
            members = members,
            ledgerEntries = entries.sortedByDescending { it.createdAt },
            balances = balances,
            settlements = settlements.sortedByDescending { it.createdAt },
            rotas = rotas,
            dutyTasks = dutyTasks.sortedBy { it.dueDate },
            dutySwaps = swaps,
            repairs = repairs.sortedByDescending { it.updatedAt },
            staleness = groupsResult.staleness,
            cachedAtEpochMillis = groupsResult.cachedAtEpochMillis,
            partialWarnings = partialWarnings.distinct(),
        )
    }

    override suspend fun createDormGroup(
        schoolId: String,
        name: String,
        description: String?,
        idempotencyKey: String,
    ): DormGroupSummary {
        val result = client.createGroup(
            request = CreateGroupRequest(schoolId = schoolId, type = "dorm", name = name, description = description),
            idempotencyKey = idempotencyKey,
        ).unwrap("createGroup")
        return parseDormGroup(result.value.data.requireObj("group"))
    }

    override suspend fun createLedgerEntry(
        groupId: String,
        input: DormLedgerEntryInput,
        idempotencyKey: String,
    ): DormLedgerEntry {
        val activeMemberIds = loadActiveMemberIds(groupId)
        val expectedShares = calculateDormShares(input.amountFen, input.splitRule, activeMemberIds)
        val result = client.createLedgerEntry(
            groupId = groupId,
            request = CreateLedgerEntryRequest(
                title = input.title,
                amountFen = input.amountFen,
                paidByMemberId = input.paidByMemberId,
                occurredAt = input.occurredAt,
                splitRule = input.splitRule.toCommunityInput(),
                entryKind = input.entryKind.name,
                note = input.note,
            ),
            idempotencyKey = idempotencyKey,
        ).unwrap("createLedgerEntry")
        val entry = parseDormLedgerEntry(result.value.data.requireObj("ledger entry"))
        validateCloudLedgerEntryInvariants(entry)
        validateCloudSharesMatchCoreLedger(input.amountFen, input.splitRule, activeMemberIds, entry.shares)
        if (entry.shares != expectedShares) {
            throw DormCollaborationException(DormErrorKind.CONTRACT, "云端分摊结果与本地预览不一致", retryable = false)
        }
        return entry
    }

    override suspend fun amendLedgerEntry(
        groupId: String,
        entryId: String,
        reason: String,
        input: DormLedgerEntryInput,
        idempotencyKey: String,
    ): DormLedgerEntry {
        val activeMemberIds = loadActiveMemberIds(groupId)
        val result = client.amendLedgerEntry(
            groupId = groupId,
            entryId = entryId,
            request = AmendLedgerEntryRequest(
                reason = reason,
                amountFen = input.amountFen,
                paidByMemberId = input.paidByMemberId,
                occurredAt = input.occurredAt,
                splitRule = input.splitRule.toCommunityInput(),
                title = input.title,
            ),
            idempotencyKey = idempotencyKey,
        ).unwrap("amendLedgerEntry")
        val entry = parseDormLedgerEntry(result.value.data.requireObj("ledger correction"))
        validateCloudLedgerEntryInvariants(entry)
        validateCloudSharesMatchCoreLedger(input.amountFen, input.splitRule, activeMemberIds, entry.shares)
        if (entry.kind != DormLedgerEntryKind.CORRECTION || entry.correctsEntryId != entryId) {
            throw DormCollaborationException(DormErrorKind.CONTRACT, "云端更正记录缺少正确的 corrects_entry_id 链", retryable = false)
        }
        return entry
    }

    override suspend fun proposeSettlement(
        groupId: String,
        input: DormSettlementInput,
        idempotencyKey: String,
    ): DormSettlement {
        val result = client.createSettlement(
            groupId = groupId,
            request = CreateSettlementRequest(
                debtorMemberId = input.debtorMemberId,
                creditorMemberId = input.creditorMemberId,
                amountFen = input.amountFen,
                basisBalanceRevision = input.basisBalanceRevision,
                note = input.note,
            ),
            idempotencyKey = idempotencyKey,
        ).unwrap("createSettlement")
        return parseDormSettlement(result.value.data.requireObj("settlement"))
    }

    override suspend fun confirmSettlement(
        settlementId: String,
        basisBalanceRevision: Long,
        idempotencyKey: String,
    ): DormSettlement {
        val result = client.confirmSettlement(
            settlementId = settlementId,
            request = ConfirmSettlementRequest(basisBalanceRevision),
            idempotencyKey = idempotencyKey,
        ).unwrap("confirmSettlement")
        return parseDormSettlement(result.value.data.requireObj("settlement"))
    }

    override suspend fun cancelSettlement(settlementId: String, reason: String?, idempotencyKey: String): DormSettlement {
        val result = client.cancelSettlement(
            settlementId = settlementId,
            request = CancelSettlementRequest(reason),
            idempotencyKey = idempotencyKey,
        ).unwrap("cancelSettlement")
        return parseDormSettlement(result.value.data.requireObj("settlement"))
    }

    override suspend fun createDutyRota(groupId: String, input: DormDutyRotaInput, idempotencyKey: String): DormDutyRota {
        val result = client.createDutyRota(
            groupId = groupId,
            request = CreateDutyRotaRequest(
                title = input.title,
                rotationMemberIds = input.rotationMemberIds,
                frequency = input.frequency,
                startDate = input.startDate,
                timeZone = input.timeZone,
                description = input.description,
                daysOfWeek = input.daysOfWeek,
                endDate = input.endDate,
                generateWeeksAhead = input.generateWeeksAhead,
            ),
            idempotencyKey = idempotencyKey,
        ).unwrap("createDutyRota")
        return parseDormDutyRota(result.value.data.requireObj("rota"))
    }

    override suspend fun completeDutyTask(taskId: String, note: String?, idempotencyKey: String): DormDutyTask {
        val result = client.completeDutyTask(taskId, CompleteDutyTaskRequest(note), idempotencyKey).unwrap("completeDutyTask")
        return parseDormDutyTask(result.value.data.requireObj("duty task"))
    }

    override suspend fun confirmDutyTask(taskId: String, note: String?, idempotencyKey: String): DormDutyTask {
        val result = client.confirmDutyTaskCompletion(taskId, ConfirmDutyTaskRequest(note), idempotencyKey).unwrap("confirmDutyTaskCompletion")
        return parseDormDutyTask(result.value.data.requireObj("duty task"))
    }

    override suspend fun skipDutyTask(taskId: String, reason: String, idempotencyKey: String): DormDutyTask {
        val result = client.skipDutyTask(taskId, SkipDutyTaskRequest(reason), idempotencyKey).unwrap("skipDutyTask")
        return parseDormDutyTask(result.value.data.requireObj("duty task"))
    }

    override suspend fun requestDutySwap(
        rotaId: String,
        taskId: String,
        type: String,
        targetMemberId: String,
        reason: String?,
        idempotencyKey: String,
    ): DormDutySwap {
        val result = client.requestDutySwap(
            rotaId = rotaId,
            request = RequestDutySwapRequest(dutyTaskId = taskId, type = type, targetMemberId = targetMemberId, reason = reason),
            idempotencyKey = idempotencyKey,
        ).unwrap("requestDutySwap")
        return parseDormDutySwap(result.value.data.requireObj("duty swap"))
    }

    override suspend fun resolveDutySwap(swapId: String, action: String, note: String?, idempotencyKey: String): DormDutySwap {
        val result = client.resolveDutySwap(swapId, ResolveDutySwapRequest(action, note), idempotencyKey).unwrap("resolveDutySwap")
        return parseDormDutySwap(result.value.data.requireObj("duty swap"))
    }

    override suspend fun createRepairTicket(groupId: String, input: DormRepairInput, idempotencyKey: String): DormRepairTicket {
        val result = client.createRepairTicket(
            groupId = groupId,
            request = CreateRepairTicketRequest(input.title, input.category, input.locationText, input.description),
            idempotencyKey = idempotencyKey,
        ).unwrap("createRepairTicket")
        return parseDormRepairTicket(result.value.data.requireObj("repair"))
    }

    override suspend fun transitionRepairStatus(
        repairId: String,
        toStatus: String,
        note: String?,
        assignedMemberId: String?,
        idempotencyKey: String,
    ): DormRepairTicket {
        val result = client.transitionRepairStatus(
            repairId = repairId,
            request = TransitionRepairStatusRequest(toStatus = toStatus, note = note, assignedMemberId = assignedMemberId),
            idempotencyKey = idempotencyKey,
        ).unwrap("transitionRepairStatus")
        return parseDormRepairTicket(result.value.data.requireObj("repair"))
    }

    override suspend fun submitRepairReview(repairId: String, rating: Int, comment: String?, idempotencyKey: String) {
        client.submitRepairReview(repairId, SubmitRepairReviewRequest(rating, comment), idempotencyKey).unwrap("submitRepairReview")
    }

    private suspend fun loadActiveMemberIds(groupId: String): List<String> {
        val result = client.listGroupMembers(groupId, limit = 100).unwrap("listGroupMembers")
        return requireArray(result.value.data, "data")
            .map { parseDormMember(it.requireObj("member")) }
            .filter { it.isActive }
            .map { it.memberId }
    }

    private data class Unwrapped(
        val value: CommunityEnvelope,
        val staleness: Staleness,
        val cachedAtEpochMillis: Long?,
    ) {
        fun hasNextCursor(): Boolean = value.meta.fields["next_cursor"] is JsonValue.Str
    }

    private fun CommunityResult<CommunityEnvelope>.unwrap(operation: String): Unwrapped = when (this) {
        is CommunityResult.Success -> Unwrapped(value, staleness, cachedAtEpochMillis)
        CommunityResult.AuthenticationRequired -> throw DormCollaborationException(
            DormErrorKind.AUTHENTICATION_REQUIRED,
            "需要登录 XPeach 校园云后才能使用宿舍协作",
            retryable = false,
            userAction = "请先登录云端账号",
        )
        is CommunityResult.Disabled -> throw DormCollaborationException(
            DormErrorKind.FEATURE_DISABLED,
            reason,
            retryable = false,
        )
        is CommunityResult.Failure -> throw error.toDormException(operation)
    }

    private fun CommunityError.toDormException(operation: String): DormCollaborationException {
        val unsupported = statusCode == 501 || code == "FEATURE_NOT_ENABLED" || statusCode == 404
        val kind = when {
            unsupported -> DormErrorKind.UNSUPPORTED
            this.kind == CommunityErrorKind.NETWORK -> DormErrorKind.NETWORK
            this.kind == CommunityErrorKind.CONTRACT -> DormErrorKind.CONTRACT
            else -> DormErrorKind.SERVER
        }
        return DormCollaborationException(
            kind = kind,
            message = operation + "：" + message,
            retryable = retryable,
            userAction = userAction,
        )
    }
}

private fun validateCloudLedgerEntryInvariants(entry: DormLedgerEntry) {
    try {
        validateDormLedgerEntryInvariants(entry)
    } catch (error: IllegalArgumentException) {
        throw DormCollaborationException(
            DormErrorKind.CONTRACT,
            "云端账本记录违反整数分/分摊守恒契约：" + error.message,
            retryable = false,
            cause = error,
        )
    }
}

private fun validateCloudSharesMatchCoreLedger(
    amountFen: Long,
    rule: DormSplitRule,
    activeMemberIds: Collection<String>,
    expectedShares: List<DormSplitShare>,
) {
    try {
        validateSharesMatchCoreLedger(amountFen, rule, activeMemberIds, expectedShares)
    } catch (error: IllegalArgumentException) {
        throw DormCollaborationException(
            DormErrorKind.CONTRACT,
            "云端分摊结果与 core:ledger largest-remainder 语义不一致",
            retryable = false,
            cause = error,
        )
    }
}

private fun validateBalanceInvariant(balances: DormLedgerBalances) {
    val sum = balances.members.fold(0L) { acc, member -> Math.addExact(acc, member.balanceFen) }
    if (sum != 0L || balances.zeroSumCheckFen != 0L) {
        throw DormCollaborationException(DormErrorKind.CONTRACT, "云端账本余额 zero-sum 校验失败", retryable = false)
    }
}

private fun JsonValue?.requireObj(label: String) = this as? JsonValue.Obj
    ?: throw DormCollaborationException(DormErrorKind.CONTRACT, "契约响应缺少对象：$label", retryable = false)

private fun JsonValue.Obj.required(key: String): JsonValue = fields[key]
    ?: throw DormCollaborationException(DormErrorKind.CONTRACT, "契约响应缺少字段：$key", retryable = false)

private fun JsonValue.Obj.string(key: String): String = required(key).stringOrNull()
    ?: throw DormCollaborationException(DormErrorKind.CONTRACT, "契约字段不是字符串：$key", retryable = false)

private fun JsonValue.Obj.nullableString(key: String): String? {
    val value = required(key)
    if (value == JsonValue.Null) return null
    return value.stringOrNull() ?: throw DormCollaborationException(DormErrorKind.CONTRACT, "契约字段不是可空字符串：$key", retryable = false)
}

/** OpenAPI 中未列入 required 的可空字段：缺失与 JSON null 都按 null 处理；存在但类型错误仍 fail-closed。 */
private fun JsonValue.Obj.optionalNullableString(key: String): String? {
    val value = fields[key] ?: return null
    if (value == JsonValue.Null) return null
    return value.stringOrNull() ?: throw DormCollaborationException(DormErrorKind.CONTRACT, "契约可选字段不是字符串：$key", retryable = false)
}

private fun JsonValue.Obj.long(key: String): Long {
    val literal = (required(key) as? JsonValue.Num)?.literal
        ?: throw DormCollaborationException(DormErrorKind.CONTRACT, "契约字段不是整数：$key", retryable = false)
    return literal.toLongOrNull()
        ?: throw DormCollaborationException(DormErrorKind.CONTRACT, "契约整数超出 int64：$key", retryable = false)
}

private fun JsonValue.Obj.int(key: String): Int {
    val value = long(key)
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
        throw DormCollaborationException(DormErrorKind.CONTRACT, "契约整数超出 int32：$key", retryable = false)
    }
    return value.toInt()
}

private fun JsonValue.Obj.array(key: String): List<JsonValue> = requireArray(required(key), key)

private fun requireArray(value: JsonValue?, label: String): List<JsonValue> = value?.asArrOrNull?.items
    ?: throw DormCollaborationException(DormErrorKind.CONTRACT, "契约字段不是数组：$label", retryable = false)

private fun requireWireValue(value: String, allowed: Set<String>, label: String): String {
    if (value !in allowed) {
        throw DormCollaborationException(DormErrorKind.CONTRACT, "未知 " + label + ": " + value, retryable = false)
    }
    return value
}

private fun parseDormGroup(obj: JsonValue.Obj): DormGroupSummary = DormGroupSummary(
    groupId = obj.string("group_id"),
    schoolId = obj.string("school_id"),
    name = obj.string("name"),
    description = obj.optionalNullableString("description"),
    memberCount = obj.int("member_count"),
    myRole = obj.string("my_role"),
)

private fun parseDormMember(obj: JsonValue.Obj): DormMember = DormMember(
    memberId = obj.string("member_id"),
    cloudUserId = obj.string("cloud_user_id"),
    displayName = obj.nullableString("display_name"),
    role = obj.string("role"),
    status = obj.string("status"),
)

private fun parseSplitRule(obj: JsonValue.Obj): DormSplitRule {
    val type = when (obj.string("type")) {
        "EQUAL" -> DormSplitRuleType.EQUAL
        "SELECTED" -> DormSplitRuleType.SELECTED
        "RATIOS" -> DormSplitRuleType.RATIOS
        "STAY_DAYS" -> DormSplitRuleType.STAY_DAYS
        else -> throw DormCollaborationException(DormErrorKind.CONTRACT, "未知 split_rule.type", retryable = false)
    }
    val participants = obj.fields["participant_member_ids"]?.asArrOrNull?.items?.map {
        it.stringOrNull() ?: throw DormCollaborationException(DormErrorKind.CONTRACT, "participant_member_ids 含非字符串", retryable = false)
    } ?: emptyList()
    val ratios = obj.fields["ratio_weights"]?.asArrOrNull?.items?.associate {
        val row = it.requireObj("ratio weight")
        row.string("member_id") to row.long("weight")
    } ?: emptyMap()
    val stayDays = obj.fields["stay_days"]?.asArrOrNull?.items?.associate {
        val row = it.requireObj("stay days")
        row.string("member_id") to row.long("days")
    } ?: emptyMap()
    return try {
        DormSplitRule(type, participants, ratios, stayDays)
    } catch (error: IllegalArgumentException) {
        throw DormCollaborationException(DormErrorKind.CONTRACT, "split_rule 违反契约: " + error.message, retryable = false, cause = error)
    }
}

private fun parseDormLedgerEntry(obj: JsonValue.Obj): DormLedgerEntry = DormLedgerEntry(
    entryId = obj.string("entry_id"),
    groupId = obj.string("group_id"),
    schoolId = obj.string("school_id"),
    kind = when (obj.string("entry_kind")) {
        "EXPENSE" -> DormLedgerEntryKind.EXPENSE
        "REFUND" -> DormLedgerEntryKind.REFUND
        "CORRECTION" -> DormLedgerEntryKind.CORRECTION
        else -> throw DormCollaborationException(DormErrorKind.CONTRACT, "未知 entry_kind", retryable = false)
    },
    correctsEntryId = obj.nullableString("corrects_entry_id"),
    correctionReason = obj.nullableString("correction_reason"),
    title = obj.string("title"),
    note = obj.nullableString("note"),
    amountFen = obj.long("amount_fen"),
    paidByMemberId = obj.string("paid_by_member_id"),
    occurredAt = obj.string("occurred_at"),
    splitRule = parseSplitRule(obj.required("split_rule").requireObj("split_rule")),
    shares = obj.array("shares").map {
        val row = it.requireObj("split share")
        DormSplitShare(row.string("participant_member_id"), row.long("amount_fen"))
    },
    createdByMemberId = obj.string("created_by_member_id"),
    createdAt = obj.string("created_at"),
)

private fun parseDormLedgerBalances(obj: JsonValue.Obj): DormLedgerBalances = DormLedgerBalances(
    groupId = obj.string("group_id"),
    balanceRevision = obj.long("balance_revision"),
    generatedAt = obj.string("generated_at"),
    zeroSumCheckFen = obj.long("zero_sum_check_fen"),
    members = obj.array("members").map {
        val row = it.requireObj("member balance")
        DormMemberBalance(
            memberId = row.string("member_id"),
            postingCreditFen = row.long("posting_credit_fen"),
            shareCostFen = row.long("share_cost_fen"),
            outboundSettlementFen = row.long("outbound_settlement_fen"),
            inboundSettlementFen = row.long("inbound_settlement_fen"),
            balanceFen = row.long("balance_fen"),
        )
    },
)

private fun parseDormSettlement(obj: JsonValue.Obj): DormSettlement = try {
    DormSettlement(
        settlementId = obj.string("settlement_id"),
        debtorMemberId = obj.string("debtor_member_id"),
        creditorMemberId = obj.string("creditor_member_id"),
        amountFen = obj.long("amount_fen"),
        basisBalanceRevision = obj.long("basis_balance_revision"),
        status = dormSettlementStatusFromWire(obj.string("status")),
        note = obj.nullableString("note"),
        createdAt = obj.string("created_at"),
        debtorConfirmedAt = obj.nullableString("debtor_confirmed_at"),
        creditorConfirmedAt = obj.nullableString("creditor_confirmed_at"),
        confirmedAt = obj.nullableString("confirmed_at"),
        cancelledAt = obj.nullableString("cancelled_at"),
    ).also(::validateDormSettlementInvariants)
} catch (error: IllegalArgumentException) {
    throw DormCollaborationException(DormErrorKind.CONTRACT, "结算记录违反契约: " + error.message, retryable = false, cause = error)
}

private fun parseDormDutyRota(obj: JsonValue.Obj): DormDutyRota = DormDutyRota(
    rotaId = obj.string("rota_id"),
    title = obj.string("title"),
    description = obj.nullableString("description"),
    rotationMemberIds = obj.array("rotation_member_ids").map { it.stringOrNull() ?: throw DormCollaborationException(DormErrorKind.CONTRACT, "rotation_member_ids 含非字符串", retryable = false) },
    frequency = requireWireValue(obj.string("frequency"), setOf("DAILY", "WEEKLY"), "值日 frequency"),
    daysOfWeek = obj.array("days_of_week").map { (it as? JsonValue.Num)?.literal?.toIntOrNull() ?: throw DormCollaborationException(DormErrorKind.CONTRACT, "days_of_week 含非整数", retryable = false) },
    startDate = obj.string("start_date"),
    endDate = obj.nullableString("end_date"),
    timeZone = obj.string("time_zone"),
    status = requireWireValue(obj.string("status"), setOf("ACTIVE", "PAUSED", "ARCHIVED"), "值日轮班 status"),
    generatedThroughDate = obj.nullableString("generated_through_date"),
)

private fun parseDormDutyTask(obj: JsonValue.Obj): DormDutyTask = try {
    DormDutyTask(
        taskId = obj.string("task_id"),
        rotaId = obj.string("rota_id"),
        title = obj.string("title"),
        assignedMemberId = obj.string("assigned_member_id"),
        dueDate = obj.string("due_date"),
        status = dormDutyTaskStatusFromWire(obj.string("status")),
        completionNote = obj.nullableString("completion_note"),
        confirmationNote = obj.nullableString("confirmation_note"),
        skipReason = obj.nullableString("skip_reason"),
    )
} catch (error: IllegalArgumentException) {
    throw DormCollaborationException(DormErrorKind.CONTRACT, "值日任务违反契约: " + error.message, retryable = false, cause = error)
}

private fun parseDormDutySwap(obj: JsonValue.Obj): DormDutySwap = DormDutySwap(
    swapId = obj.string("swap_id"),
    rotaId = obj.string("rota_id"),
    dutyTaskId = obj.string("duty_task_id"),
    type = requireWireValue(obj.string("type"), setOf("SWAP", "COVER"), "换班 type"),
    requestedByMemberId = obj.string("requested_by_member_id"),
    targetMemberId = obj.string("target_member_id"),
    reason = obj.nullableString("reason"),
    status = requireWireValue(obj.string("status"), setOf("PENDING", "ACCEPTED", "REJECTED", "CANCELLED"), "换班 status"),
    resolutionNote = obj.nullableString("resolution_note"),
)

private fun parseDormRepairTicket(obj: JsonValue.Obj): DormRepairTicket = DormRepairTicket(
    repairId = obj.string("repair_id"),
    title = obj.string("title"),
    category = requireWireValue(obj.string("category"), setOf("PLUMBING", "ELECTRICAL", "FURNITURE", "NETWORK", "CLEANING", "SAFETY", "OTHER"), "报修 category"),
    locationText = obj.string("location_text"),
    description = obj.string("description"),
    reporterMemberId = obj.string("reporter_member_id"),
    assignedMemberId = obj.nullableString("assigned_member_id"),
    status = requireWireValue(obj.string("status"), setOf("OPEN", "TRIAGED", "IN_PROGRESS", "RESOLVED", "REJECTED", "CLOSED"), "报修 status"),
    resolutionNote = obj.nullableString("resolution_note"),
    reviewId = obj.nullableString("review_id"),
    createdAt = obj.string("created_at"),
    updatedAt = obj.string("updated_at"),
)
