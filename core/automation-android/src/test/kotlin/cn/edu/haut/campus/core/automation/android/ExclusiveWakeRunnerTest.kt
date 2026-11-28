package cn.edu.haut.campus.core.automation.android

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ExclusiveWakeRunnerTest {
    @Test
    fun concurrentWakeCallsNeverOverlap() = runTest {
        val runner = ExclusiveWakeRunner()
        val gate = CompletableDeferred<Unit>()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val completed = AtomicInteger(0)

        val jobs = (1..12).map {
            async(Dispatchers.Default) {
                runner.call {
                    val nowActive = active.incrementAndGet()
                    maxActive.updateAndGet { old -> maxOf(old, nowActive) }
                    gate.await()
                    completed.incrementAndGet()
                    active.decrementAndGet()
                }
            }
        }
        kotlinx.coroutines.yield()
        gate.complete(Unit)
        jobs.awaitAll()

        assertEquals(12, completed.get())
        assertEquals(1, maxActive.get())
    }
}
