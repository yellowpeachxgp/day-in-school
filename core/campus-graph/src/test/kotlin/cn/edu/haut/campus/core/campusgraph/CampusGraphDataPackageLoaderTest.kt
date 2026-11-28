package cn.edu.haut.campus.core.campusgraph

import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.packages.bundle.PackageBundle
import cn.edu.haut.campus.core.packages.bundle.PackageFile
import cn.edu.haut.campus.core.packages.manifest.PackageLicense
import cn.edu.haut.campus.core.packages.manifest.PackageManifest
import cn.edu.haut.campus.core.packages.manifest.PackageTarget
import cn.edu.haut.campus.core.packages.manifest.PackageType
import cn.edu.haut.campus.core.packages.manifest.PackageVerification
import cn.edu.haut.campus.core.packages.manifest.PackageVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class CampusGraphDataPackageLoaderTest {

    private val school = SchoolId("haut")
    private val campus = CampusId("lianhuajie")
    private val campusRef = CampusGraphIds.build(school, campus, CampusGraphEntityType.CAMPUS, "lianhuajie")

    @Test
    fun loadValidatesTargetDigestAndReturnsDecodedDataPackageEntities() {
        val bundle = bundle()
        val manifest = manifest(bundle)
        val building = building(CampusGraphSourceLevel.DATA_PACKAGE)
        var decodeCalls = 0
        val loader = CampusGraphDataPackageLoader(CampusGraphDataPackageDecoder { manifestArg, bundleArg ->
            decodeCalls += 1
            assertEquals(manifest, manifestArg)
            assertEquals(bundle.digest, bundleArg.digest)
            listOf(building)
        })

        val loaded = loader.load(manifest, bundle, school, campus)
        assertEquals(listOf(building), loaded)
        assertEquals(1, decodeCalls)
    }

    @Test
    fun loadRejectsDigestMismatchWrongSchoolAndNonDataPackageEntities() {
        val bundle = bundle()
        val manifest = manifest(bundle)

        assertThrowsIllegalArgument {
            loaderReturning(listOf(building(CampusGraphSourceLevel.DATA_PACKAGE)))
                .load(manifest(bundle, fileDigestOverride = "0".repeat(64)), bundle, school, campus)
        }
        assertThrowsIllegalArgument {
            loaderReturning(listOf(building(CampusGraphSourceLevel.DATA_PACKAGE)))
                .load(manifest, bundle, SchoolId("other"), campus)
        }
        assertThrowsIllegalArgument {
            loaderReturning(listOf(building(CampusGraphSourceLevel.USER_OVERRIDE)))
                .load(manifest, bundle, school, campus)
        }
    }

    @Test
    fun loadRejectsDuplicateIdsAndFiltersRequestedCampus() {
        val bundle = bundle()
        val manifest = manifest(bundle)
        val lianhua = building(CampusGraphSourceLevel.DATA_PACKAGE)
        val songshanCampus = CampusId("songshanlu")
        val songshan = BuildingNode(
            ref = CampusGraphIds.build(school, songshanCampus, CampusGraphEntityType.BUILDING, "b_ss_t1"),
            name = "嵩山路1号教学楼",
            shortName = "嵩山1教",
            legacyBuildingId = "b_ss_t1",
            category = CampusCategory.TEACHING_BUILDING,
            parent = CampusGraphIds.build(school, songshanCampus, CampusGraphEntityType.CAMPUS, "songshanlu"),
            provenance = provenance(CampusGraphSourceLevel.DATA_PACKAGE)
        )

        assertThrowsIllegalArgument {
            loaderReturning(listOf(lianhua, lianhua)).load(manifest, bundle, school, campus)
        }
        val filtered = loaderReturning(listOf(lianhua, songshan)).load(manifest, bundle, school, campus)
        assertEquals(listOf(lianhua), filtered)
    }

    private fun loaderReturning(entities: List<CampusGraphEntity>): CampusGraphDataPackageLoader =
        CampusGraphDataPackageLoader(CampusGraphDataPackageDecoder { _, _ -> entities })

    private fun bundle(): PackageBundle {
        val file = PackageFile.of("assets/campus-graph.bin", byteArrayOf(1, 2, 3, 4))
        return PackageBundle.of(listOf(file))
    }

    private fun manifest(bundle: PackageBundle, fileDigestOverride: String? = null): PackageManifest {
        val files = bundle.files.associate { it.path to (fileDigestOverride ?: it.sha256) }
        return PackageManifest(
            id = "haut.campus-graph",
            version = PackageVersion("1.0.0"),
            type = PackageType.SCHOOL_PROFILE,
            publisher = "campus-maintainers",
            license = PackageLicense("Apache-2.0"),
            targets = listOf(PackageTarget(school)),
            minHostApi = 1,
            minAppVersionCode = 1,
            capabilities = setOf(CapabilityKind.CAMPUS_BUILDINGS),
            permissions = emptyList(),
            files = files,
            verification = PackageVerification(
                level = VerificationLevel.MOCK_TESTED,
                fixtureDigest = "0".repeat(64)
            )
        )
    }

    private fun building(sourceLevel: CampusGraphSourceLevel): BuildingNode = BuildingNode(
        ref = CampusGraphIds.build(school, campus, CampusGraphEntityType.BUILDING, "b_t1"),
        name = "31号楼（电气信息楼）",
        shortName = "31号楼",
        legacyBuildingId = "b_t1",
        category = CampusCategory.TEACHING_BUILDING,
        parent = campusRef,
        provenance = provenance(sourceLevel)
    )

    private fun provenance(sourceLevel: CampusGraphSourceLevel): CampusGraphProvenance = CampusGraphProvenance(
        sourceLevel = sourceLevel,
        provenance = CampusGraphProvenance.legacySnapshot(
            schoolId = school,
            providerId = "test-pack",
            sourceSystem = "test",
            sourceRecordId = "fixture"
        ),
        sourceReference = "test fixture"
    )

    private inline fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
