package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonParseException
import cn.edu.haut.campus.core.model.json.JsonValue
import java.math.BigDecimal

/** 一条配置校验违例；path 与 detail 不包含实例值，避免把用户配置写进日志。 */
data class JsonSchemaViolation(
    val path: String,
    val rule: String,
    val detail: String
)

class JsonConfigValidationException(
    val schemaType: String,
    val violations: List<JsonSchemaViolation>
) : IllegalArgumentException(
    schemaType + " 配置不符合 JSON Schema: " + violations.size + " 项违例"
)

/**
 * 自动化配置使用的 JSON Schema 安全子集。
 *
 * 支持 type/properties/required/additionalProperties/items/minItems/maxItems/minLength/maxLength/pattern、
 * minimum/maximum、enum/const 与 allOf/anyOf/oneOf/not。未支持的求值关键字会在 schema 构造时拒绝，
 * 避免给插件一种“已经声明但被静默忽略”的假安全感。该实现不依赖网络、Android 或第三方库。
 */
class JsonSchema private constructor(
    val document: JsonValue.Obj
) {
    init {
        validateSchemaNode(document, "$", 0)
    }

    fun validate(instance: JsonValue): List<JsonSchemaViolation> {
        val violations = ArrayList<JsonSchemaViolation>()
        validateInstance(document, instance, "$", violations)
        return violations
    }

    fun requireValid(instance: JsonValue, schemaType: String) {
        val violations = validate(instance)
        if (violations.isNotEmpty()) throw JsonConfigValidationException(schemaType, violations)
    }

    companion object {
        /** 适用于无配置扩展：必须是 object 且不允许任何字段。 */
        val EMPTY_OBJECT: JsonSchema = objectSchema()

        fun of(document: JsonValue.Obj): JsonSchema = JsonSchema(document)

        fun parse(raw: String): JsonSchema {
            val parsed = try {
                Json.parse(raw)
            } catch (error: JsonParseException) {
                throw IllegalArgumentException("JSON Schema 不是合法 JSON", error)
            }
            val obj = parsed as? JsonValue.Obj
                ?: throw IllegalArgumentException("JSON Schema 根节点必须是 object")
            return JsonSchema(obj)
        }

        fun objectSchema(
            properties: Map<String, JsonValue.Obj> = emptyMap(),
            required: Set<String> = emptySet(),
            additionalProperties: Boolean = false
        ): JsonSchema {
            val fields = LinkedHashMap<String, JsonValue>()
            fields["type"] = JsonValue.Str("object")
            if (properties.isNotEmpty()) {
                fields["properties"] = JsonValue.Obj(properties.mapValues { it.value as JsonValue })
            }
            if (required.isNotEmpty()) {
                fields["required"] = JsonValue.Arr(required.sorted().map { JsonValue.Str(it) })
            }
            fields["additionalProperties"] = JsonValue.Bool(additionalProperties)
            return JsonSchema(JsonValue.Obj(fields))
        }
    }
}

private val SCHEMA_KEYWORDS = setOf(
    "$schema", "title", "description", "default", "examples",
    "type", "properties", "required", "additionalProperties", "items",
    "minItems", "maxItems", "minLength", "maxLength", "pattern",
    "minimum", "maximum", "enum", "const",
    "allOf", "anyOf", "oneOf", "not",
    "x-dis-sensitive", "x-dis-mutable"
)

private val JSON_TYPES = setOf("object", "array", "string", "number", "integer", "boolean", "null")

