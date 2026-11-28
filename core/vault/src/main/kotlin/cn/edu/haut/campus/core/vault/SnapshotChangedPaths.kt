package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue

/**
 * 从 journal before/after 快照提取“只含路径、不含值”的变更形状。
 *
 * 事件消费者只需要知道哪些子树发生变化；具体值必须通过 changeId 回到 entity_journal 授权读取。
 * 路径使用 RFC 6901 JSON Pointer。数组索引也占一个路径段。 exact 路径过多时逐层折叠到祖先路径，
 * 保证 vault.entity.changed 始终是小型事件，不会把课程聚合或批量规则变化放大成大 payload。
 */
internal object SnapshotChangedPaths {
    private const val MAX_PATHS_BEFORE_COLLAPSE = 64
    private val DEPTH_FALLBACKS = listOf(8, 4, 2, 1)

    fun of(before: CanonicalJson?, after: CanonicalJson?): List<String> {
        val beforeValue = before?.let { Json.parse(it.text) }
        val afterValue = after?.let { Json.parse(it.text) }
        DEPTH_FALLBACKS.forEach { maxDepth ->
            val paths = diff(beforeValue, afterValue, maxDepth)
            if (paths.size <= MAX_PATHS_BEFORE_COLLAPSE) {
                return paths.ifEmpty { listOf("/") }
            }
        }
        return diff(beforeValue, afterValue, maxDepth = 1).ifEmpty { listOf("/") }
    }

    private fun diff(before: JsonValue?, after: JsonValue?, maxDepth: Int): List<String> {
        val paths = linkedSetOf<String>()
        diffValue(
            path = "",
            before = before,
            after = after,
            depth = 0,
            maxDepth = maxDepth,
            out = paths
        )
        return paths.sorted()
    }

    private fun diffValue(
        path: String,
        before: JsonValue?,
        after: JsonValue?,
        depth: Int,
        maxDepth: Int,
        out: MutableSet<String>
    ) {
        if (jsonValueEquals(before, after)) return
        if (depth >= maxDepth) {
            out += path.ifEmpty { "/" }
            return
        }
        if (before is JsonValue.Obj && after is JsonValue.Obj) {
            val keys = (before.fields.keys + after.fields.keys).sorted()
            keys.forEach { key ->
                diffValue(
                    path = path + "/" + escapeJsonPointer(key),
                    before = before.fields[key],
                    after = after.fields[key],
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    out = out
                )
            }
            return
        }
        if (before == null && after is JsonValue.Obj) {
            diffObjectAsAddition(path, after, depth, maxDepth, out)
            return
        }
        if (after == null && before is JsonValue.Obj) {
            diffObjectAsAddition(path, before, depth, maxDepth, out)
            return
        }
        if (before is JsonValue.Arr && after is JsonValue.Arr) {
            val common = minOf(before.items.size, after.items.size)
            for (index in 0 until common) {
                diffValue(
                    path = path + "/" + index,
                    before = before.items[index],
                    after = after.items[index],
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    out = out
                )
            }
            if (before.items.size != after.items.size) {
                val extra = if (before.items.size > after.items.size) before.items.size else after.items.size
                for (index in common until extra) {
                    diffValue(
                        path = path + "/" + index,
                        before = before.items.getOrNull(index),
                        after = after.items.getOrNull(index),
                        depth = depth + 1,
                        maxDepth = maxDepth,
                        out = out
                    )
                }
            }
            return
        }
        if (before == null && after is JsonValue.Arr) {
            diffArrayAsAddition(path, after, depth, maxDepth, out)
            return
        }
        if (after == null && before is JsonValue.Arr) {
            diffArrayAsAddition(path, before, depth, maxDepth, out)
            return
        }
        out += path.ifEmpty { "/" }
    }

    private fun diffObjectAsAddition(
        path: String,
        value: JsonValue.Obj,
        depth: Int,
        maxDepth: Int,
        out: MutableSet<String>
    ) {
        if (value.fields.isEmpty() || depth >= maxDepth) {
            out += path.ifEmpty { "/" }
            return
        }
        value.fields.keys.sorted().forEach { key ->
            val childPath = path + "/" + escapeJsonPointer(key)
            when (val child = value.fields.getValue(key)) {
                is JsonValue.Obj -> diffObjectAsAddition(childPath, child, depth + 1, maxDepth, out)
                is JsonValue.Arr -> diffArrayAsAddition(childPath, child, depth + 1, maxDepth, out)
                else -> out += childPath
            }
        }
    }

    private fun diffArrayAsAddition(
        path: String,
        value: JsonValue.Arr,
        depth: Int,
        maxDepth: Int,
        out: MutableSet<String>
    ) {
        if (value.items.isEmpty() || depth >= maxDepth) {
            out += path.ifEmpty { "/" }
            return
        }
        value.items.forEachIndexed { index, child ->
            val childPath = path + "/" + index
            when (child) {
                is JsonValue.Obj -> diffObjectAsAddition(childPath, child, depth + 1, maxDepth, out)
                is JsonValue.Arr -> diffArrayAsAddition(childPath, child, depth + 1, maxDepth, out)
                else -> out += childPath
            }
        }
    }

    private fun jsonValueEquals(left: JsonValue?, right: JsonValue?): Boolean {
        if (left == null || right == null) return left == right
        if (left::class != right::class) return false
        return when (left) {
            is JsonValue.Obj -> right is JsonValue.Obj && left.fields == right.fields
            is JsonValue.Arr -> right is JsonValue.Arr && left.items == right.items
            else -> Json.canonical(left) == Json.canonical(right)
        }
    }

    private fun escapeJsonPointer(segment: String): String =
        segment.replace("~", "~0").replace("/", "~1")
}
