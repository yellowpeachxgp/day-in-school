package cn.edu.haut.campus.feature.teamtime

import cn.edu.haut.campus.core.model.json.JsonValue

/** 契约 envelope data 的 fail-closed 解析器；字段缺失或类型不符时绝不猜测。 */
internal object TeamTimeJson {
    fun parseGroupList(data: JsonValue?): List<TeamTimeGroup> =
        data.asArr("data").items.map(::parseGroup)

    fun parseGroup(value: JsonValue?): TeamTimeGroup {
        val obj = value.asObj("group")
        return TeamTimeGroup(
            groupId = obj.reqString("group_id"),
            schoolId = obj.reqString("school_id"),
            type = obj.reqString("type"),
            name = obj.reqString("name"),
            status = obj.reqString("status"),
            memberCount = obj.reqInt("member_count"),
            myRole = obj.reqString("my_role"),
        )
    }

    fun parseMemberList(data: JsonValue?): List<TeamTimeMember> =
        data.asArr("data").items.map(::parseMember)

    fun parseMember(value: JsonValue?): TeamTimeMember {
        val obj = value.asObj("group member")
        return TeamTimeMember(
            memberId = obj.reqString("member_id"),
            groupId = obj.reqString("group_id"),
            cloudUserId = obj.reqString("cloud_user_id"),
            displayName = obj.optString("display_name"),
            role = obj.reqString("role"),
            sharingLevel = obj.reqString("sharing_level"),
            status = obj.reqString("status"),
        )
    }

    fun parseShare(data: JsonValue?): TeamTimeShareSummary {
        val obj = data.asObj("availability share")
        return TeamTimeShareSummary(
            memberId = obj.reqString("member_id"),
            sharingLevel = obj.reqString("sharing_level"),
            blockCount = obj.reqInt("block_count"),
            ttlSeconds = obj.reqInt("ttl_seconds"),
            expiresAt = parseTeamTimeInstant(obj.reqString("expires_at"), "expires_at"),
            updatedAt = parseTeamTimeInstant(obj.reqString("updated_at"), "updated_at"),
        )
    }

    fun parseAvailabilityQuery(data: JsonValue?): List<TeamTimeCandidate> {
        val obj = data.asObj("availability query result")
        return obj.reqArray("candidates").items.map(::parseCandidate)
    }

    private fun parseCandidate(value: JsonValue?): TeamTimeCandidate {
        val obj = value.asObj("availability candidate")
        return TeamTimeCandidate(
            startAt = parseTeamTimeInstant(obj.reqString("start_at"), "candidate.start_at"),
            endAt = parseTeamTimeInstant(obj.reqString("end_at"), "candidate.end_at"),
            availableMemberCount = obj.reqInt("available_member_count"),
            busyMemberCount = obj.reqInt("busy_member_count"),
            unknownMemberCount = obj.reqInt("unknown_member_count"),
            totalMemberCount = obj.reqInt("total_member_count"),
            memberResults = obj.reqArray("member_results").items.map(::parseMemberAvailability),
        )
    }

    private fun parseMemberAvailability(value: JsonValue?): TeamTimeMemberAvailability {
        val obj = value.asObj("member availability")
        return TeamTimeMemberAvailability(
            memberId = obj.reqString("member_id"),
            status = TeamTimeAvailabilityStatus.valueOf(obj.reqString("status")),
            sharingLevel = obj.reqString("sharing_level"),
        )
    }

    fun parsePoll(data: JsonValue?): TeamTimePoll {
        val obj = data.asObj("group poll")
        return TeamTimePoll(
            pollId = obj.reqString("poll_id"),
            groupId = obj.reqString("group_id"),
            schoolId = obj.reqString("school_id"),
            title = obj.reqString("title"),
            status = TeamTimePollStatus.valueOf(obj.reqString("status")),
            options = obj.reqArray("options").items.map(::parsePollOption),
            votingDeadlineAt = parseTeamTimeInstant(obj.reqString("voting_deadline_at"), "voting_deadline_at"),
            createdByMemberId = obj.reqString("created_by_member_id"),
            createdAt = parseTeamTimeInstant(obj.reqString("created_at"), "created_at"),
            closedAt = obj.optString("closed_at")?.let { parseTeamTimeInstant(it, "closed_at") },
            confirmedAt = obj.optString("confirmed_at")?.let { parseTeamTimeInstant(it, "confirmed_at") },
            confirmedOptionId = obj.optString("confirmed_option_id"),
            confirmedEventId = obj.optString("confirmed_event_id"),
            myVoteOptionId = obj.optString("my_vote_option_id"),
        )
    }

    private fun parsePollOption(value: JsonValue?): TeamTimePollOption {
        val obj = value.asObj("poll option")
        return TeamTimePollOption(
            optionId = obj.reqString("option_id"),
            startAt = parseTeamTimeInstant(obj.reqString("option.start_at"), "option.start_at"),
            endAt = parseTeamTimeInstant(obj.reqString("option.end_at"), "option.end_at"),
            voteCount = obj.reqInt("vote_count"),
            voterMemberIds = obj.reqArray("voter_member_ids").items.map { it.asString("voter_member_ids[]") },
        )
    }

    fun parseVoteResult(data: JsonValue?): TeamTimePoll {
        val obj = data.asObj("poll vote result")
        return parsePoll(obj.fields["poll"])
    }

    fun parseConfirmResult(data: JsonValue?): Pair<TeamTimePoll, ConfirmedGroupEvent> {
        val obj = data.asObj("confirm poll result")
        return parsePoll(obj.fields["poll"]) to parseConfirmedEvent(obj.fields["confirmed_event"])
    }

    private fun parseConfirmedEvent(value: JsonValue?): ConfirmedGroupEvent {
        val obj = value.asObj("confirmed group event")
        return ConfirmedGroupEvent(
            eventId = obj.reqString("event_id"),
            pollId = obj.reqString("poll_id"),
            optionId = obj.reqString("option_id"),
            groupId = obj.reqString("group_id"),
            schoolId = obj.reqString("school_id"),
            startAt = parseTeamTimeInstant(obj.reqString("event.start_at"), "event.start_at"),
            endAt = parseTeamTimeInstant(obj.reqString("event.end_at"), "event.end_at"),
            status = obj.reqString("status"),
            confirmedByMemberId = obj.reqString("confirmed_by_member_id"),
            confirmedAt = parseTeamTimeInstant(obj.reqString("event.confirmed_at"), "event.confirmed_at"),
        )
    }

    private fun JsonValue?.asObj(label: String): JsonValue.Obj =
        this?.asObjOrNull ?: throw IllegalArgumentException(label + " 必须是 JSON object")

    private fun JsonValue?.asArr(label: String): JsonValue.Arr =
        this?.asArrOrNull ?: throw IllegalArgumentException(label + " 必须是 JSON array")

    private fun JsonValue?.asString(label: String): String =
        this?.stringOrNull()?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException(label + " 必须是非空字符串")

    private fun JsonValue.Obj.reqString(key: String): String = fields[key].asString(key)

    private fun JsonValue.Obj.optString(key: String): String? =
        fields[key]?.stringOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonValue.Obj.reqArray(key: String): JsonValue.Arr = fields[key].asArr(key)

    private fun JsonValue.Obj.reqInt(key: String): Int {
        val literal = (fields[key] as? JsonValue.Num)?.literal
            ?: throw IllegalArgumentException(key + " 必须是整数")
        return literal.toIntOrNull() ?: throw IllegalArgumentException(key + " 必须是整数")
    }
}
