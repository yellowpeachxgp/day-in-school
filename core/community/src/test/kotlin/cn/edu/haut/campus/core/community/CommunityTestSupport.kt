package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.io.IOException

internal fun communityTestScope(): SchoolAccountScope = SchoolAccountScope(
    schoolId = SchoolId("haut"),
    accountId = AccountId("acct_community_test"),
    profileId = ProfileId("prof_community_test"),
)

internal fun communityTestCacheIdentity(
    cloudUserId: String = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    identityGeneration: Long = 1L,
): CommunityCacheIdentity = CommunityCacheIdentity(
    cloudUserId = cloudUserId,
    identityGeneration = identityGeneration,
)

internal fun communityListGroupsCacheKey(
    identity: CommunityCacheIdentity = communityTestCacheIdentity(),
): CommunityCacheKey = CommunityCacheKey(
    cloudIdentity = identity,
    apiBaseUrl = "https://cloud.example.test",
    operationId = "listGroups",
    resolvedPath = "/api/v1/groups",
    queryParams = mapOf("school_id" to listOf("haut")),
)

private fun responseMeta(requestId: String): JsonValue.Obj = JsonValue.Obj(
    linkedMapOf(
        "request_id" to JsonValue.Str(requestId),
        "api_version" to JsonValue.Str("v1"),
    ),
)

internal fun successBody(dataJson: String, requestId: String = "req-test"): String {
    val root = JsonValue.Obj(
        linkedMapOf(
            "ok" to JsonValue.Bool(true),
            "code" to JsonValue.Str("OK"),
            "request_id" to JsonValue.Str(requestId),
            "data" to Json.parse(dataJson),
            "meta" to responseMeta(requestId),
        ),
    )
    return Json.canonical(root)
}

internal fun errorBody(code: String, retryable: Boolean, requestId: String = "req-error"): String {
    val detail = JsonValue.Obj(
        linkedMapOf(
            "code" to JsonValue.Str(code),
            "message" to JsonValue.Str("synthetic contract error"),
            "retryable" to JsonValue.Bool(retryable),
            "user_action" to JsonValue.Str("RETRY_LATER"),
            "detail" to JsonValue.Obj(linkedMapOf("reason" to JsonValue.Str("synthetic"))),
        ),
    )
    val root = JsonValue.Obj(
        linkedMapOf(
            "ok" to JsonValue.Bool(false),
            "code" to JsonValue.Str(code),
            "request_id" to JsonValue.Str(requestId),
            "error" to detail,
            "meta" to responseMeta(requestId),
        ),
    )
    return Json.canonical(root)
}

internal fun envelopeFromBody(body: String): CommunityEnvelope =
    requireNotNull(parseCommunityEnvelope(requireNotNull(Json.parse(body).asObjOrNull)))

internal fun jsonObjectOf(vararg fields: Pair<String, JsonValue>): JsonValue.Obj =
    JsonValue.Obj(linkedMapOf(*fields))

internal sealed interface CommunityTransportAction {
    data class Respond(val response: CommunityHttpResponse) : CommunityTransportAction
    data class Fail(val error: IOException) : CommunityTransportAction
}

internal class RecordingCommunityTransport(vararg actions: CommunityTransportAction) : CommunityTransport {
    private val pending = java.util.ArrayDeque<CommunityTransportAction>()
    val requests = mutableListOf<CommunityHttpRequest>()

    init {
        actions.forEach { pending.add(it) }
    }

    override suspend fun execute(request: CommunityHttpRequest): CommunityHttpResponse {
        requests += request.copy()
        return when (val action = pending.poll() ?: error("no scripted transport action left")) {
            is CommunityTransportAction.Respond -> action.response
            is CommunityTransportAction.Fail -> throw action.error
        }
    }
}

internal fun jsonResponse(body: String, status: Int = 200): CommunityTransportAction.Respond =
    CommunityTransportAction.Respond(CommunityHttpResponse(statusCode = status, body = body))

internal fun networkFailure(): CommunityTransportAction.Fail =
    CommunityTransportAction.Fail(IOException("synthetic offline transport"))
