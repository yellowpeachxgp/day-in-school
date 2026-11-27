package cn.edu.haut.campus.core.solver

import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.selection.ClassCapacity
import cn.edu.haut.campus.core.selection.ClassSlot
import cn.edu.haut.campus.core.selection.TeachingWeeks
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Random

internal enum class BenchmarkFamily(val wireId: String) {
    MEDIUM_PLANTED("medium-planted"),
    SPARSE_MANY_SOLUTIONS("sparse-many-solutions"),
    DENSE_CONFLICT("dense-conflict"),
    NEAR_INFEASIBLE("near-infeasible"),
    UNCERTAIN_DATA("uncertain-data"),
    CROSS_CAMPUS("cross-campus")
}

internal data class SyntheticBenchmarkInstance(
    val size: Int,
    val family: BenchmarkFamily,
    val seed: Long,
    val problem: SelectionProblem,
    val requiredOfferingIds: List<String>,
    val plantedSolutionIds: List<String>?,
    val expectedCoreKinds: Set<ConstraintKind>,
    val canonicalJson: String,
    val sha256: String
) {
    val fixtureId: String get() = "S" + size + "-" + family.wireId + "-" + seed
}

/** R04 §7 固定种子合成基准生成器。所有作息、楼距与证据都来自显式夹具，不对应真实学校。 */
internal object SyntheticBenchmarkGenerator {
    val seeds: List<Long> = listOf(20260301L, 20260302L, 20260303L, 20260304L, 20260305L)
    val sizes: List<Int> = listOf(50, 100, 200)

    fun generate(size: Int, family: BenchmarkFamily, seed: Long): SyntheticBenchmarkInstance {
        return when (family) {
            BenchmarkFamily.NEAR_INFEASIBLE -> nearInfeasible(size, seed)
            BenchmarkFamily.UNCERTAIN_DATA -> uncertain(size, seed)
            BenchmarkFamily.CROSS_CAMPUS -> crossCampus(size, seed)
            else -> generateBase(size, family, seed)
        }
    }

    fun matrixCases(quickOnly: Boolean): List<Triple<Int, BenchmarkFamily, Long>> {
        val seed = seeds.first()
        return if (quickOnly) {
            listOf(
                Triple(50, BenchmarkFamily.MEDIUM_PLANTED, seed),
                Triple(50, BenchmarkFamily.DENSE_CONFLICT, seed),
                Triple(100, BenchmarkFamily.MEDIUM_PLANTED, seed),
                Triple(100, BenchmarkFamily.DENSE_CONFLICT, seed),
                Triple(200, BenchmarkFamily.MEDIUM_PLANTED, seed),
                Triple(200, BenchmarkFamily.DENSE_CONFLICT, seed),
                Triple(50, BenchmarkFamily.NEAR_INFEASIBLE, seed)
            )
        } else {
            sizes.flatMap { size ->
                BenchmarkFamily.entries.flatMap { family -> seeds.map { seed -> Triple(size, family, seed) } }
            }
        }
    }

    private data class SizeSpec(val classCounts: List<Int>, val requiredCount: Int) {
        val candidateCount: Int get() = classCounts.sum()
        val offeringCount: Int get() = classCounts.size
    }

    private fun spec(size: Int): SizeSpec = when (size) {
        50 -> SizeSpec(List(10) { 5 }, requiredCount = 4)
        100 -> SizeSpec(List(12) { 6 } + List(4) { 7 }, requiredCount = 5)
        200 -> SizeSpec(List(25) { 8 }, requiredCount = 6)
        else -> throw IllegalArgumentException("unsupported benchmark size: " + size)
    }

