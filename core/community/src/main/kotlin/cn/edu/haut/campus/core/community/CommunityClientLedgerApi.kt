package cn.edu.haut.campus.core.community

suspend fun CommunityClient.createLedgerEntry(
    groupId: String,
    request: CreateLedgerEntryRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_LEDGER_ENTRY,
    pathParams = mapOf("groupId" to requireUuid(groupId, "groupId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.listLedgerEntries(
    groupId: String,
    correctsEntryId: String? = null,
    cursor: String? = null,
    limit: Int? = null,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.LIST_LEDGER_ENTRIES,
    pathParams = mapOf("groupId" to requireUuid(groupId, "groupId")),
    queryParams = communityQueryParams(
        "corrects_entry_id" to correctsEntryId?.let { requireUuid(it, "corrects_entry_id") },
        "cursor" to cursor,
        "limit" to requireLimit(limit),
    ),
)

suspend fun CommunityClient.amendLedgerEntry(
    groupId: String,
    entryId: String,
    request: AmendLedgerEntryRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.AMEND_LEDGER_ENTRY,
    pathParams = mapOf("groupId" to requireUuid(groupId, "groupId"), "entryId" to requireUuid(entryId, "entryId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.getLedgerBalances(groupId: String): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.GET_LEDGER_BALANCES,
    pathParams = mapOf("groupId" to requireUuid(groupId, "groupId")),
)

suspend fun CommunityClient.createSettlement(
    groupId: String,
    request: CreateSettlementRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_SETTLEMENT,
    pathParams = mapOf("groupId" to requireUuid(groupId, "groupId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.listSettlements(
    groupId: String,
    status: String? = null,
    cursor: String? = null,
    limit: Int? = null,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.LIST_SETTLEMENTS,
    pathParams = mapOf("groupId" to requireUuid(groupId, "groupId")),
    queryParams = communityQueryParams(
        "status" to status?.let {
            requireWireEnum(it, setOf("PENDING_BOTH_CONFIRMATIONS", "PENDING_DEBTOR_CONFIRMATION", "PENDING_CREDITOR_CONFIRMATION", "CONFIRMED", "CANCELLED"), "status")
        },
        "cursor" to cursor,
        "limit" to requireLimit(limit),
    ),
)

suspend fun CommunityClient.getSettlement(settlementId: String): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.GET_SETTLEMENT,
    pathParams = mapOf("settlementId" to requireUuid(settlementId, "settlementId")),
)

suspend fun CommunityClient.confirmSettlement(
    settlementId: String,
    request: ConfirmSettlementRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CONFIRM_SETTLEMENT,
    pathParams = mapOf("settlementId" to requireUuid(settlementId, "settlementId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.cancelSettlement(
    settlementId: String,
    request: CancelSettlementRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CANCEL_SETTLEMENT,
    pathParams = mapOf("settlementId" to requireUuid(settlementId, "settlementId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.createDutyRota(
    groupId: String,
    request: CreateDutyRotaRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_DUTY_ROTA,
    pathParams = mapOf("groupId" to requireUuid(groupId, "groupId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.listDutyRotas(
    groupId: String,
    status: String? = null,
    cursor: String? = null,
    limit: Int? = null,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.LIST_DUTY_ROTAS,
    pathParams = mapOf("groupId" to requireUuid(groupId, "groupId")),
    queryParams = communityQueryParams(
        "status" to status?.let { requireWireEnum(it, setOf("ACTIVE", "PAUSED", "ARCHIVED"), "status") },
        "cursor" to cursor,
        "limit" to requireLimit(limit),
    ),
)

suspend fun CommunityClient.getDutyRota(rotaId: String): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.GET_DUTY_ROTA,
    pathParams = mapOf("rotaId" to requireUuid(rotaId, "rotaId")),
)

suspend fun CommunityClient.updateDutyRota(
    rotaId: String,
    request: UpdateDutyRotaRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.UPDATE_DUTY_ROTA,
    pathParams = mapOf("rotaId" to requireUuid(rotaId, "rotaId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.listDutyTasks(
    groupId: String,
    status: String? = null,
    assignedMemberId: String? = null,
    fromDate: String? = null,
    toDate: String? = null,
    cursor: String? = null,
    limit: Int? = null,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.LIST_DUTY_TASKS,
    pathParams = mapOf("groupId" to requireUuid(groupId, "groupId")),
    queryParams = communityQueryParams(
        "status" to status?.let { requireWireEnum(it, setOf("PENDING", "COMPLETED", "CONFIRMED", "SKIPPED"), "status") },
        "assigned_member_id" to assignedMemberId?.let { requireUuid(it, "assigned_member_id") },
        "from_date" to fromDate?.let { requireIsoDate(it, "from_date") },
        "to_date" to toDate?.let { requireIsoDate(it, "to_date") },
        "cursor" to cursor,
        "limit" to requireLimit(limit),
    ),
)

suspend fun CommunityClient.completeDutyTask(
    taskId: String,
    request: CompleteDutyTaskRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.COMPLETE_DUTY_TASK,
    pathParams = mapOf("taskId" to requireUuid(taskId, "taskId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.confirmDutyTaskCompletion(
    taskId: String,
    request: ConfirmDutyTaskRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CONFIRM_DUTY_TASK_COMPLETION,
    pathParams = mapOf("taskId" to requireUuid(taskId, "taskId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.skipDutyTask(
    taskId: String,
    request: SkipDutyTaskRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.SKIP_DUTY_TASK,
    pathParams = mapOf("taskId" to requireUuid(taskId, "taskId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.requestDutySwap(
    rotaId: String,
    request: RequestDutySwapRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.REQUEST_DUTY_SWAP,
    pathParams = mapOf("rotaId" to requireUuid(rotaId, "rotaId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.listDutySwaps(
    rotaId: String,
    status: String? = null,
    dutyTaskId: String? = null,
    cursor: String? = null,
    limit: Int? = null,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.LIST_DUTY_SWAPS,
    pathParams = mapOf("rotaId" to requireUuid(rotaId, "rotaId")),
    queryParams = communityQueryParams(
        "status" to status?.let { requireWireEnum(it, setOf("PENDING", "ACCEPTED", "REJECTED", "CANCELLED"), "status") },
        "duty_task_id" to dutyTaskId?.let { requireUuid(it, "duty_task_id") },
        "cursor" to cursor,
        "limit" to requireLimit(limit),
    ),
)

suspend fun CommunityClient.getDutySwap(swapId: String): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.GET_DUTY_SWAP,
    pathParams = mapOf("swapId" to requireUuid(swapId, "swapId")),
)

suspend fun CommunityClient.resolveDutySwap(
    swapId: String,
    request: ResolveDutySwapRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.RESOLVE_DUTY_SWAP,
    pathParams = mapOf("swapId" to requireUuid(swapId, "swapId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.createRepairTicket(
    groupId: String,
    request: CreateRepairTicketRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_REPAIR_TICKET,
    pathParams = mapOf("groupId" to requireUuid(groupId, "groupId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.listRepairTickets(
    groupId: String,
    status: String? = null,
    cursor: String? = null,
    limit: Int? = null,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.LIST_REPAIR_TICKETS,
    pathParams = mapOf("groupId" to requireUuid(groupId, "groupId")),
    queryParams = communityQueryParams(
        "status" to status?.let { requireWireEnum(it, setOf("OPEN", "TRIAGED", "IN_PROGRESS", "RESOLVED", "REJECTED", "CLOSED"), "status") },
        "cursor" to cursor,
        "limit" to requireLimit(limit),
    ),
)

suspend fun CommunityClient.getRepairTicket(repairId: String): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.GET_REPAIR_TICKET,
    pathParams = mapOf("repairId" to requireUuid(repairId, "repairId")),
)

suspend fun CommunityClient.updateRepairTicket(
    repairId: String,
    request: UpdateRepairTicketRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.UPDATE_REPAIR_TICKET,
    pathParams = mapOf("repairId" to requireUuid(repairId, "repairId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.transitionRepairStatus(
    repairId: String,
    request: TransitionRepairStatusRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.TRANSITION_REPAIR_STATUS,
    pathParams = mapOf("repairId" to requireUuid(repairId, "repairId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.submitRepairReview(
    repairId: String,
    request: SubmitRepairReviewRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.SUBMIT_REPAIR_REVIEW,
    pathParams = mapOf("repairId" to requireUuid(repairId, "repairId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)