private fun validateSchemaNode(schema: JsonValue.Obj, path: String, depth: Int) {
    require(depth <= 32) { "JSON Schema 嵌套过深: " + path }
    val unknown = schema.fields.keys - SCHEMA_KEYWORDS
    require(unknown.isEmpty()) { "JSON Schema 含未支持的求值关键字: " + unknown.sorted().joinToString(",") }

    schema.fields["type"]?.let { typeNode ->
        when (typeNode) {
            is JsonValue.Str -> require(typeNode.value in JSON_TYPES) { "未知 JSON Schema type: " + typeNode.value }
            is JsonValue.Arr -> {
                require(typeNode.items.isNotEmpty()) { "JSON Schema type 数组不能为空" }
                val values = typeNode.items.map {
                    require(it is JsonValue.Str && it.value in JSON_TYPES) { "JSON Schema type 数组含非法项" }
                    (it as JsonValue.Str).value
                }
                require(values.distinct().size == values.size) { "JSON Schema type 数组含重复项" }
            }
            else -> throw IllegalArgumentException("JSON Schema type 必须是 string 或 string array")
        }
    }

    schema.fields["properties"]?.let { node ->
        val properties = node as? JsonValue.Obj ?: throw IllegalArgumentException("properties 必须是 object")
        properties.fields.forEach { (name, child) ->
            val childObj = child as? JsonValue.Obj ?: throw IllegalArgumentException("properties." + name + " 必须是 object schema")
            validateSchemaNode(childObj, path + ".properties." + name, depth + 1)
        }
    }

    schema.fields["additionalProperties"]?.let { node ->
        when (node) {
            is JsonValue.Bool -> Unit
            is JsonValue.Obj -> validateSchemaNode(node, path + ".additionalProperties", depth + 1)
            else -> throw IllegalArgumentException("additionalProperties 必须是 boolean 或 object schema")
        }
    }

    schema.fields["items"]?.let { node ->
        val items = node as? JsonValue.Obj ?: throw IllegalArgumentException("items 必须是 object schema")
        validateSchemaNode(items, path + ".items", depth + 1)
    }

    schema.fields["required"]?.let { node ->
        val required = node as? JsonValue.Arr ?: throw IllegalArgumentException("required 必须是 array")
        val names = required.items.map {
            require(it is JsonValue.Str) { "required 只能包含 string" }
            (it as JsonValue.Str).value
        }
        require(names.distinct().size == names.size) { "required 含重复字段" }
    }

    listOf("allOf", "anyOf", "oneOf").forEach { keyword ->
        schema.fields[keyword]?.let { node ->
            val branches = node as? JsonValue.Arr ?: throw IllegalArgumentException(keyword + " 必须是 array")
            require(branches.items.isNotEmpty()) { keyword + " 不能为空" }
            branches.items.forEachIndexed { index, child ->
                val childObj = child as? JsonValue.Obj ?: throw IllegalArgumentException(keyword + "[" + index + "] 必须是 object schema")
                validateSchemaNode(childObj, path + "." + keyword + "[" + index + "]", depth + 1)
            }
        }
    }

    schema.fields["not"]?.let { node ->
        val child = node as? JsonValue.Obj ?: throw IllegalArgumentException("not 必须是 object schema")
        validateSchemaNode(child, path + ".not", depth + 1)
    }

    schema.fields["enum"]?.let { node ->
        val values = node as? JsonValue.Arr ?: throw IllegalArgumentException("enum 必须是 array")
        require(values.items.isNotEmpty()) { "enum 不能为空" }
    }

    intKeyword(schema, "minItems")
    intKeyword(schema, "maxItems")
    intKeyword(schema, "minLength")
    intKeyword(schema, "maxLength")
    checkMinMax(schema, "minItems", "maxItems")
    checkMinMax(schema, "minLength", "maxLength")

    schema.fields["pattern"]?.let { node ->
        val pattern = node as? JsonValue.Str ?: throw IllegalArgumentException("pattern 必须是 string")
        try {
            Regex(pattern.value)
        } catch (error: Exception) {
            throw IllegalArgumentException("pattern 不是合法正则", error)
        }
    }

    schema.fields["minimum"]?.let { require(it is JsonValue.Num) { "minimum 必须是 number" } }
    schema.fields["maximum"]?.let { require(it is JsonValue.Num) { "maximum 必须是 number" } }
    val minimum = (schema.fields["minimum"] as? JsonValue.Num)?.literal?.toBigDecimalOrNullStrict()
    val maximum = (schema.fields["maximum"] as? JsonValue.Num)?.literal?.toBigDecimalOrNullStrict()
    if (minimum != null && maximum != null) {
        require(minimum <= maximum) { "minimum 不能大于 maximum" }
    }
}

private fun intKeyword(schema: JsonValue.Obj, keyword: String) {
    schema.fields[keyword]?.let { node ->
        val number = node as? JsonValue.Num ?: throw IllegalArgumentException(keyword + " 必须是 integer")
        val value = number.literal.toBigDecimalOrNullStrict()
            ?: throw IllegalArgumentException(keyword + " 必须是合法数字")
        require(value.stripTrailingZeros().scale() <= 0 && value >= BigDecimal.ZERO) {
            keyword + " 必须是非负 integer"
        }
    }
}