    private fun generateBase(size: Int, family: BenchmarkFamily, seed: Long): SyntheticBenchmarkInstance {
        val spec = spec(size)
        require(spec.candidateCount == size)
        val random = Random(seed * 1009L + size * 131L + family.ordinal)
        val grid = SolverTestFixtures.grid()
        val travel = benchmarkTravelMatrix()
        val fixed = fixedOccupancy()
        val offeringIds = spec.classCounts.indices.map { "OF" + it.toString().padStart(2, '0') }
        val required = offeringIds.take(spec.requiredCount)
        val credits = offeringIds.mapIndexed { index, _ -> 2000L + ((index * 7 + seed.toInt()) % 3) * 1000L }
        val requiredMilli = required.sumOf { credits[offeringIds.indexOf(it)] }
        val lowerMilli = requiredMilli + 5000L
        val upperMilli = requiredMilli + 8000L
        val plantedOfferings = choosePlantedOfferings(offeringIds, required, credits, lowerMilli, upperMilli)
        val plantedSlots = placePlantedSlots(plantedOfferings, fixed.flatMap { it.slots }, random)

        val candidates = ArrayList<TeachingClassOption>()
        var globalIndex = 0
        for (offeringIndex in spec.classCounts.indices) {
            val offeringId = offeringIds[offeringIndex]
            val classCount = spec.classCounts[offeringIndex]
            for (classIndex in 0 until classCount) {
                val id = offeringId + "-C" + classIndex.toString().padStart(2, '0')
                val planted = classIndex == 0 && plantedSlots.containsKey(offeringId)
                val slots = if (planted) {
                    plantedSlots.getValue(offeringId)
                } else {
                    randomSlots(random, globalIndex, family)
                }
                val capacity = if (planted) {
                    ClassCapacity.Known(total = 60, selected = 20)
                } else {
                    randomCapacity(random)
                }
                candidates += TeachingClassOption(
                    id = id,
                    offeringId = offeringId,
                    courseName = "合成课程" + offeringId,
                    credits = BigDecimal.valueOf(credits[offeringIndex], 3),
                    slots = slots,
                    capacity = capacity,
                    provenance = SolverTestFixtures.provenance(id)
                )
                globalIndex++
            }
        }

        val hard = ArrayList<HardConstraint>()
        required.forEach { hard += HardConstraint.RequireOffering(it, "合成课程" + it) }
        hard += HardConstraint.CapacityPolicy(excludeFull = true, allowUnknown = false)
        hard += HardConstraint.CommutePolicy(bufferMinutes = 10, unknownTravel = UnknownTravelPolicy.REJECT)

        val creditRange = milliToCredits(lowerMilli)..milliToCredits(upperMilli)
        val problem = SelectionProblem(
            candidates = candidates,
            hard = hard,
            soft = benchmarkSoftPreferences(),
            travel = travel,
            creditRange = creditRange,
            scope = SolverTestFixtures.scope,
            timeGrid = grid,
            fixedOccupancy = fixed
        )
        val plantedIds = plantedOfferings.map { it + "-C00" }
        return instance(size, family, seed, problem, required, plantedIds, emptySet())
    }

    private fun nearInfeasible(size: Int, seed: Long): SyntheticBenchmarkInstance {
        val base = generateBase(size, BenchmarkFamily.MEDIUM_PLANTED, seed)
        val firstTwo = base.requiredOfferingIds.take(2)
        val forcedSlots = listOf(
            SolverTestFixtures.slot(
                weekday = 2,
                start = 5,
                end = 6,
                weeks = TeachingWeeks.range(1, 16),
                location = SolverTestFixtures.location(SolverTestFixtures.north, "A02")
            )
        )
        val changed = base.problem.candidates.map { candidate ->
            if (candidate.offeringId in firstTwo) candidate.copy(slots = forcedSlots) else candidate
        }
        val problem = base.problem.copy(candidates = changed)
        return instance(size, BenchmarkFamily.NEAR_INFEASIBLE, seed, problem, base.requiredOfferingIds, null, setOf(ConstraintKind.REQUIRED_OFFERING))
    }

    private fun uncertain(size: Int, seed: Long): SyntheticBenchmarkInstance {
        val base = generateBase(size, BenchmarkFamily.MEDIUM_PLANTED, seed)
        val random = Random(seed + size * 17L)
        val changed = base.problem.candidates.map { candidate ->
            val unknownWeeks = random.nextInt(100) < 15
            val unknownLocation = random.nextInt(100) < 15
            val unknownCapacity = random.nextInt(100) < 12
            val slots = candidate.slots.map { slot ->
                var next = slot
                if (unknownWeeks) next = next.copy(time = next.time.copy(weeks = TeachingWeeks.UNKNOWN))
                if (unknownLocation) next = next.copy(location = null)
                next
            }
            candidate.copy(
                slots = slots,
                capacity = if (unknownCapacity) ClassCapacity.Unknown else candidate.capacity
            )
        }
        val problem = base.problem.copy(candidates = changed)
        return instance(size, BenchmarkFamily.UNCERTAIN_DATA, seed, problem, base.requiredOfferingIds, null, emptySet())
    }

