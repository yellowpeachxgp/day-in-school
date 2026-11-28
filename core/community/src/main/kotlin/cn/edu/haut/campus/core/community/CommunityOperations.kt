package cn.edu.haut.campus.core.community

/** 手写客户端与 contracts/openapi/cloud-v1.yaml 逐项比对的 operation 事实表。 */
enum class CommunityOperation(
    val operationId: String,
    val method: String,
    val pathTemplate: String,
    val feature: CommunityFeature,
    val requestSchema: String? = null,
    val successEnvelope: String,
    val readOnly: Boolean = false,
    val idempotencyRequired: Boolean = false,
    /** MASTER_PLAN W4 只允许群组快照离线缓存；其他协作/众包数据离线时必须显式失败而非长期保留。 */
    val offlineCacheAllowed: Boolean = false,
) {
    LIST_GROUPS("listGroups", "GET", "/api/v1/groups", CommunityFeature.GROUPS, successEnvelope = "GroupListEnvelope", readOnly = true, offlineCacheAllowed = true),
    CREATE_GROUP("createGroup", "POST", "/api/v1/groups", CommunityFeature.GROUPS, requestSchema = "CreateGroupRequest", successEnvelope = "GroupEnvelope", idempotencyRequired = true),
    GET_GROUP("getGroup", "GET", "/api/v1/groups/{groupId}", CommunityFeature.GROUPS, successEnvelope = "GroupEnvelope", readOnly = true, offlineCacheAllowed = true),
    UPDATE_GROUP("updateGroup", "PATCH", "/api/v1/groups/{groupId}", CommunityFeature.GROUPS, requestSchema = "UpdateGroupRequest", successEnvelope = "GroupEnvelope", idempotencyRequired = true),
    DELETE_GROUP("deleteGroup", "DELETE", "/api/v1/groups/{groupId}", CommunityFeature.GROUPS, successEnvelope = "ActionAckEnvelope", idempotencyRequired = true),
    CREATE_GROUP_INVITE("createGroupInvite", "POST", "/api/v1/groups/{groupId}/invites", CommunityFeature.GROUPS, requestSchema = "CreateGroupInviteRequest", successEnvelope = "GroupInviteEnvelope", idempotencyRequired = true),
    ACCEPT_GROUP_INVITE("acceptGroupInvite", "POST", "/api/v1/group-invites/{code}/accept", CommunityFeature.GROUPS, successEnvelope = "GroupMemberEnvelope", idempotencyRequired = true),
    REVOKE_GROUP_INVITE("revokeGroupInvite", "DELETE", "/api/v1/groups/{groupId}/invites/{inviteId}", CommunityFeature.GROUPS, successEnvelope = "ActionAckEnvelope", idempotencyRequired = true),
    LIST_GROUP_MEMBERS("listGroupMembers", "GET", "/api/v1/groups/{groupId}/members", CommunityFeature.GROUPS, successEnvelope = "GroupMemberListEnvelope", readOnly = true, offlineCacheAllowed = true),
    UPDATE_GROUP_MEMBER("updateGroupMember", "PATCH", "/api/v1/groups/{groupId}/members/{memberId}", CommunityFeature.GROUPS, requestSchema = "UpdateGroupMemberRequest", successEnvelope = "GroupMemberEnvelope", idempotencyRequired = true),
    REMOVE_GROUP_MEMBER("removeGroupMember", "DELETE", "/api/v1/groups/{groupId}/members/{memberId}", CommunityFeature.GROUPS, successEnvelope = "ActionAckEnvelope", idempotencyRequired = true),

    REPLACE_MY_GROUP_AVAILABILITY("replaceMyGroupAvailability", "PUT", "/api/v1/groups/{groupId}/availability/me", CommunityFeature.AVAILABILITY, requestSchema = "ReplaceGroupAvailabilityRequest", successEnvelope = "GroupAvailabilityShareEnvelope", idempotencyRequired = true),
    DELETE_MY_GROUP_AVAILABILITY("deleteMyGroupAvailability", "DELETE", "/api/v1/groups/{groupId}/availability/me", CommunityFeature.AVAILABILITY, successEnvelope = "ActionAckEnvelope", idempotencyRequired = true),
    QUERY_GROUP_AVAILABILITY("queryGroupAvailability", "POST", "/api/v1/groups/{groupId}/availability/query", CommunityFeature.AVAILABILITY, requestSchema = "GroupAvailabilityQueryRequest", successEnvelope = "GroupAvailabilityQueryEnvelope", readOnly = true, idempotencyRequired = true),

    CREATE_GROUP_POLL("createGroupPoll", "POST", "/api/v1/groups/{groupId}/polls", CommunityFeature.POLLS, requestSchema = "CreateGroupPollRequest", successEnvelope = "GroupPollEnvelope", idempotencyRequired = true),
    GET_GROUP_POLL("getGroupPoll", "GET", "/api/v1/polls/{pollId}", CommunityFeature.POLLS, successEnvelope = "GroupPollEnvelope", readOnly = true),
    UPSERT_GROUP_POLL_VOTE("upsertGroupPollVote", "POST", "/api/v1/polls/{pollId}/votes", CommunityFeature.POLLS, requestSchema = "UpsertGroupPollVoteRequest", successEnvelope = "GroupPollVoteEnvelope", idempotencyRequired = true),
    CONFIRM_GROUP_POLL("confirmGroupPoll", "POST", "/api/v1/polls/{pollId}/confirm", CommunityFeature.POLLS, requestSchema = "ConfirmGroupPollRequest", successEnvelope = "ConfirmGroupPollEnvelope", idempotencyRequired = true),

    CREATE_LEDGER_ENTRY("createLedgerEntry", "POST", "/api/v1/groups/{groupId}/ledger/entries", CommunityFeature.LEDGER, requestSchema = "CreateLedgerEntryRequest", successEnvelope = "LedgerEntryEnvelope", idempotencyRequired = true),
    LIST_LEDGER_ENTRIES("listLedgerEntries", "GET", "/api/v1/groups/{groupId}/ledger/entries", CommunityFeature.LEDGER, successEnvelope = "LedgerEntryListEnvelope", readOnly = true),
    AMEND_LEDGER_ENTRY("amendLedgerEntry", "POST", "/api/v1/groups/{groupId}/ledger/entries/{entryId}/amend", CommunityFeature.LEDGER, requestSchema = "AmendLedgerEntryRequest", successEnvelope = "LedgerEntryEnvelope", idempotencyRequired = true),
    GET_LEDGER_BALANCES("getLedgerBalances", "GET", "/api/v1/groups/{groupId}/ledger/balances", CommunityFeature.LEDGER, successEnvelope = "LedgerBalanceEnvelope", readOnly = true),
    CREATE_SETTLEMENT("createSettlement", "POST", "/api/v1/groups/{groupId}/settlements", CommunityFeature.LEDGER, requestSchema = "CreateSettlementRequest", successEnvelope = "SettlementEnvelope", idempotencyRequired = true),
    LIST_SETTLEMENTS("listSettlements", "GET", "/api/v1/groups/{groupId}/settlements", CommunityFeature.LEDGER, successEnvelope = "SettlementListEnvelope", readOnly = true),
    GET_SETTLEMENT("getSettlement", "GET", "/api/v1/settlements/{settlementId}", CommunityFeature.LEDGER, successEnvelope = "SettlementEnvelope", readOnly = true),
    CONFIRM_SETTLEMENT("confirmSettlement", "POST", "/api/v1/settlements/{settlementId}/confirm", CommunityFeature.LEDGER, requestSchema = "ConfirmSettlementRequest", successEnvelope = "SettlementEnvelope", idempotencyRequired = true),
    CANCEL_SETTLEMENT("cancelSettlement", "POST", "/api/v1/settlements/{settlementId}/cancel", CommunityFeature.LEDGER, requestSchema = "CancelSettlementRequest", successEnvelope = "SettlementEnvelope", idempotencyRequired = true),

    CREATE_DUTY_ROTA("createDutyRota", "POST", "/api/v1/groups/{groupId}/rotas", CommunityFeature.DUTY, requestSchema = "CreateDutyRotaRequest", successEnvelope = "DutyRotaEnvelope", idempotencyRequired = true),
    LIST_DUTY_ROTAS("listDutyRotas", "GET", "/api/v1/groups/{groupId}/rotas", CommunityFeature.DUTY, successEnvelope = "DutyRotaListEnvelope", readOnly = true),
    GET_DUTY_ROTA("getDutyRota", "GET", "/api/v1/rotas/{rotaId}", CommunityFeature.DUTY, successEnvelope = "DutyRotaEnvelope", readOnly = true),
    UPDATE_DUTY_ROTA("updateDutyRota", "PATCH", "/api/v1/rotas/{rotaId}", CommunityFeature.DUTY, requestSchema = "UpdateDutyRotaRequest", successEnvelope = "DutyRotaEnvelope", idempotencyRequired = true),
    LIST_DUTY_TASKS("listDutyTasks", "GET", "/api/v1/groups/{groupId}/duty-tasks", CommunityFeature.DUTY, successEnvelope = "DutyTaskListEnvelope", readOnly = true),
    COMPLETE_DUTY_TASK("completeDutyTask", "POST", "/api/v1/duty-tasks/{taskId}/complete", CommunityFeature.DUTY, requestSchema = "CompleteDutyTaskRequest", successEnvelope = "DutyTaskEnvelope", idempotencyRequired = true),
    CONFIRM_DUTY_TASK_COMPLETION("confirmDutyTaskCompletion", "POST", "/api/v1/duty-tasks/{taskId}/confirm", CommunityFeature.DUTY, requestSchema = "ConfirmDutyTaskRequest", successEnvelope = "DutyTaskEnvelope", idempotencyRequired = true),
    SKIP_DUTY_TASK("skipDutyTask", "POST", "/api/v1/duty-tasks/{taskId}/skip", CommunityFeature.DUTY, requestSchema = "SkipDutyTaskRequest", successEnvelope = "DutyTaskEnvelope", idempotencyRequired = true),
    REQUEST_DUTY_SWAP("requestDutySwap", "POST", "/api/v1/rotas/{rotaId}/swaps", CommunityFeature.DUTY, requestSchema = "RequestDutySwapRequest", successEnvelope = "DutySwapEnvelope", idempotencyRequired = true),
    LIST_DUTY_SWAPS("listDutySwaps", "GET", "/api/v1/rotas/{rotaId}/swaps", CommunityFeature.DUTY, successEnvelope = "DutySwapListEnvelope", readOnly = true),
    GET_DUTY_SWAP("getDutySwap", "GET", "/api/v1/duty-swaps/{swapId}", CommunityFeature.DUTY, successEnvelope = "DutySwapEnvelope", readOnly = true),
    RESOLVE_DUTY_SWAP("resolveDutySwap", "POST", "/api/v1/duty-swaps/{swapId}/resolve", CommunityFeature.DUTY, requestSchema = "ResolveDutySwapRequest", successEnvelope = "DutySwapEnvelope", idempotencyRequired = true),

    CREATE_REPAIR_TICKET("createRepairTicket", "POST", "/api/v1/groups/{groupId}/repairs", CommunityFeature.REPAIRS, requestSchema = "CreateRepairTicketRequest", successEnvelope = "RepairTicketEnvelope", idempotencyRequired = true),
    LIST_REPAIR_TICKETS("listRepairTickets", "GET", "/api/v1/groups/{groupId}/repairs", CommunityFeature.REPAIRS, successEnvelope = "RepairTicketListEnvelope", readOnly = true),
    GET_REPAIR_TICKET("getRepairTicket", "GET", "/api/v1/repairs/{repairId}", CommunityFeature.REPAIRS, successEnvelope = "RepairTicketEnvelope", readOnly = true),
    UPDATE_REPAIR_TICKET("updateRepairTicket", "PATCH", "/api/v1/repairs/{repairId}", CommunityFeature.REPAIRS, requestSchema = "UpdateRepairTicketRequest", successEnvelope = "RepairTicketEnvelope", idempotencyRequired = true),
    TRANSITION_REPAIR_STATUS("transitionRepairStatus", "POST", "/api/v1/repairs/{repairId}/status", CommunityFeature.REPAIRS, requestSchema = "TransitionRepairStatusRequest", successEnvelope = "RepairTicketEnvelope", idempotencyRequired = true),
    SUBMIT_REPAIR_REVIEW("submitRepairReview", "POST", "/api/v1/repairs/{repairId}/review", CommunityFeature.REPAIRS, requestSchema = "SubmitRepairReviewRequest", successEnvelope = "RepairReviewEnvelope", idempotencyRequired = true),

    CREATE_PLACE_STATUS_REPORT("createPlaceStatusReport", "POST", "/api/v1/places/{placeRef}/reports", CommunityFeature.PLACE_STATUS, requestSchema = "CreatePlaceStatusReportRequest", successEnvelope = "PlaceStatusReportEnvelope", idempotencyRequired = true),
    LIST_PLACE_STATUS_REPORTS("listPlaceStatusReports", "GET", "/api/v1/places/{placeRef}/reports", CommunityFeature.PLACE_STATUS, successEnvelope = "PlaceStatusReportListEnvelope", readOnly = true),
    QUERY_PLACE_STATUS("queryPlaceStatus", "GET", "/api/v1/places/status", CommunityFeature.PLACE_STATUS, successEnvelope = "PlaceStatusQueryEnvelope", readOnly = true),
    CREATE_PLACE_STATUS_SUBSCRIPTION("createPlaceStatusSubscription", "POST", "/api/v1/places/status/subscriptions", CommunityFeature.PLACE_STATUS, requestSchema = "CreatePlaceStatusSubscriptionRequest", successEnvelope = "PlaceStatusSubscriptionEnvelope", idempotencyRequired = true),
    LIST_PLACE_STATUS_SUBSCRIPTIONS("listPlaceStatusSubscriptions", "GET", "/api/v1/places/status/subscriptions", CommunityFeature.PLACE_STATUS, successEnvelope = "PlaceStatusSubscriptionListEnvelope", readOnly = true),
    DELETE_PLACE_STATUS_SUBSCRIPTION("deletePlaceStatusSubscription", "DELETE", "/api/v1/places/status/subscriptions/{subscriptionId}", CommunityFeature.PLACE_STATUS, successEnvelope = "ActionAckEnvelope", idempotencyRequired = true),
    FLAG_PLACE_STATUS_REPORT("flagPlaceStatusReport", "POST", "/api/v1/place-reports/{reportId}/flag", CommunityFeature.PLACE_STATUS, requestSchema = "CreatePlaceReportFlagRequest", successEnvelope = "PlaceReportFlagEnvelope", idempotencyRequired = true),
    LIST_PLACE_REPORT_FLAGS("listPlaceReportFlags", "GET", "/api/v1/admin/place-report-flags", CommunityFeature.PLACE_STATUS, successEnvelope = "PlaceReportFlagListEnvelope", readOnly = true),
    REVIEW_PLACE_REPORT_FLAG("reviewPlaceReportFlag", "POST", "/api/v1/admin/place-report-flags/{flagId}/review", CommunityFeature.PLACE_STATUS, requestSchema = "ReviewPlaceReportFlagRequest", successEnvelope = "PlaceReportFlagReviewEnvelope", idempotencyRequired = true),
    ;

    init {
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "非法 HTTP 方法" }
        require(pathTemplate.startsWith("/api/v1/")) { "协作 API 路径必须属于 /api/v1" }
        require(!readOnly || method == "GET" || operationId == "queryGroupAvailability") {
            "除忙闲交集查询外，只有 GET 可标记为只读"
        }
        require(!offlineCacheAllowed || readOnly) { "离线缓存只允许用于只读 operation" }
        require(
            !offlineCacheAllowed || operationId in setOf("listGroups", "getGroup", "listGroupMembers"),
        ) { "W4 隐私边界只允许群组/成员快照离线缓存" }
    }

    companion object {
        val all: List<CommunityOperation> = entries.toList()
        private val byOperationId = all.associateBy { it.operationId }

        fun byOperationId(operationId: String): CommunityOperation? = byOperationId[operationId]
    }
}
