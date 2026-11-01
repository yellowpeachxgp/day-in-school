package cn.edu.haut.campus.core.packages

import cn.edu.haut.campus.core.packages.bundle.PackageBundle
import cn.edu.haut.campus.core.packages.bundle.PackageLimits
import cn.edu.haut.campus.core.packages.crypto.Ed25519
import cn.edu.haut.campus.core.packages.crypto.PackageSignaturePayload
import cn.edu.haut.campus.core.packages.crypto.PublisherKeyRegistry
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

/**
 * 合成夹具工厂。
 *
 * UNVERIFIED：这里的清单、文件、权限与夹具摘要全部是按公开协议格式推导的合成数据，
 * 不代表任何真实学校、真实发行渠道或真实密钥；没有对任何学校做过验证。
 * 所有断言消息都带 "UNVERIFIED" 前缀，避免被误读成实测结论。
 */
object SyntheticPackages {

    const val PUBLISHER: String = "example-contrib"
    const val PACKAGE_ID: String = "cn.example.synthetic-profile"
    const val ORIGIN: String = "https://jwgl.example.edu"
    const val SCHOOL_ID: String = "example-univ"
    const val PROVIDER_ID: String = "example-zf-modern"
    const val FIXTURE_DIGEST: String = "0000000000000000000000000000000000000000000000000000000000000000"

    const val DEFAULT_PERMISSIONS: String =
        "[{\"kind\":\"network.request\",\"origins\":[\"" + ORIGIN + "\"]," +
            "\"paths\":[\"/app/\"],\"methods\":[\"GET\"],\"purposes\":[\"READ\"]}]"

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun rawPublic(keyPair: KeyPair): ByteArray = Ed25519.toRaw(keyPair.public)

    fun registry(keyPair: KeyPair, publisher: String = PUBLISHER): PublisherKeyRegistry =
        PublisherKeyRegistry(mapOf(publisher to rawPublic(keyPair)))

    fun sign(keyPair: KeyPair, manifestBytes: ByteArray): ByteArray {
        val payload = PackageSignaturePayload.of(manifestBytes)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(payload)
        return signer.sign()
    }

    fun bundle(
        contents: Map<String, String> = mapOf("profile/school.json" to "{\"displayName\":\"合成学校\"}"),
        limits: PackageLimits = PackageLimits.DEFAULT
    ): PackageBundle = PackageBundle.ofContents(
        contents.mapValues { it.value.toByteArray(Charsets.UTF_8) },
        limits
    )

    fun filesOf(bundle: PackageBundle): Map<String, String> = bundle.files.associate { it.path to it.sha256 }

    fun manifest(
        bundle: PackageBundle,
        id: String = PACKAGE_ID,
        version: String = "1.0.0",
        type: String = "school-profile",
        publisher: String = PUBLISHER,
        license: String = "Apache-2.0",
        targetSchool: String = SCHOOL_ID,
        targetProvider: String? = PROVIDER_ID,
        minHostApi: Int = 1,
        minAppVersionCode: Int = 1103,
        capabilities: List<String> = listOf("TIMETABLE_READ", "GRADES_READ"),
        permissions: String = DEFAULT_PERMISSIONS,
        files: Map<String, String> = filesOf(bundle),
        verificationLevel: String = "MOCK_TESTED",
        extraTopLevel: String = ""
    ): String {
        val target = if (targetProvider == null) {
            "{\"schoolId\":\"" + targetSchool + "\"}"
        } else {
            "{\"schoolId\":\"" + targetSchool + "\",\"provider\":\"" + targetProvider + "\"}"
        }
        val fileEntries = files.entries.sortedBy { it.key }
            .joinToString(",\n    ") { "\"" + it.key + "\": \"" + it.value + "\"" }
        val capabilityList = capabilities.joinToString(",") { "\"" + it + "\"" }
        val extra = if (extraTopLevel.isBlank()) "" else ",\n  " + extraTopLevel
        return """
            {
              "format": "dis-campus-plugin/v1",
              "id": "$id",
              "version": "$version",
              "type": "$type",
              "publisher": "$publisher",
              "license": "$license",
              "targets": [$target],
              "minHostApi": $minHostApi,
              "minAppVersionCode": $minAppVersionCode,
              "capabilities": [$capabilityList],
              "requiredPermissions": $permissions,
              "files": {
                $fileEntries
              },
              "verification": {"level": "$verificationLevel", "fixtureDigest": "$FIXTURE_DIGEST"}$extra
            }
        """.trimIndent()
    }

    /** 组合夹具：同一把密钥、同一个包、签名与字节都就绪。 */
    fun signed(
        keyPair: KeyPair,
        contents: Map<String, String> = mapOf("profile/school.json" to "{\"displayName\":\"合成学校\"}"),
        version: String = "1.0.0",
        permissions: String = DEFAULT_PERMISSIONS,
        capabilities: List<String> = listOf("TIMETABLE_READ", "GRADES_READ")
    ): SignedPackage {
        val bundle = bundle(contents)
        val manifest = manifest(
            bundle = bundle,
            version = version,
            permissions = permissions,
            capabilities = capabilities
        )
        val bytes = manifest.toByteArray(Charsets.UTF_8)
        return SignedPackage(
            bundle = bundle,
            manifestBytes = bytes,
            signature = sign(keyPair, bytes),
            manifestText = manifest
        )
    }

    data class SignedPackage(
        val bundle: PackageBundle,
        val manifestBytes: ByteArray,
        val signature: ByteArray,
        val manifestText: String
    )

}