    private fun crossCampus(size: Int, seed: Long): SyntheticBenchmarkInstance {
        val base = generateBase(size, BenchmarkFamily.MEDIUM_PLANTED, seed)
        val first = base.requiredOfferingIds.getOrNull(0)
        val second = base.requiredOfferingIds.getOrNull(1)
        val changed = base.problem.candidates.map { candidate ->
            when {
                candidate.offeringId == first && candidate.id.endsWith("C00") -> candidate.copy(
                    slots = listOf(SolverTestFixtures.slot(5, 5, 6, location = SolverTestFixtures.location(SolverTestFixtures.north, "A01")))
                )
                candidate.offeringId == second && candidate.id.endsWith("C00") -> candidate.copy(
                    slots = listOf(SolverTestFixtures.slot(5, 7, 8, location = SolverTestFixtures.location(SolverTestFixtures.south, "B01")))
                )
                else -> candidate
            }
        }
        val problem = base.problem.copy(candidates = changed)
        return instance(size, BenchmarkFamily.CROSS_CAMPUS, seed, problem, base.requiredOfferingIds, null, emptySet())
    }

    private fun instance(
        size: Int,
        family: BenchmarkFamily,
        seed: Long,
        problem: SelectionProblem,
        required: List<String>,
        plantedIds: List<String>?,
        expectedCoreKinds: Set<ConstraintKind>
    ): SyntheticBenchmarkInstance {
        val canonical = canonicalJson(size, family, seed, problem, required)
        return SyntheticBenchmarkInstance(
            size = size,
            family = family,
            seed = seed,
            problem = problem,
            requiredOfferingIds = required,
            plantedSolutionIds = plantedIds,
            expectedCoreKinds = expectedCoreKinds,
            canonicalJson = canonical,
            sha256 = sha256(canonical)
        )
    }

    private fun choosePlantedOfferings(
        offeringIds: List<String>,
        required: List<String>,
        credits: List<Long>,
        lowerMilli: Long,
        upperMilli: Long
    ): List<String> {
        val selected = ArrayList<String>()
        selected += required
        var total = required.sumOf { credits[offeringIds.indexOf(it)] }
        for (offering in offeringIds.drop(required.size)) {
            if (total >= lowerMilli) break
            selected += offering
            total += credits[offeringIds.indexOf(offering)]
        }
        require(total in lowerMilli..upperMilli) { "planted credit target cannot be met" }
        return selected
    }

    private fun placePlantedSlots(
        offerings: List<String>,
        fixedSlots: List<SolverClassSlot>,
        random: Random
    ): Map<String, List<SolverClassSlot>> {
        val result = LinkedHashMap<String, List<SolverClassSlot>>()
        val occupied = ArrayList<SolverClassSlot>()
        occupied += fixedSlots
        val starts = listOf(1, 3, 5, 7, 9)
        val weekChoices = listOf(
            TeachingWeeks.range(1, 16),
            TeachingWeeks.odd(1, 15),
            TeachingWeeks.even(2, 16)
        )
        for (offering in offerings.sorted()) {
            var placed: SolverClassSlot? = null
            outer@ for (weeks in weekChoices) {
                for (weekday in 1..5) {
                    for (start in starts) {
                        val candidate = SolverTestFixtures.slot(
                            weekday = weekday,
                            start = start,
                            end = start + 1,
                            weeks = weeks,
                            location = SolverTestFixtures.location(SolverTestFixtures.north, "A01")
                        )
                        if (occupied.none { overlap(it, candidate) }) {
                            placed = candidate
                            break@outer
                        }
                    }
                }
            }
            require(placed != null) { "unable to place synthetic planted offering" }
            occupied += placed
            result[offering] = listOf(placed)
        }
        return result
    }

