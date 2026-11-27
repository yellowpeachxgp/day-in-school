package cn.edu.haut.campus.core.community

suspend fun CommunityClient.listGroups(
    schoolId: String,
    type: String? = null,
    cursor: String? = null,
    limit: Int? = null,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.LIST_GROUPS,
    queryParams = communityQueryParams(
        "school_id" to schoolId,
        "type" to type,
        "cursor" to cursor,
        "limit" to limit,
    ),
)

suspend fun CommunityClient.createGroup(
    request: CreateGroupRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_GROUP,
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.getGroup(groupId: String): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.GET_GROUP,
    pathParams = mapOf("groupId" to groupId),
)

suspend fun CommunityClient.updateGroup(
    groupId: String,
    request: UpdateGroupRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.UPDATE_GROUP,
    pathParams = mapOf("groupId" to groupId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.deleteGroup(
    groupId: String,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.DELETE_GROUP,
    pathParams = mapOf("groupId" to groupId),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.createGroupInvite(
    groupId: String,
    request: CreateGroupInviteRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_GROUP_INVITE,
    pathParams = mapOf("groupId" to groupId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.acceptGroupInvite(
    code: String,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.ACCEPT_GROUP_INVITE,
    pathParams = mapOf("code" to code),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.revokeGroupInvite(
    groupId: String,
    inviteId: String,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.REVOKE_GROUP_INVITE,
    pathParams = mapOf("groupId" to groupId, "inviteId" to inviteId),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.listGroupMembers(
    groupId: String,
    cursor: String? = null,
    limit: Int? = null,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.LIST_GROUP_MEMBERS,
    pathParams = mapOf("groupId" to groupId),
    queryParams = communityQueryParams("cursor" to cursor, "limit" to limit),
)

suspend fun CommunityClient.updateGroupMember(
    groupId: String,
    memberId: String,
    request: UpdateGroupMemberRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.UPDATE_GROUP_MEMBER,
    pathParams = mapOf("groupId" to groupId, "memberId" to memberId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.removeGroupMember(
    groupId: String,
    memberId: String,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.REMOVE_GROUP_MEMBER,
    pathParams = mapOf("groupId" to groupId, "memberId" to memberId),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.replaceMyGroupAvailability(
    groupId: String,
    request: ReplaceGroupAvailabilityRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.REPLACE_MY_GROUP_AVAILABILITY,
    pathParams = mapOf("groupId" to groupId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.deleteMyGroupAvailability(
    groupId: String,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.DELETE_MY_GROUP_AVAILABILITY,
    pathParams = mapOf("groupId" to groupId),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.queryGroupAvailability(
    groupId: String,
    request: GroupAvailabilityQueryRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.QUERY_GROUP_AVAILABILITY,
    pathParams = mapOf("groupId" to groupId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.createGroupPoll(
    groupId: String,
    request: CreateGroupPollRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CREATE_GROUP_POLL,
    pathParams = mapOf("groupId" to groupId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.getGroupPoll(pollId: String): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.GET_GROUP_POLL,
    pathParams = mapOf("pollId" to pollId),
)

suspend fun CommunityClient.upsertGroupPollVote(
    pollId: String,
    request: UpsertGroupPollVoteRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.UPSERT_GROUP_POLL_VOTE,
    pathParams = mapOf("pollId" to pollId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)

suspend fun CommunityClient.confirmGroupPoll(
    pollId: String,
    request: ConfirmGroupPollRequest,
    idempotencyKey: String,
): CommunityResult<CommunityEnvelope> = execute(
    CommunityOperation.CONFIRM_GROUP_POLL,
    pathParams = mapOf("pollId" to pollId),
    body = request.toJson(),
    idempotencyKey = idempotencyKey,
)
