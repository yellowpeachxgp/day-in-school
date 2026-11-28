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

internal fun successBody(dataJson: String, requestId: String = "req-test"): String =
    "{"ok":true,"code":"OK","request_id":"" + requestId + "","data":" + dataJson +
        ","meta":{"request_id":"" + requestId + "","api_version":"v1"}}"

internal fun errorBody(code: String, retryable: Boolean, requestId: String = "req-error"): String =
    "{"ok":false,"code":"" + code + "","request_id":"" + requestId + ""," +
        ""error":{"code":"" + code + "","message":"synthetic contract error","retryable":" + retryable + "}," +
        ""meta":{"request_id":"" + requestId + "","api_version":"v1"}}"

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