    private fun randomSlots(random: Random, globalIndex: Int, family: BenchmarkFamily): List<SolverClassSlot> {
        val pattern = when (globalIndex % 20) {
            in 0..10 -> 0
            in 11..14 -> 1
            in 15..17 -> 2
            else -> 3
        }
        repeat(100) {
            val slots = when (pattern) {
                0 -> listOf(randomMeeting(random, 2, TeachingWeeks.range(1, 16), family), randomMeeting(random, 2, TeachingWeeks.range(1, 16), family))
                1 -> listOf(randomMeeting(random, 2, TeachingWeeks.range(1, 16), family), randomMeeting(random, 3, TeachingWeeks.range(1, 16), family))
                2 -> listOf(randomMeeting(random, 2, if (random.nextBoolean()) TeachingWeeks.odd(1, 15) else TeachingWeeks.even(2, 16), family))
                else -> listOf(randomMeeting(random, 2, TeachingWeeks.range(9, 16), family, weekendBias = true))
            }
            if (!overlap(slots.getOrNull(0), slots.getOrNull(1))) return slots
        }
        return listOf(randomMeeting(random, 2, TeachingWeeks.range(1, 16), family))
    }

    private fun randomMeeting(
        random: Random,
        length: Int,
        weeks: TeachingWeeks,
        family: BenchmarkFamily,
        weekendBias: Boolean = false
    ): SolverClassSlot {
        val maxStart = 12 - length + 1
        val dense = family == BenchmarkFamily.DENSE_CONFLICT && random.nextInt(100) < 70
        val weekday = when {
            dense -> listOf(1, 3, 5)[random.nextInt(3)]
            weekendBias && random.nextBoolean() -> 6 + random.nextInt(2)
            family == BenchmarkFamily.SPARSE_MANY_SOLUTIONS -> 1 + random.nextInt(7)
            else -> 1 + random.nextInt(5)
        }
        val start = when {
            dense -> listOf(1, 3, 5).filter { it + length - 1 <= 12 }[random.nextInt(listOf(1, 3, 5).count { it + length - 1 <= 12 })]
            family == BenchmarkFamily.SPARSE_MANY_SOLUTIONS -> 1 + random.nextInt(maxStart)
            else -> listOf(1, 3, 5, 7, 9).filter { it + length - 1 <= 12 }.let { it[random.nextInt(it.size)] }
        }
        return SolverTestFixtures.slot(weekday, start, start + length - 1, weeks, randomLocation(random, family))
    }

    private fun randomLocation(random: Random, family: BenchmarkFamily): SolverLocation {
        val southProbability = when (family) {
            BenchmarkFamily.DENSE_CONFLICT -> 10
            BenchmarkFamily.SPARSE_MANY_SOLUTIONS -> 40
            else -> 20
        }
        return if (random.nextInt(100) < southProbability) {
            SolverTestFixtures.location(SolverTestFixtures.south, "B" + (1 + random.nextInt(4)).toString().padStart(2, '0'))
        } else {
            SolverTestFixtures.location(SolverTestFixtures.north, "A" + (1 + random.nextInt(8)).toString().padStart(2, '0'))
        }
    }

    private fun randomCapacity(random: Random): ClassCapacity {
        val roll = random.nextInt(100)
        return when {
            roll < 8 -> ClassCapacity.Known(total = 40, selected = 40)
            roll < 20 -> ClassCapacity.Unknown
            else -> ClassCapacity.Known(total = 60, selected = 10 + random.nextInt(30))
        }
    }

    private fun overlap(left: SolverClassSlot?, right: SolverClassSlot?): Boolean {
        if (left == null || right == null) return false
        return left.sharesSectionWith(right) && left.weeks.intersects(right.weeks)
    }

    private fun fixedOccupancy(): List<FixedOccupancy> {
        val blocks = listOf(
            Triple(1, 1, 2), Triple(3, 1, 2),
            Triple(1, 3, 4), Triple(3, 3, 4),
            Triple(2, 1, 2), Triple(4, 1, 2),
            Triple(2, 3, 4), Triple(4, 3, 4)
        )
        return blocks.take(8).chunked(2).mapIndexed { index, pairs ->
            FixedOccupancy(
                id = "fixed-course-" + (index + 1),
                label = "固定课程" + (index + 1),
                slots = pairs.map { triple ->
                    SolverTestFixtures.slot(
                        weekday = triple.first,
                        start = triple.second,
                        end = triple.third,
                        weeks = TeachingWeeks.range(1, 16),
                        location = SolverTestFixtures.location(SolverTestFixtures.north, "A01")
                    )
                },
                provenance = SolverTestFixtures.provenance("fixed-course-" + (index + 1))
            )
        }
    }