private fun checkMinMax(schema: JsonValue.Obj, minKeyword: String, maxKeyword: String) {
    val min = (schema.fields[minKeyword] as? JsonValue.Num)?.literal?.toBigDecimalOrNullStrict()
    val max = (schema.fields[maxKeyword] as? JsonValue.Num)?.literal?.toBigDecimalOrNullStrict()
    if (min != null && max != null) require(min <= max) { minKeyword + " 不能大于 " + maxKeyword }
}

private fun validateInstance(
    schema: JsonValue.Obj,
    instance: JsonValue,
    path: String,
    violations: MutableList<JsonSchemaViolation>
) {
    val typeNode = schema.fields["type"]
    val typeMatches = if (typeNode == null) {
        true
    } else {
        val accepted = acceptedTypes(typeNode)
        val matches = accepted.any { matchesType(instance, it) }
        if (!matches) {
            violations += JsonSchemaViolation(path, "type", "类型不符合 schema")
        }
        matches
    }

    schema.fields["const"]?.let { expected ->
        if (!jsonEquals(expected, instance)) {
            violations += JsonSchemaViolation(path, "const", "值不等于 const")
        }
    }
    (schema.fields["enum"] as? JsonValue.Arr)?.let { enumValues ->
        if (enumValues.items.none { jsonEquals(it, instance) }) {
            violations += JsonSchemaViolation(path, "enum", "值不在 enum 允许集合内")
        }
    }

    if (typeMatches) {
        when (instance) {
            is JsonValue.Obj -> validateObjectInstance(schema, instance, path, violations)
            is JsonValue.Arr -> validateArrayInstance(schema, instance, path, violations)
            is JsonValue.Str -> validateStringInstance(schema, instance, path, violations)
            is JsonValue.Num -> validateNumberInstance(schema, instance, path, violations)
            else -> Unit
        }
    }

    validateCompositions(schema, instance, path, violations)
}

private fun validateObjectInstance(
    schema: JsonValue.Obj,
    instance: JsonValue.Obj,
    path: String,
    violations: MutableList<JsonSchemaViolation>
) {
    val properties = (schema.fields["properties"] as? JsonValue.Obj)?.fields ?: emptyMap()
    (schema.fields["required"] as? JsonValue.Arr)?.items?.forEach { requiredNode ->
        val name = (requiredNode as JsonValue.Str).value
        if (!instance.fields.containsKey(name)) {
            violations += JsonSchemaViolation(childPath(path, name), "required", "缺少必填字段")
        }
    }

    instance.fields.forEach { (name, value) ->
        val propertySchema = properties[name] as? JsonValue.Obj
        if (propertySchema != null) {
            validateInstance(propertySchema, value, childPath(path, name), violations)
        } else {
            when (val additional = schema.fields["additionalProperties"]) {
                is JsonValue.Bool -> if (!additional.value) {
                    violations += JsonSchemaViolation(childPath(path, name), "additionalProperties", "不允许未声明字段")
                }
                is JsonValue.Obj -> validateInstance(additional, value, childPath(path, name), violations)
                else -> Unit
            }
        }
    }
}

private fun validateArrayInstance(
    schema: JsonValue.Obj,
    instance: JsonValue.Arr,
    path: String,
    violations: MutableList<JsonSchemaViolation>
) {
    intBound(schema, "minItems", instance.items.size)?.let {
        violations += JsonSchemaViolation(path, "minItems", "数组长度小于下限 " + it)
    }
    intBound(schema, "maxItems", instance.items.size)?.let {
        violations += JsonSchemaViolation(path, "maxItems", "数组长度大于上限 " + it)
    }
    (schema.fields["items"] as? JsonValue.Obj)?.let { itemSchema ->
        instance.items.forEachIndexed { index, item ->
            validateInstance(itemSchema, item, path + "[" + index + "]", violations)
        }
    }
}

private fun validateStringInstance(
    schema: JsonValue.Obj,
    instance: JsonValue.Str,
    path: String,
    violations: MutableList<JsonSchemaViolation>
) {
    intBound(schema, "minLength", instance.value.length)?.let {
        violations += JsonSchemaViolation(path, "minLength", "字符串长度小于下限 " + it)
    }
    intBound(schema, "maxLength", instance.value.length)?.let {
        violations += JsonSchemaViolation(path, "maxLength", "字符串长度大于上限 " + it)
    }
    (schema.fields["pattern"] as? JsonValue.Str)?.let { pattern ->
        if (!Regex(pattern.value).containsMatchIn(instance.value)) {
            violations += JsonSchemaViolation(path, "pattern", "字符串不匹配 pattern")
        }
    }
}

