package cn.edu.haut.campus.core.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Line-oriented extraction of the checked-in OpenAPI copy. The test intentionally reads the
 * authoritative YAML text instead of duplicating a generated manifest: adding, renaming, or
 * flipping a W4 community operation in contracts/ immediately breaks this parity test.
 */
class CommunityContractParityTest {
    private data class ContractOperation(
        val operationId: String,
        val method: String,
        val path: String,
        val requestSchema: String?,
        val successEnvelopes: Set<String>,
        val idempotencyRequired: Boolean,
        val status: String,
    )

    @Test
    fun handwrittenOperationTableMatchesOpenApiExactly() {
        val contractOperations = extractCommunityOperations(Files.readString(Path.of("contracts/openapi/cloud-v1.yaml")))
        val localOperations = CommunityOperation.all.associateBy { it.operationId }

        assertEquals("contract operation ids must exactly match handwritten client ids", contractOperations.keys, localOperations.keys)
        assertEquals("community contract operation count", contractOperations.size, localOperations.size)

        for ((operationId, contract) in contractOperations) {
            val local = requireNotNull(localOperations[operationId])
            assertEquals(operationId + " method", contract.method, local.method)
            assertEquals(operationId + " path", contract.path, local.pathTemplate)
            assertEquals(operationId + " request schema", contract.requestSchema, local.requestSchema)
            assertEquals(
                operationId + " must have exactly one 2xx envelope shape",
                setOf(contract.successEnvelopes.single()),
                contract.successEnvelopes,
            )
            assertEquals(operationId + " success envelope", contract.successEnvelopes.single(), local.successEnvelope)
            assertEquals(operationId + " idempotency", contract.idempotencyRequired, local.idempotencyRequired)
            assertEquals(operationId + " runtime status", "active", contract.status)
        }
    }

    @Test
    fun everyWriteOperationRequiresIdempotencyAndEveryGetIsReadOnly() {
        for (operation in CommunityOperation.all) {
            if (operation.method == "GET") {
                assertTrue(operation.operationId + " GET must be cache-readable", operation.readOnly)
                assertTrue(operation.operationId + " GET must not require idempotency", !operation.idempotencyRequired)
            }
            if (operation.method in setOf("POST", "PUT", "PATCH", "DELETE")) {
                assertTrue(operation.operationId + " write/query operation must require idempotency", operation.idempotencyRequired)
            }
        }
    }

    private fun extractCommunityOperations(yaml: String): Map<String, ContractOperation> {
        val operations = linkedMapOf<String, ContractOperation>()
        var currentPath: String? = null
        var currentMethod: String? = null
        var operationId: String? = null
        var requestSchema: String? = null
        var idempotencyRequired = false
        var status: String? = null
        var insideRequestBody = false
        var insideResponses = false
        var currentStatusCode: String? = null
        val successEnvelopes = linkedSetOf<String>()

        fun resetOperationState() {
            operationId = null
            requestSchema = null
            idempotencyRequired = false
            status = null
            insideRequestBody = false
            insideResponses = false
            currentStatusCode = null
            successEnvelopes.clear()
        }

        fun flush() {
            val oid = operationId
            val method = currentMethod
            val path = currentPath
            if (oid != null && method != null && path != null && isCommunityPath(path)) {
                operations[oid] = ContractOperation(
                    operationId = oid,
                    method = method.uppercase(),
                    path = path,
                    requestSchema = requestSchema,
                    successEnvelopes = successEnvelopes.toSet(),
                    idempotencyRequired = idempotencyRequired,
                    status = status ?: "",
                )
            }
        }

        resetOperationState()
        yaml.lines().forEach { line ->
            val pathMatch = Regex("^  (/api/v1\\S*):").find(line)
            if (pathMatch != null) {
                flush()
                currentPath = pathMatch.groupValues[1]
                currentMethod = null
                resetOperationState()
                return@forEach
            }

            val methodMatch = Regex("^    (get|post|put|patch|delete):\\s*$").find(line)
            if (methodMatch != null) {
                flush()
                currentMethod = methodMatch.groupValues[1]
                resetOperationState()
                return@forEach
            }
            if (currentMethod == null) return@forEach

            val trimmed = line.trim()
            when {
                trimmed.startsWith("operationId: ") -> operationId = trimmed.removePrefix("operationId: ").trim()
                trimmed.startsWith("x-dis-status: ") -> status = trimmed.removePrefix("x-dis-status: ").trim()
                trimmed.startsWith("x-dis-idempotency: ") -> idempotencyRequired = trimmed.endsWith("required")
                trimmed == "requestBody:" -> {
                    insideRequestBody = true
                    insideResponses = false
                    currentStatusCode = null
                }
                trimmed == "responses:" -> {
                    insideRequestBody = false
                    insideResponses = true
                    currentStatusCode = null
                }
            }

            if (insideRequestBody && requestSchema == null) {
                extractSchemaRef(trimmed)?.let { requestSchema = it }
            }
            if (insideResponses) {
                val statusMatch = Regex("^'(\\d{3})':").find(trimmed)
                if (statusMatch != null) currentStatusCode = statusMatch.groupValues[1]
                if (currentStatusCode?.startsWith("2") == true) {
                    extractSchemaRef(trimmed)?.let { successEnvelopes += it }
                }
            }
        }
        flush()
        return operations
    }

    private fun extractSchemaRef(trimmedLine: String): String? {
        val prefix = '$'.toString() + "ref: '#/components/schemas/"
        if (!trimmedLine.startsWith(prefix) || !trimmedLine.endsWith("'")) return null
        return trimmedLine.removePrefix(prefix).removeSuffix("'").takeIf { it.isNotBlank() }
    }

    private fun isCommunityPath(path: String): Boolean = listOf(
        "/groups",
        "/group-invites",
        "/polls",
        "/ledger",
        "/settlements",
        "/rotas",
        "/duty",
        "/repairs",
        "/places",
        "/place-reports",
        "/place-report-flags",
    ).any { path.contains(it) }
}