    private fun benchmarkTravelMatrix(): TravelTimeMatrix {
        val locations = ArrayList<SolverLocation>()
        for (index in 1..8) locations += SolverTestFixtures.location(SolverTestFixtures.north, "A" + index.toString().padStart(2, '0'))
        for (index in 1..4) locations += SolverTestFixtures.location(SolverTestFixtures.south, "B" + index.toString().padStart(2, '0'))
        val entries = HashMap<TravelPair, TravelTimeEstimate>()
        for (from in locations) {
            for (to in locations) {
                val minutes = when {
                    from == to -> 0
                    from.campusId == to.campusId -> {
                        val diff = kotlin.math.abs((from.buildingId ?: "").removePrefix("A").removePrefix("B").toInt() - (to.buildingId ?: "").removePrefix("A").removePrefix("B").toInt())
                        when {
                            diff <= 1 -> 5
                            diff <= 3 -> 10
                            else -> 20
                        }
                    }
                    else -> 45
                }
                entries[TravelPair(from, to)] = TravelTimeEstimate.Reachable(minutes)
            }
        }
        return PrecomputedTravelTimeMatrix(entries)
    }

    private fun benchmarkSoftPreferences(): List<WeightedPreference> = listOf(
        WeightedPreference.EarlySection(latestEarlySection = 1, costPerOccurrenceMilli = 15),
        WeightedPreference.LateSection(firstLateSection = 10, costPerOccurrenceMilli = 10),
        WeightedPreference.GapTime(costPerHourMilli = 6),
        WeightedPreference.TravelTime(costPerMinuteMilli = 1),
        WeightedPreference.DailyLoad(maxSectionsPerDay = 8, costPerExtraSectionMilli = 12)
    )

    private fun canonicalJson(
        size: Int,
        family: BenchmarkFamily,
        seed: Long,
        problem: SelectionProblem,
        required: List<String>
    ): String {
        val candidates = problem.candidates.sortedBy { it.id }.joinToString(separator = ",") { candidate ->
            val slots = candidate.slots.sortedWith(compareBy<SolverClassSlot> { it.weekday }.thenBy { it.startSection }).joinToString(separator = ",") { slot ->
                "{\"endSection\":" + slot.endSection +
                    ",\"location\":" + quote(slot.location?.let { (it.campusId?.value ?: "unknown") + "/" + (it.buildingId ?: "unknown") } ?: "unknown") +
                    ",\"startSection\":" + slot.startSection +
                    ",\"weekday\":" + slot.weekday +
                    ",\"weeks\":" + quote(if (slot.weeks.isKnown) slot.weeks.weeks.sorted().joinToString("+") else "unknown") +
                    "}"
            }
            "{\"capacity\":" + quote(capacityText(candidate.capacity)) +
                ",\"credits\":" + quote(candidate.credits.stripTrailingZeros().toPlainString()) +
                ",\"id\":" + quote(candidate.id) +
                ",\"offeringId\":" + quote(candidate.offeringId) +
                ",\"slots\":[" + slots + "]}"
        }
        return "{\"candidates\":[" + candidates + "]" +
            ",\"creditRange\":[" + quote(problem.creditRange.start.stripTrailingZeros().toPlainString()) + "," + quote(problem.creditRange.endInclusive.stripTrailingZeros().toPlainString()) + "]" +
            ",\"family\":" + quote(family.wireId) +
            ",\"required\":[" + required.sorted().joinToString(separator = ",") { quote(it) } + "]" +
            ",\"seed\":" + seed +
            ",\"size\":" + size + "}"
    }

    private fun capacityText(capacity: ClassCapacity): String = when (capacity) {
        ClassCapacity.Unknown -> "unknown"
        is ClassCapacity.Known -> "known:" + capacity.total + ":" + capacity.selected
    }

    private fun quote(value: String): String {
        val out = StringBuilder()
        out.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }


    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { it.toInt().and(255).toString(16).padStart(2, '0') }
    }
}
