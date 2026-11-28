package cn.edu.haut.campus.core.community

suspend fun CommunityClient.createPlaceStatusReport(
    placeRef: String,
    request: CreatePlaceStatusReportRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_PLACE_STATUS_REPORT,
    pathParams = mapOf("placeRef" to requirePlaceRef(placeRef)),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.listPlaceStatusReports(
    placeRef: String,
    metric: String? = null,
    includeExpired: Boolean? = null,
    cursor: String? = null,
    limit: Int? = null,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.LIST_PLACE_STATUS_REPORTS,
    pathParams = mapOf("placeRef" to requirePlaceRef(placeRef)),
    queryParams = communityQueryParams(
        "metric" to metric?.let { requireWireEnum(it, setOf("ACCESS", "OPERATION", "OCCUPANCY", "SAFETY"), "metric") },
        "include_expired" to includeExpired,
        "cursor" to cursor,
        "limit" to requireLimit(limit),
    ),
)

suspend fun CommunityClient.queryPlaceStatus(
    schoolId: String,
    placeRefs: List<String> = emptyList(),
    bbox: String? = null,
    placeTypes: List<String> = emptyList(),
    metrics: List<String> = emptyList(),
    includeExpired: Boolean? = null,
    cursor: String? = null,
    limit: Int? = null,
): CommunityResult<CommunityEnvelope> = run {
    requireText(schoolId, "school_id")
    require(placeRefs.size <= 100 && placeRefs.distinct().size == placeRefs.size) {
        "place_refs 必须唯一且不超过 100 个"
    }
    val validPlaceRefs = placeRefs.map { requirePlaceRef(it, "place_refs[]") }
    val validBbox = bbox?.let { requireBboxQuery(it) }
    require(validPlaceRefs.isNotEmpty() != (validBbox != null)) {
        "place_refs 与 bbox 必须且只能提供一种查询范围"
    }
    val allowedTypes = setOf("campus", "building", "floor", "room", "facility", "entrance", "indoor_node")
    require(placeTypes.distinct().size == placeTypes.size && placeTypes.all { it in allowedTypes }) {
        "place_types 必须是唯一契约地点类型"
    }
    val allowedMetrics = setOf("ACCESS", "OPERATION", "OCCUPANCY", "SAFETY")
    require(metrics.distinct().size == metrics.size && metrics.all { it in allowedMetrics }) {
        "metrics 必须是唯一契约 metric"
    }
    execute(
        CommunityOperation.QUERY_PLACE_STATUS,
        queryParams = communityQueryParams(
            "school_id" to schoolId,
            "place_refs" to validPlaceRefs.takeIf { it.isNotEmpty() },
            "bbox" to validBbox,
            "place_types" to placeTypes.takeIf { it.isNotEmpty() },
            "metrics" to metrics.takeIf { it.isNotEmpty() },
            "include_expired" to includeExpired,
            "cursor" to cursor,
            "limit" to requireLimit(limit),
        ),
    )
}

suspend fun CommunityClient.createPlaceStatusSubscription(
    request: CreatePlaceStatusSubscriptionRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_PLACE_STATUS_SUBSCRIPTION,
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.listPlaceStatusSubscriptions(
    schoolId: String,
    status: String? = null,
    cursor: String? = null,
    limit: Int? = null,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.LIST_PLACE_STATUS_SUBSCRIPTIONS,
    queryParams = communityQueryParams(
        "school_id" to requireText(schoolId, "school_id"),
        "status" to status?.let { requireWireEnum(it, setOf("ACTIVE", "CANCELLED"), "status") },
        "cursor" to cursor,
        "limit" to requireLimit(limit),
    ),
)

suspend fun CommunityClient.deletePlaceStatusSubscription(
    subscriptionId: String,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.DELETE_PLACE_STATUS_SUBSCRIPTION,
    pathParams = mapOf("subscriptionId" to requireUuid(subscriptionId, "subscriptionId")),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.flagPlaceStatusReport(
    reportId: String,
    request: CreatePlaceReportFlagRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.FLAG_PLACE_STATUS_REPORT,
    pathParams = mapOf("reportId" to requireUuid(reportId, "reportId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.listPlaceReportFlags(
    schoolId: String,
    status: String? = null,
    placeRef: String? = null,
    cursor: String? = null,
    limit: Int? = null,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.LIST_PLACE_REPORT_FLAGS,
    queryParams = communityQueryParams(
        "school_id" to requireText(schoolId, "school_id"),
        "status" to status?.let { requireWireEnum(it, setOf("OPEN", "RESOLVED_KEEP_VISIBLE", "RESOLVED_EXCLUDED", "REJECTED"), "status") },
        "place_ref" to placeRef?.let { requirePlaceRef(it) },
        "cursor" to cursor,
        "limit" to requireLimit(limit),
    ),
)

suspend fun CommunityClient.reviewPlaceReportFlag(
    flagId: String,
    request: ReviewPlaceReportFlagRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.REVIEW_PLACE_REPORT_FLAG,
    pathParams = mapOf("flagId" to requireUuid(flagId, "flagId")),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)
