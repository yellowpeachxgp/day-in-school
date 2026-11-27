package cn.edu.haut.campus.core.solver

import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachingTimeGridTest {

    @Test
    fun firstWeekMondayRejectsEveryNonMondayWeekday() {
        val monday = LocalDate.of(2026, 3, 2)
        for (offset in 1L..6L) {
            val invalidDate = monday.plusDays(offset)
            val error = runCatching {
                SolverTestFixtures.grid().copy(firstWeekMonday = invalidDate)
            }.exceptionOrNull()

            assertTrue("expected rejection for " + invalidDate, error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("firstWeekMonday 必须是周一"))
        }
    }
}