private fun validateNumberInstance(
    schema: JsonValue.Obj,
    instance: JsonValue.Num,
    path: String,
    violations: MutableList<JsonSchemaViolation>
) {
    val value = instance.literal.toBigDecimalOrNullStrict()
    if (value == null) {
        violations += JsonSchemaViolation(path, "number", "数字字面量非法")
        return
    }
    (schema.fields["minimum"] as? JsonValue.Num)?.literal?.toBigDecimalOrNullStrict()?.let { minimum ->
        if (value < minimum) violations += JsonSchemaViolation(path, "minimum", "数字小于下限")
    }
    (schema.fields["maximum"] as? JsonValue.Num)?.literal?.toBigDecimalOrNullStrict()?.let { maximum ->
        if (value > maximum) violations += JsonSchemaViolation(path, "maximum", "数字大于上限")
    }
}

private fun validateCompositions(
    schema: JsonValue.Obj,
    instance: JsonValue,
    path: String,
    violations: MutableList<JsonSchemaViolation>
) {
    (schema.fields["allOf"] as? JsonValue.Arr)?.items?.forEach { branch ->
        validateInstance(branch as JsonValue.Obj, instance, path, violations)
    }
    (schema.fields["anyOf"] as? JsonValue.Arr)?.let { branches ->
        val valid = branches.items.any { branch ->
            val childViolations = ArrayList<JsonSchemaViolation>()
            validateInstance(branch as JsonValue.Obj, instance, path, childViolations)
            childViolations.isEmpty()
        }
        if (!valid) violations += JsonSchemaViolation(path, "anyOf", "不满足任何分支")
    }
    (schema.fields["oneOf"] as? JsonValue.Arr)?.let { branches ->
        val validCount = branches.items.count { branch ->
            val childViolations = ArrayList<JsonSchemaViolation>()
            validateInstance(branch as JsonValue.Obj, instance, path, childViolations)
            childViolations.isEmpty()
        }
        if (validCount != 1) violations += JsonSchemaViolation(path, "oneOf", "满足分支数不是 1")
    }
    (schema.fields["not"] as? JsonValue.Obj)?.let { branch ->
        val childViolations = ArrayList<JsonSchemaViolation>()
        validateInstance(branch, instance, path, childViolations)
        if (childViolations.isEmpty()) violations += JsonSchemaViolation(path, "not", "命中被禁止的分支")
    }
}

private fun acceptedTypes(typeNode: JsonValue): Set<String> = when (typeNode) {
    is JsonValue.Str -> setOf(typeNode.value)
    is JsonValue.Arr -> typeNode.items.map { (it as JsonValue.Str).value }.toSet()
    else -> emptySet()
}

private fun matchesType(instance: JsonValue, type: String): Boolean = when (type) {
    "object" -> instance is JsonValue.Obj
    "array" -> instance is JsonValue.Arr
    "string" -> instance is JsonValue.Str
    "boolean" -> instance is JsonValue.Bool
    "null" -> instance == JsonValue.Null
    "number" -> instance is JsonValue.Num
    "integer" -> instance is JsonValue.Num && isIntegerLiteral(instance.literal)
    else -> false
}

private fun isIntegerLiteral(literal: String): Boolean {
    val value = literal.toBigDecimalOrNullStrict() ?: return false
    return value.stripTrailingZeros().scale() <= 0
}

private fun intBound(schema: JsonValue.Obj, keyword: String, actual: Int): Int? {
    val bound = (schema.fields[keyword] as? JsonValue.Num)?.literal?.toIntOrNull() ?: return null
    return when (keyword) {
        "minItems", "minLength" -> if (actual < bound) bound else null
        "maxItems", "maxLength" -> if (actual > bound) bound else null
        else -> null
    }
}

private fun jsonEquals(left: JsonValue, right: JsonValue): Boolean {
    if (left == right) return true
    val leftCanonical = runCatching { Json.canonical(left) }.getOrNull()
    val rightCanonical = runCatching { Json.canonical(right) }.getOrNull()
    return leftCanonical != null && leftCanonical == rightCanonical
}

private fun childPath(parent: String, key: String): String =
    if (key.all { it.isLetterOrDigit() || it == '_' || it == '-' } && key.isNotEmpty()) {
        parent + "." + key
    } else {
        parent + "[key]"
    }

private fun String.toBigDecimalOrNullStrict(): BigDecimal? = try {
    BigDecimal(this)
} catch (error: NumberFormatException) {
    null
}
