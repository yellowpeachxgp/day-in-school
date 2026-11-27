package cn.edu.haut.campus.core.community

suspend fun CommunityClient.createLedgerEntry(
    groupId: String,
    request: CreateLedgerEntryRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_LEDGER_ENTRY,
    pathParams = mapOf("groupId" to groupId),
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
    pathParams = mapOf("groupId" to groupId),
    queryParams = communityQueryParams(
        "corrects_entry_id" to correctsEntryId,
        "cursor" to cursor,
        "limit" to limit,
    ),
)

suspend fun CommunityClient.amendLedgerEntry(
    groupId: String,
    entryId: String,
    request: AmendLedgerEntryRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.AMEND_LEDGER_ENTRY,
    pathParams = mapOf("groupId" to groupId, "entryId" to entryId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.getLedgerBalances(groupId: String): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.GET_LEDGER_BALANCES,
    pathParams = mapOf("groupId" to groupId),
)

suspend fun CommunityClient.createSettlement(
    groupId: String,
    request: CreateSettlementRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_SETTLEMENT,
    pathParams = mapOf("groupId" to groupId),
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
    pathParams = mapOf("groupId" to groupId),
    queryParams = communityQueryParams("status" to status, "cursor" to cursor, "limit" to limit),
)

suspend fun CommunityClient.getSettlement(settlementId: String): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.GET_SETTLEMENT,
    pathParams = mapOf("settlementId" to settlementId),
)

suspend fun CommunityClient.confirmSettlement(
    settlementId: String,
    request: ConfirmSettlementRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CONFIRM_SETTLEMENT,
    pathParams = mapOf("settlementId" to settlementId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.cancelSettlement(
    settlementId: String,
    request: CancelSettlementRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CANCEL_SETTLEMENT,
    pathParams = mapOf("settlementId" to settlementId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.createDutyRota(
    groupId: String,
    request: CreateDutyRotaRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_DUTY_ROTA,
    pathParams = mapOf("groupId" to groupId),
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
    pathParams = mapOf("groupId" to groupId),
    queryParams = communityQueryParams("status" to status, "cursor" to cursor, "limit" to limit),
)

suspend fun CommunityClient.getDutyRota(rotaId: String): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.GET_DUTY_ROTA,
    pathParams = mapOf("rotaId" to rotaId),
)

suspend fun CommunityClient.updateDutyRota(
    rotaId: String,
    request: UpdateDutyRotaRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.UPDATE_DUTY_ROTA,
    pathParams = mapOf("rotaId" to rotaId),
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
    pathParams = mapOf("groupId" to groupId),
    queryParams = communityQueryParams(
        "status" to status,
        "assigned_member_id" to assignedMemberId,
        "from_date" to fromDate,
        "to_date" to toDate,
        "cursor" to cursor,
        "limit" to limit,
    ),
)

suspend fun CommunityClient.completeDutyTask(
    taskId: String,
    request: CompleteDutyTaskRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.COMPLETE_DUTY_TASK,
    pathParams = mapOf("taskId" to taskId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.confirmDutyTaskCompletion(
    taskId: String,
    request: ConfirmDutyTaskRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CONFIRM_DUTY_TASK_COMPLETION,
    pathParams = mapOf("taskId" to taskId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.skipDutyTask(
    taskId: String,
    request: SkipDutyTaskRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.SKIP_DUTY_TASK,
    pathParams = mapOf("taskId" to taskId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.requestDutySwap(
    rotaId: String,
    request: RequestDutySwapRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.REQUEST_DUTY_SWAP,
    pathParams = mapOf("rotaId" to rotaId),
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
    pathParams = mapOf("rotaId" to rotaId),
    queryParams = communityQueryParams(
        "status" to status,
        "duty_task_id" to dutyTaskId,
        "cursor" to cursor,
        "limit" to limit,
    ),
)

suspend fun CommunityClient.getDutySwap(swapId: String): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.GET_DUTY_SWAP,
    pathParams = mapOf("swapId" to swapId),
)

suspend fun CommunityClient.resolveDutySwap(
    swapId: String,
    request: ResolveDutySwapRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.RESOLVE_DUTY_SWAP,
    pathParams = mapOf("swapId" to swapId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.createRepairTicket(
    groupId: String,
    request: CreateRepairTicketRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_REPAIR_TICKET,
    pathParams = mapOf("groupId" to groupId),
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
    pathParams = mapOf("groupId" to groupId),
    queryParams = communityQueryParams("status" to status, "cursor" to cursor, "limit" to limit),
)

suspend fun CommunityClient.getRepairTicket(repairId: String): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.GET_REPAIR_TICKET,
    pathParams = mapOf("repairId" to repairId),
)

suspend fun CommunityClient.updateRepairTicket(
    repairId: String,
    request: UpdateRepairTicketRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.UPDATE_REPAIR_TICKET,
    pathParams = mapOf("repairId" to repairId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.transitionRepairStatus(
    repairId: String,
    request: TransitionRepairStatusRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.TRANSITION_REPAIR_STATUS,
    pathParams = mapOf("repairId" to repairId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.submitRepairReview(
    repairId: String,
    request: SubmitRepairReviewRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.SUBMIT_REPAIR_REVIEW,
    pathParams = mapOf("repairId" to repairId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)
