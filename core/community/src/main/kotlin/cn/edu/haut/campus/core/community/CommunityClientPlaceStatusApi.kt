package cn.edu.haut.campus.core.community

suspend fun CommunityClient.createPlaceStatusReport(
    placeRef: String,
    request: CreatePlaceStatusReportRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_PLACE_STATUS_REPORT,
    pathParams = mapOf("placeRef" to placeRef),
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
    pathParams = mapOf("placeRef" to placeRef),
    queryParams = communityQueryParams(
        "metric" to metric,
        "include_expired" to includeExpired,
        "cursor" to cursor,
        "limit" to limit,
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
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.QUERY_PLACE_STATUS,
    queryParams = communityQueryParams(
        "school_id" to schoolId,
        "place_refs" to placeRefs.takeIf { it.isNotEmpty() },
        "bbox" to bbox,
        "place_types" to placeTypes.takeIf { it.isNotEmpty() },
        "metrics" to metrics.takeIf { it.isNotEmpty() },
        "include_expired" to includeExpired,
        "cursor" to cursor,
        "limit" to limit,
    ),
)

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
        "school_id" to schoolId,
        "status" to status,
        "cursor" to cursor,
        "limit" to limit,
    ),
)

suspend fun CommunityClient.deletePlaceStatusSubscription(
    subscriptionId: String,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.DELETE_PLACE_STATUS_SUBSCRIPTION,
    pathParams = mapOf("subscriptionId" to subscriptionId),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.flagPlaceStatusReport(
    reportId: String,
    request: CreatePlaceReportFlagRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.FLAG_PLACE_STATUS_REPORT,
    pathParams = mapOf("reportId" to reportId),
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
        "school_id" to schoolId,
        "status" to status,
        "place_ref" to placeRef,
        "cursor" to cursor,
        "limit" to limit,
    ),
)

suspend fun CommunityClient.reviewPlaceReportFlag(
    flagId: String,
    request: ReviewPlaceReportFlagRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.REVIEW_PLACE_REPORT_FLAG,
    pathParams = mapOf("flagId" to flagId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)
